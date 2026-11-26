package com.yaroslav.habittracker.timer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class TimerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HabitTrackerApplication
    private val repository = app.timers

    val active = repository.active.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activePlan = active.map { it?.planId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(null) else app.database.calendar().observePlan(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var saved by mutableStateOf<FocusSession?>(null)
        private set
    var confirmFromNotification by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            var previous: ActiveTimer? = null
            active.collect { timer ->
                if (previous?.running == true && timer == null && saved == null) {
                    // Read the prior session by ID. A finished timer is saved atomically with clearing its draft.
                    try {
                        val previousId = previous?.id ?: return@collect
                        saved = app.database.timer().session(previousId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        notice = R.string.timer_error
                    }
                }
                previous = timer
            }
        }
    }

    fun now() = repository.now()

    fun requestFromNotification(confirm: Boolean) {
        confirmFromNotification = confirm
    }

    fun clearConfirmation() {
        confirmFromNotification = false
    }

    fun clearFeedback() {
        saved = null
        notice = null
    }

    fun start(limitMillis: Long?, habitIds: Set<Long>, planId: Long? = null) = operate {
        saved = null
        confirmFromNotification = false
        val timer = repository.start(limitMillis, habitIds, planId)
        TimerRuntime.synchronizeAlarm(app, timer, now())
        TimerRuntime.monitor(app)
    }

    fun toggle(timer: ActiveTimer) = operate {
        repository.togglePause(timer.id, pause = timer.running)
        val updated = app.database.timer().active() ?: return@operate
        TimerRuntime.synchronizeAlarm(app, updated, now())
        TimerRuntime.monitor(app)
    }

    fun finish(timer: ActiveTimer) = operate {
        val result = TimerRuntime.finish(app, timer.id, expiredOnly = false)
        if (result != null) saved = result
        TimerRuntime.cancelAlarm(app, timer.id)
    }

    fun reconcile() = operate {
        val timer = app.database.timer().active() ?: return@operate
        if (timer.running && timer.remainingAt(now()) == 0L) {
            saved = TimerRuntime.finish(app, timer.id, expiredOnly = true)
        } else {
            TimerRuntime.synchronizeAlarm(app, timer, now())
            TimerRuntime.monitor(app)
        }
    }

    private fun operate(task: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        viewModelScope.launch {
            try {
                task()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notice = R.string.timer_error
            } finally {
                busy = false
            }
        }
    }
}
