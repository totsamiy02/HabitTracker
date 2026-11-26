package com.yaroslav.habittracker.habits

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.timer.MAX_SESSION_MINUTES
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class Tab { Today, Calendar, Tasks, Settings }

internal sealed interface Screen {
    data object Home : Screen
    data class HabitEditor(val id: Long? = null) : Screen
    data class HabitDetails(val id: Long) : Screen
    data class History(val id: Long) : Screen
    data class EditTime(val habitId: Long, val sessionId: String? = null) : Screen
    data object AllHabits : Screen
    data object BulkMark : Screen
    data class Timer(val habitId: Long? = null, val plan: DayPlan? = null) : Screen
    data object Updates : Screen
    data object Export : Screen
    data object Restore : Screen
    data object Week : Screen
}

internal sealed interface HabitsState {
    data object Loading : HabitsState
    data object Failed : HabitsState
    data class Loaded(val date: LocalDate, val habits: List<HabitProgress>) : HabitsState {
        val scheduled: List<HabitProgress> get() = habits.filter { it.habit.isScheduled(date.dayOfWeek) }
    }
}

internal class HabitsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HabitTrackerApplication
    private val dao = app.database.habits()
    private data class DayQuery(val date: LocalDate = LocalDate.now(), val attempt: Int = 0)
    private val dayQuery = MutableStateFlow(DayQuery())

    @OptIn(ExperimentalCoroutinesApi::class)
    val habits = dayQuery.flatMapLatest { (date) ->
        dao.observeProgress(date.toEpochDay())
            .map<List<HabitProgress>, HabitsState> { HabitsState.Loaded(date, it) }
            .catch { emit(HabitsState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsState.Loading)

    var tab by mutableStateOf(Tab.Today)
        private set
    private var stack by mutableStateOf(listOf<Screen>(Screen.Home))
    val screen: Screen get() = stack.last()

    var writing by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set

    fun selectTab(tab: Tab) {
        this.tab = tab
        stack = listOf(Screen.Home)
    }

    fun open(screen: Screen) {
        if (this.screen != screen) stack = stack + screen
    }

    fun goBack() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun refreshDate() {
        dayQuery.update { it.copy(date = LocalDate.now()) }
    }

    fun retry() {
        dayQuery.update { it.copy(date = LocalDate.now(), attempt = it.attempt + 1) }
    }

    fun saveHabit(id: Long?, name: String, description: String, weekdays: Int, goal: Int?) {
        val title = name.trim()
        if (title.isEmpty() || weekdays !in 1..ALL_WEEKDAYS ||
            (goal != null && goal !in 1..MAX_SESSION_MINUTES)
        ) return

        write(R.string.habit_save_error) {
            val editor = Screen.HabitEditor(id)
            val habit = Habit(id ?: 0L, title, description.trim(), weekdays, goal)
            if (id == null) {
                val created = dao.insert(habit)
                if (screen == editor) stack = stack.dropLast(1) + Screen.HabitDetails(created)
            } else {
                app.database.withTransaction {
                    val previous = requireNotNull(dao.record(id))
                    val oldGoal = previous.habit.targetMinutes
                    if (oldGoal != null && goal == null) {
                        val byDay = previous.sessions.groupingBy { it.day }.fold(0L) { sum, session ->
                            sum + session.durationMillis
                        }
                        for ((day, duration) in byDay) {
                            if (duration >= oldGoal * 60_000L) dao.mark(HabitCompletion(id, day))
                        }
                    }
                    dao.update(habit)
                }
                if (screen == editor) goBack()
            }
        }
    }

    fun markToday(habitId: Long) = markToday(setOf(habitId), closeBulk = false)

    fun markToday(ids: Set<Long>, closeBulk: Boolean) {
        if (ids.isEmpty()) return
        val day = LocalDate.now()
        refreshDate()
        write(R.string.habit_mark_error) {
            dao.markSeveral(ids, day.toEpochDay())
            if (closeBulk && screen == Screen.BulkMark) goBack()
        }
    }

    fun setMark(habitId: Long, date: LocalDate, complete: Boolean, previousDay: Long? = null) {
        if (date.isAfter(LocalDate.now())) return
        val day = date.toEpochDay()
        write(R.string.habit_mark_error) {
            if (!complete) {
                dao.removeMark(HabitCompletion(habitId, day))
            } else if (previousDay != null) {
                if (!dao.moveMark(habitId, previousDay, day)) showNotice(R.string.habit_mark_exists)
            } else {
                if (dao.mark(HabitCompletion(habitId, day)) == -1L) showNotice(R.string.habit_mark_exists)
            }
        }
    }

    fun saveManualTime(habitId: Long, date: LocalDate, durationMillis: Long, sessionId: String?) {
        write(R.string.time_save_error) {
            app.timers.saveManual(habitId, date, durationMillis, sessionId)
            if (screen == Screen.EditTime(habitId, sessionId)) goBack()
        }
    }

    fun removeSession(habitId: Long, sessionId: String) {
        write(R.string.time_save_error) {
            app.timers.deleteSession(habitId, sessionId)
            if (screen == Screen.EditTime(habitId, sessionId)) goBack()
        }
    }

    fun showNotice(@StringRes message: Int) {
        notice = message
    }

    fun clearNotice() {
        notice = null
    }

    private fun write(@StringRes error: Int, task: suspend () -> Unit) {
        if (writing) return
        writing = true
        viewModelScope.launch {
            try {
                task()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showNotice(error)
            } finally {
                writing = false
            }
        }
    }
}
