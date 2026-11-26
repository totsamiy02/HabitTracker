package com.yaroslav.habittracker

import android.app.Application
import androidx.room.Room
import com.yaroslav.habittracker.habits.HabitDatabase

class HabitTrackerApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, HabitDatabase::class.java, "habittracker.db").build()
    }
}
