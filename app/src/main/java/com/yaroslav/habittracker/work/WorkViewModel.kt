package com.yaroslav.habittracker.work

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface WorkLoad<out T> {
    data object Loading : WorkLoad<Nothing>
    data object Failed : WorkLoad<Nothing>
    data class Ready<T>(val value: T) : WorkLoad<T>
}

internal class WorkViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as HabitTrackerApplication).database.work()
    private val attempt = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val templates = attempt.flatMapLatest { dao.observeTemplates().asLoad() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkLoad.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(period: WorkPeriod, includeOvernight: Boolean = false) = attempt.flatMapLatest {
        dao.observeShifts(period.from.toEpochDay() - if (includeOvernight) 1L else 0L, period.through.toEpochDay())
            .asLoad()
    }

    var reportChoice by mutableStateOf(ReportChoice())

    var writing by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun retry() { attempt.update { it + 1 } }

    fun beginEdit() { failed = false }

    fun save(shift: WorkShift, finished: () -> Unit) = write(finished) {
        require(shift.isValid())
        if (shift.id == 0L) dao.insertShift(shift) else check(dao.updateShift(shift) == 1)
    }

    fun save(template: ShiftTemplate, finished: () -> Unit) = write(finished) {
        require(template.isValid())
        if (template.id == 0L) dao.insertTemplate(template) else check(dao.updateTemplate(template) == 1)
    }

    fun delete(shift: WorkShift, finished: () -> Unit) = write(finished) { dao.deleteShift(shift) }
    fun delete(template: ShiftTemplate, finished: () -> Unit) = write(finished) { dao.deleteTemplate(template) }

    private fun write(finished: () -> Unit, block: suspend () -> Unit) {
        if (writing) return
        failed = false
        writing = true
        viewModelScope.launch {
            try {
                block()
                finished()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                writing = false
            }
        }
    }
}

private fun <T> Flow<T>.asLoad() = map<T, WorkLoad<T>> { WorkLoad.Ready(it) }
    .onStart { emit(WorkLoad.Loading) }
    .catch { emit(WorkLoad.Failed) }
