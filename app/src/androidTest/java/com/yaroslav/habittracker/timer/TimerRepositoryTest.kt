package com.yaroslav.habittracker.timer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryTest {
    @Test
    fun pausedTimerSavesExactlyOneSessionLinkedToBothHabits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        try {
            val habits = database.habits()
            val first = habits.insert(Habit(name = "Учёба", description = "", targetMinutes = 1))
            val second = habits.insert(Habit(name = "Чтение", description = "", targetMinutes = 1))
            var now = TimerMoment(wallMillis = 100_000L, elapsedMillis = 10_000L, bootCount = 1)
            val repository = TimerRepository(database) { now }
            val draft = repository.start(60_000L, setOf(first, second))
            assertEquals(draft.id, repository.start(null, emptySet()).id)

            now = TimerMoment(130_000L, 40_000L, 1)
            repository.togglePause(draft.id, pause = true)
            now = TimerMoment(250_000L, 160_000L, 1)
            assertNull(repository.finish(draft.id, expiredOnly = true))
            repository.togglePause(draft.id, pause = false)
            now = TimerMoment(300_000L, 210_000L, 1)

            val attempts = List(2) { async { repository.finish(draft.id, expiredOnly = true) } }.awaitAll()
            assertEquals(1, attempts.count { it != null })
            val snapshot = habits.snapshot()
            assertEquals(60_000L, snapshot.sessions.single().durationMillis)
            assertEquals(setOf(first, second), snapshot.links.map { it.habitId }.toSet())
            assertNull(database.timer().active())
            assertNotNull(database.timer().session(draft.id))
        } finally {
            database.close()
        }
    }
}
