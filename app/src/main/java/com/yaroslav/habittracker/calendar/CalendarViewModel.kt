package com.yaroslav.habittracker.calendar

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.planning.PlanEditScope
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface CalendarState {
    val date: LocalDate

    data class Loading(override val date: LocalDate) : CalendarState
    data class Failed(override val date: LocalDate) : CalendarState
    data class Ready(val agenda: CalendarDay) : CalendarState {
        override val date: LocalDate get() = agenda.date
    }
}

internal class CalendarViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as HabitTrackerApplication
    private val repository = CalendarRepository(app.database)
    private data class DayQuery(val date: LocalDate, val attempt: Int = 0)
    private val initial = savedState.get<Long>("calendar_day")?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    private val query = MutableStateFlow(DayQuery(initial))

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = query.flatMapLatest { (date) ->
        repository.observeDay(date)
            .map<CalendarDay, CalendarState> { CalendarState.Ready(it) }
            .onStart { emit(CalendarState.Loading(date)) }
            .catch { emit(CalendarState.Failed(date)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarState.Loading(initial))

    var writing by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set

    fun select(date: LocalDate) {
        if (date.year !in CALENDAR_YEARS) return
        savedState["calendar_day"] = date.toEpochDay()
        query.update { it.copy(date = date) }
    }

    fun moveMonth(offset: Long) {
        val date = query.value.date
        val month = YearMonth.from(date).plusMonths(offset)
        select(month.atDay(minOf(date.dayOfMonth, month.lengthOfMonth())))
    }

    fun retry() = query.update { it.copy(attempt = it.attempt + 1) }

    fun beginEdit() {
        saveFailed = false
    }

    fun save(plan: DayPlan, occurrenceDay: Long?, scope: PlanEditScope, onSaved: () -> Unit) {
        if (writing) return
        writing = true
        saveFailed = false
        viewModelScope.launch {
            try {
                repository.save(plan, occurrenceDay, scope)
                app.refreshReminders()
                select(LocalDate.ofEpochDay(plan.day))
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                writing = false
            }
        }
    }
}
