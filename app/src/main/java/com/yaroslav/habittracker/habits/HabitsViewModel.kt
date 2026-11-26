package com.yaroslav.habittracker.habits

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R
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

internal enum class Tab { Today, Settings }

internal sealed interface Screen {
    data object Home : Screen
    data object CreateHabit : Screen
    data class HabitDetails(val id: Long) : Screen
    data object Updates : Screen
    data object Export : Screen
}

internal sealed interface HabitsState {
    data object Loading : HabitsState
    data object Failed : HabitsState
    data class Loaded(val date: LocalDate, val habits: List<HabitProgress>) : HabitsState
}

internal class HabitsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as HabitTrackerApplication).database.habits()
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
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var creating by mutableStateOf(false)
        private set
    var markingHabitId by mutableStateOf<Long?>(null)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set

    fun selectTab(tab: Tab) {
        this.tab = tab
        screen = Screen.Home
    }

    fun open(screen: Screen) {
        this.screen = screen
    }

    fun goBack() {
        screen = Screen.Home
    }

    fun refreshDate() {
        dayQuery.update { it.copy(date = LocalDate.now()) }
    }

    fun retry() {
        dayQuery.update { it.copy(date = LocalDate.now(), attempt = it.attempt + 1) }
    }

    fun createHabit(name: String, description: String) {
        val title = name.trim()
        if (title.isEmpty() || creating) return

        creating = true
        viewModelScope.launch {
            try {
                val id = dao.insert(Habit(name = title, description = description.trim()))
                if (screen == Screen.CreateHabit) open(Screen.HabitDetails(id))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showNotice(R.string.habit_save_error)
            } finally {
                creating = false
            }
        }
    }

    fun markToday(habitId: Long) {
        if (markingHabitId != null) return
        val date = LocalDate.now()
        refreshDate()
        markingHabitId = habitId
        viewModelScope.launch {
            try {
                dao.mark(HabitCompletion(habitId, date.toEpochDay()))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showNotice(R.string.habit_mark_error)
            } finally {
                markingHabitId = null
            }
        }
    }

    fun showNotice(@StringRes message: Int) {
        notice = message
    }

    fun clearNotice() {
        notice = null
    }
}
