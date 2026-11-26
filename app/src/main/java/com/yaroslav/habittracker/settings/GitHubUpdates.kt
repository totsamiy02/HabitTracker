package com.yaroslav.habittracker.settings

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.yaroslav.habittracker.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class GitHubRelease(
    val version: String,
    val versionCode: Long,
    val notes: String,
    val apk: URI,
    val size: Long,
)

internal sealed interface ReleaseCheck {
    data object NoRelease : ReleaseCheck
    data object UpToDate : ReleaseCheck
    data class Available(val release: GitHubRelease) : ReleaseCheck
}

internal class InvalidUpdateException : IOException("Invalid update APK")

private const val MAX_RELEASE_CHARACTERS = 1_048_576
private const val MAX_APK_BYTES = 200L * 1024 * 1024

private val apkNamePattern =
    Regex("""HabitTracker-([0-9]+\.[0-9]+\.[0-9]+)-build([1-9][0-9]*)\.apk""")

internal fun releaseVersionCode(asset: String, tag: String): Long? {
    val match = apkNamePattern.matchEntire(asset) ?: return null
    val (version, code) = match.destructured
    return code.toLongOrNull()?.takeIf { tag == "v$version" }
}

internal class GitHubUpdates(private val context: Context) {
    private val repository = BuildConfig.GITHUB_REPOSITORY

    suspend fun latest(): ReleaseCheck = withContext(Dispatchers.IO) {
        val endpoint = URI("https://api.github.com/repos/$repository/releases/latest")
        request(endpoint, setOf("api.github.com"), "application/vnd.github+json") { response ->
            when (response.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> ReleaseCheck.NoRelease
                HttpURLConnection.HTTP_OK -> {
                    val payload = response.inputStream.bufferedReader().use { it.readReleasePayload() }
                    val release = parseRelease(JSONObject(payload))
                    if (release.versionCode > BuildConfig.VERSION_CODE) {
                        ReleaseCheck.Available(release)
                    } else {
                        ReleaseCheck.UpToDate
                    }
                }
                else -> throw IOException("GitHub release request failed")
            }
        }
    }

    private fun parseRelease(release: JSONObject): GitHubRelease {
        val tag = release.getString("tag_name")
        val assets = release.getJSONArray("assets")
        val candidates = (0 until assets.length()).mapNotNull { index ->
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            val code = releaseVersionCode(name, tag) ?: return@mapNotNull null
            val uri = URI(asset.getString("browser_download_url"))
            val size = asset.getLong("size")
            requireTrustedHost(uri, setOf("github.com"))
            val expectedPath = "/$repository/releases/download/$tag/$name"
            if (!uri.path.equals(expectedPath, ignoreCase = true) || size !in 1..MAX_APK_BYTES) {
                throw InvalidUpdateException()
            }
            GitHubRelease(
                version = tag.removePrefix("v"),
                versionCode = code,
                notes = release.optString("body").takeUnless { it == "null" }.orEmpty().trim(),
                apk = uri,
                size = size,
            )
        }
        return candidates.singleOrNull() ?: throw InvalidUpdateException()
    }

    suspend fun download(release: GitHubRelease): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create cache")
        val target = File(directory, "HabitTracker-${release.versionCode}.apk")
        val partial = File.createTempFile("download-", ".apk", context.cacheDir)
        try {
            val coroutineContext = currentCoroutineContext()
            request(
                release.apk,
                setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
                "application/octet-stream",
            ) { response ->
                if (response.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("APK download failed")
                }
                response.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            copied += count
                            if (copied > release.size) throw InvalidUpdateException()
                            output.write(buffer, 0, count)
                        }
                        if (copied != release.size) throw InvalidUpdateException()
                    }
                }
            }
            verifyApk(partial, release)
            if (!partial.renameTo(target)) throw IOException("Cannot store downloaded APK")
            target
        } finally {
            partial.delete()
        }
    }

    private fun BufferedReader.readReleasePayload(): String = buildString {
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            if (length + count > MAX_RELEASE_CHARACTERS) throw InvalidUpdateException()
            appendRange(buffer, 0, count)
        }
    }

    @Suppress("DEPRECATION") // Integer flags and legacy signatures support Android 8.
    private fun verifyApk(file: File, release: GitHubRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = manager.getPackageInfo(context.packageName, flags)
        val update = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw InvalidUpdateException()

        val signers = installed.signers()
        if (update.packageName != context.packageName ||
            update.versionCodeCompat() != release.versionCode ||
            update.versionName != release.version ||
            release.versionCode <= installed.versionCodeCompat() ||
            signers.isEmpty() || signers != update.signers()
        ) {
            throw InvalidUpdateException()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<Signature> =
        if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.apkContentsSigners.orEmpty().toSet()
        } else {
            signatures.orEmpty().toSet()
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private fun <T> request(
        origin: URI,
        hosts: Set<String>,
        accept: String,
        read: (HttpURLConnection) -> T,
    ): T {
        var uri = origin
        repeat(6) {
            requireTrustedHost(uri, hosts)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", "HabitTracker")
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Missing redirect location")
                        uri = uri.resolve(location)
                    }
                    else -> return read(connection)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun requireTrustedHost(uri: URI, hosts: Set<String>) {
        if (uri.scheme != "https" || uri.host !in hosts || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            throw InvalidUpdateException()
        }
    }
}
