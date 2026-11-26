package com.yaroslav.habittracker.settings

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface RestoreState {
    data object Idle : RestoreState
    data object Reading : RestoreState
    data object Writing : RestoreState
    data class Preview(
        val selected: SelectedBackup,
        val current: BackupCounts,
        @StringRes val message: Int? = null,
    ) : RestoreState
    data class Saved(val counts: BackupCounts) : RestoreState
    data class Failed(@StringRes val message: Int) : RestoreState
}

internal class RestoreViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HabitTrackerApplication
    private val reader = BackupReader(app.contentResolver)
    private val repository = RestoreRepository(app.database)

    var state by mutableStateOf<RestoreState>(RestoreState.Idle)
        private set

    fun pickerFailed() {
        if (state != RestoreState.Writing && state != RestoreState.Reading) {
            state = RestoreState.Failed(R.string.restore_read_error)
        }
    }

    fun read(uri: Uri) {
        if (state == RestoreState.Writing || state == RestoreState.Reading) return
        state = RestoreState.Reading
        viewModelScope.launch {
            state = try {
                RestoreState.Preview(reader.read(uri), repository.counts())
            } catch (error: CancellationException) {
                throw error
            } catch (_: UnsupportedBackup) {
                RestoreState.Failed(R.string.restore_unsupported)
            } catch (_: FileNotFoundException) {
                RestoreState.Failed(R.string.restore_read_error)
            } catch (_: SecurityException) {
                RestoreState.Failed(R.string.restore_read_error)
            } catch (_: Exception) {
                RestoreState.Failed(R.string.restore_invalid)
            }
        }
    }

    fun apply(preview: RestoreState.Preview) {
        if (state !== preview) return
        state = RestoreState.Writing
        viewModelScope.launch {
            state = try {
                val snapshot = preview.selected.payload.snapshot
                when (val outcome = repository.replace(snapshot, preview.current)) {
                    RestoreResult.Applied -> {
                        app.refreshReminders()
                        RestoreState.Saved(snapshot.counts())
                    }
                    RestoreResult.RunningTimer -> preview.copy(message = R.string.restore_timer)
                    is RestoreResult.Changed -> preview.copy(current = outcome.counts, message = R.string.restore_changed)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                preview.copy(message = R.string.restore_write_error)
            }
        }
    }
}
