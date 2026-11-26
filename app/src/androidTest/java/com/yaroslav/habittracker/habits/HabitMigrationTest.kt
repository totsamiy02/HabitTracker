package com.yaroslav.habittracker.habits

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitMigrationTest {
    @Test
    fun upgradesVersionOneWithoutDroppingHabitOrHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL("CREATE TABLE habits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL)")
                old.execSQL(
                    """
                    CREATE TABLE habit_completions (
                        habitId INTEGER NOT NULL, day INTEGER NOT NULL, PRIMARY KEY(habitId, day),
                        FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                old.execSQL("INSERT INTO habits (id, name, description) VALUES (9, 'Прогулка', 'После работы')")
                old.execSQL("INSERT INTO habit_completions (habitId, day) VALUES (9, 15000)")
                old.version = 1
            }
            val database = Room.databaseBuilder(context, HabitDatabase::class.java, name).build()
            try {
                val habit = database.habits().observeProgress(15_000).first().single()
                assertEquals("Прогулка", habit.habit.name)
                assertEquals(ALL_WEEKDAYS, habit.habit.weekdays)
                assertNull(habit.habit.targetMinutes)
                assertEquals(1, habit.completedDays)
                assertTrue(habit.completedToday)
                assertTrue(database.habits().snapshot().sessions.isEmpty())
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
