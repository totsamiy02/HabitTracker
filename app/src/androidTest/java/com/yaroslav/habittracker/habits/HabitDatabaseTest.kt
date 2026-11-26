package com.yaroslav.habittracker.habits

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitDatabaseTest {
    @Test
    fun recordsOnlyOneCompletionPerDayAndPersistsAfterReopening() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "test-habit-${UUID.randomUUID()}.db"
        val day = LocalDate.of(2026, 11, 26).toEpochDay()

        val builder = Room.databaseBuilder(context, HabitDatabase::class.java, databaseName)
        var database = builder.build()
        try {
            val dao = database.habits()
            val habitId = dao.insert(Habit(name = "Прогулка", description = "После работы"))
            dao.mark(HabitCompletion(habitId, day))
            dao.mark(HabitCompletion(habitId, day))
            dao.mark(HabitCompletion(habitId, day + 1))

            database.close()
            database = builder.build()
            val reopenedDao = database.habits()
            val current = reopenedDao.observeProgress(day).first().single()
            assertEquals(habitId, current.habit.id)
            assertEquals("После работы", current.habit.description)
            assertTrue(current.completedToday)
            assertEquals(2, current.completedDays)
            assertFalse(reopenedDao.observeProgress(day + 2).first().single().completedToday)
            assertEquals(listOf(day, day + 1), reopenedDao.snapshot().completions.map { it.day })
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
        Unit
    }
}
