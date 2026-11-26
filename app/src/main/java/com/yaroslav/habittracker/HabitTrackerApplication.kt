package com.yaroslav.habittracker

import android.app.Application
import androidx.room.Room
import com.yaroslav.habittracker.habits.HabitDatabase
import com.yaroslav.habittracker.planning.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.yaroslav.habittracker.timer.TimerRepository
import com.yaroslav.habittracker.timer.deviceTimerClock

class HabitTrackerApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, HabitDatabase::class.java, "habittracker.db").build()
    }

    internal val timers by lazy { TimerRepository(database) { deviceTimerClock(this) } }
    internal val reminders by lazy { ReminderScheduler(this, database) }
    private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun refreshReminders() {
        reminderScope.launch {
            try {
                reminders.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("HabitTracker", "Could not schedule plan reminders", error)
            }
        }
    }
}
