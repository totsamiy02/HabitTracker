package com.yaroslav.habittracker.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.yaroslav.habittracker.BuildConfig
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.io.File
import java.text.NumberFormat

@Composable
internal fun SettingsScreen(
    openUpdates: () -> Unit,
    openExport: () -> Unit,
    openRestore: () -> Unit,
    activeTimer: (@Composable () -> Unit)?,
) {
    ScreenContent {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold)

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(56.dp).background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(16.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(R.drawable.ic_launcher_foreground, Modifier.size(56.dp), tint = Color.Unspecified)
                    }
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                if (BuildConfig.DEBUG) R.string.app_version_debug else R.string.app_version,
                                BuildConfig.VERSION_NAME,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(stringResource(R.string.app_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        activeTimer?.invoke()
        SettingsRow(R.drawable.ic_update, R.string.app_update, R.string.update_subtitle, openUpdates)
        Text(stringResource(R.string.backup), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        SettingsRow(R.drawable.ic_download, R.string.export_data, R.string.export_subtitle, openExport)
        SettingsRow(R.drawable.ic_backup, R.string.restore_title, R.string.restore_subtitle, openRestore)
    }
}

@Composable
private fun SettingsRow(icon: Int, title: Int, subtitle: Int, open: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), onClick = open) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(13.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(icon)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(R.drawable.ic_next, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ExportScreen(
    model: SettingsViewModel,
    habitCount: Int,
    completionCount: Long,
    sessionCount: Int,
    planCount: Int,
) {
    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destination -> destination?.let(model::exportTo) }
    val filename = stringResource(R.string.backup_filename)
    val numberFormat = NumberFormat.getIntegerInstance()

    ScreenContent {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(R.drawable.ic_backup)
                    Text(stringResource(R.string.backup), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                }
                CountRow(R.string.habits, numberFormat.format(habitCount))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CountRow(R.string.backup_completions, numberFormat.format(completionCount))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CountRow(R.string.backup_sessions, numberFormat.format(sessionCount))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CountRow(R.string.calendar_backup_plans, numberFormat.format(planCount))
                Text(stringResource(R.string.backup_mosaic), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.backup_filename_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(filename, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = {
                try {
                    saveDocument.launch(filename)
                } catch (_: RuntimeException) {
                    model.exportPickerFailed()
                }
            },
            enabled = model.export != ExportState.Writing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_choose_location))
        }
        when (model.export) {
            ExportState.Idle -> StatusText(R.string.backup_picker_hint)
            ExportState.Writing -> ProgressText(R.string.backup_writing)
            ExportState.Saved -> StatusText(R.string.backup_saved)
            ExportState.Failed -> StatusText(R.string.backup_failed, error = true)
        }
    }
}

@Composable
private fun CountRow(label: Int, number: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), Modifier.weight(1f))
        Text(number, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun UpdateScreen(model: SettingsViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun openInstaller(file: File) {
        try {
            context.startActivity(installIntent(context, file))
            model.reportInstallNotice(null)
        } catch (_: RuntimeException) {
            model.reportInstallNotice(R.string.update_install_error)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ready = model.update as? UpdateState.Ready ?: return@rememberLauncherForActivityResult
        if (context.packageManager.canRequestPackageInstalls()) {
            openInstaller(ready.file)
        } else {
            model.reportInstallNotice(R.string.update_install_permission)
        }
    }

    fun requestInstall(file: File, openPermissionSettings: Boolean) {
        when {
            context.packageManager.canRequestPackageInstalls() -> openInstaller(file)
            openPermissionSettings -> {
                try {
                    permission.launch(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null),
                    ))
                } catch (_: RuntimeException) {
                    model.reportInstallNotice(R.string.update_install_error)
                }
            }
            else -> model.reportInstallNotice(R.string.update_install_permission)
        }
    }

    val request = model.installRequest
    LaunchedEffect(request) {
        if (request != null) {
            lifecycle.withResumed {
                model.clearInstallRequest()
                requestInstall(request, openPermissionSettings = false)
            }
        }
    }

    ScreenContent {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_installed_version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold)
            }
        }

        when (val state = model.update) {
            UpdateState.Idle -> StatusText(R.string.update_explanation)
            UpdateState.Checking -> ProgressText(R.string.update_checking)
            is UpdateState.CheckFailed -> StatusText(state.message, error = true)
            is UpdateState.Checked -> when (val result = state.result) {
                ReleaseCheck.NoRelease -> StatusText(R.string.update_no_release)
                ReleaseCheck.UpToDate -> StatusText(R.string.update_latest)
                is ReleaseCheck.Available -> AvailableUpdate(result.release) { model.download(result.release) }
            }
            is UpdateState.Downloading -> {
                ReleaseCard(state.release)
                ProgressText(R.string.update_downloading)
            }
            is UpdateState.DownloadFailed -> {
                AvailableUpdate(state.release) { model.download(state.release) }
                StatusText(state.message, error = true)
            }
            is UpdateState.Ready -> {
                ReleaseCard(state.release)
                Button(
                    onClick = { requestInstall(state.file, openPermissionSettings = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_install))
                }
                model.installNotice?.let { StatusText(it) }
            }
        }

        val state = model.update
        if (state !is UpdateState.Checking && state !is UpdateState.Downloading) {
            val available = state is UpdateState.Checked && state.result is ReleaseCheck.Available
            if (available || state is UpdateState.DownloadFailed || state is UpdateState.Ready) {
                TextButton(onClick = model::check, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_check))
                }
            } else {
                Button(onClick = model::check, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_check))
                }
            }
        }
    }
}

@Composable
private fun AvailableUpdate(release: GitHubRelease, download: () -> Unit) {
    ReleaseCard(release)
    Button(onClick = download, modifier = Modifier.fillMaxWidth(), enabled = !BuildConfig.DEBUG) {
        Text(stringResource(R.string.update_download))
    }
    StatusText(if (BuildConfig.DEBUG) R.string.update_debug_info else R.string.update_confirm_android)
}

@Composable
private fun ReleaseCard(release: GitHubRelease) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_available), color = MaterialTheme.colorScheme.primary)
            Text(release.version, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (release.notes.isNotBlank()) {
                Text(stringResource(R.string.update_notes), fontWeight = FontWeight.Bold)
                Text(release.notes)
            }
        }
    }
}

@Composable
private fun StatusText(message: Int, error: Boolean = false) {
    Text(
        stringResource(message),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProgressText(message: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusText(message)
    }
}

private fun installIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
