package com.yaroslav.habittracker.settings

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.BuildConfig
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Checked(val result: ReleaseCheck) : UpdateState
    data class CheckFailed(val message: Int) : UpdateState
    data class Downloading(val release: GitHubRelease) : UpdateState
    data class DownloadFailed(val release: GitHubRelease, val message: Int) : UpdateState
    data class Ready(val release: GitHubRelease, val file: File) : UpdateState
}

internal enum class ExportState { Idle, Writing, Saved, Failed }

internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HabitTrackerApplication
    val sessionCount = app.database.habits().observeSessionCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val planCount = app.database.habits().observePlanCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val backup = BackupExporter(app.database.habits(), app.contentResolver)
    private val updates = GitHubUpdates(app)

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    var export by mutableStateOf(ExportState.Idle)
        private set
    var installRequest by mutableStateOf<File?>(null)
        private set
    var installNotice by mutableStateOf<Int?>(null)
        private set

    fun check() {
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        installNotice = null
        installRequest = null
        update = UpdateState.Checking
        viewModelScope.launch {
            update = try {
                UpdateState.Checked(updates.latest())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: InvalidUpdateException) {
                UpdateState.CheckFailed(R.string.update_release_invalid)
            } catch (_: Exception) {
                UpdateState.CheckFailed(R.string.update_check_error)
            }
        }
    }

    fun download(release: GitHubRelease) {
        if (BuildConfig.DEBUG || update is UpdateState.Downloading) return
        installNotice = null
        update = UpdateState.Downloading(release)
        viewModelScope.launch {
            update = try {
                val file = updates.download(release)
                installRequest = file
                UpdateState.Ready(release, file)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: InvalidUpdateException) {
                UpdateState.DownloadFailed(release, R.string.update_apk_invalid)
            } catch (_: Exception) {
                UpdateState.DownloadFailed(release, R.string.update_download_error)
            }
        }
    }

    fun clearInstallRequest() {
        installRequest = null
    }

    fun reportInstallNotice(message: Int?) {
        installNotice = message
    }

    fun exportTo(uri: Uri) {
        if (export == ExportState.Writing) return
        export = ExportState.Writing
        viewModelScope.launch {
            export = try {
                backup.writeTo(uri)
                ExportState.Saved
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                ExportState.Failed
            }
        }
    }

    fun exportPickerFailed() {
        export = ExportState.Failed
    }
}
