package com.yaroslav.habittracker.calendar

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitDatabase
import com.yaroslav.habittracker.planning.PlanEditScope
import com.yaroslav.habittracker.planning.WORKDAYS
import com.yaroslav.habittracker.timer.TimerMoment
import com.yaroslav.habittracker.timer.TimerRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarRepositoryTest {
    @Test
    fun editingSeriesPreservesPastAndDetachedOccurrences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        try {
            val monday = LocalDate.of(2026, 11, 23)
            val start = monday.toEpochDay()
            val calendar = CalendarRepository(database)
            val draft = DayPlan(title = "Учёба", day = start, minuteOfDay = 540, durationMinutes = 30, repeatMask = WORKDAYS)
            val original = calendar.save(draft)
            val friday = start + 4
            calendar.save(draft.copy(id = original, day = friday, title = "Практика"), friday, PlanEditScope.Single)
            val wednesday = start + 2
            calendar.save(draft.copy(id = original, day = wednesday, durationMinutes = 45), wednesday, PlanEditScope.Following)

            assertEquals(original, calendar.readDay(monday.plusDays(1)).plans.single().id)
            assertEquals(45, calendar.readDay(monday.plusDays(2)).plans.single().durationMinutes)
            assertEquals("Практика", calendar.readDay(monday.plusDays(4)).plans.single().title)
            assertTrue(calendar.readDay(monday.plusDays(5)).plans.isEmpty())
            assertEquals(180L, calendar.observeWeek(monday).first().sumOf { it.plannedMinutes })
        } finally {
            database.close()
        }
    }

    @Test
    fun twoActualSessionsAreSeparateFromThePlanAndEarnOneCell() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        try {
            val date = LocalDate.of(2026, 11, 26)
            val day = date.toEpochDay()
            val zone = ZoneId.systemDefault()
            val habitId = database.habits().insert(Habit(name = "Учёба", description = "", targetMinutes = 120))
            fun atHour(hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

            var now = TimerMoment(atHour(8), 1_000, 1)
            val timer = TimerRepository(database) { now }
            val calendar = CalendarRepository(database)
            val draftPlan = DayPlan(title = " Учёба ", day = day, minuteOfDay = 19 * 60, habitId = habitId)
            val planId = calendar.save(draftPlan)

            val morning = timer.start(45 * 60_000L, setOf(habitId))
            now = now.copy(wallMillis = atHour(8) + 45 * 60_000, elapsedMillis = 2_701_000)
            checkNotNull(timer.finish(morning.id, expiredOnly = true))

            val before = withTimeout(5_000) { calendar.observeDay(date).first() }
            val savedPlan = before.plans.single()
            assertEquals("Учёба", savedPlan.title)
            assertEquals(planId, savedPlan.id)
            assertEquals(45 * 60_000L, before.progress.single().todayMillis)
            assertEquals(0, before.progress.single().completedDays)
            assertFalse(before.progress.single().completedToday)

            val updated = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { calendar.observeDay(date).first { it.sessions.size == 2 } }
            }
            now = TimerMoment(atHour(19), 50_000_000, 1)
            val afternoon = timer.start(75 * 60_000L, emptySet(), planId)
            assertEquals(listOf(habitId), afternoon.habitIds)
            now = now.copy(wallMillis = atHour(19) + 75 * 60_000, elapsedMillis = 54_500_000)
            checkNotNull(timer.finish(afternoon.id, expiredOnly = true))
            val after = updated.await()

            assertEquals(listOf(atHour(8), atHour(19)), after.sessions.map { it.session.startedAtMillis })
            assertEquals(listOf(45 * 60_000L, 75 * 60_000L), after.sessions.map { it.session.durationMillis })
            assertEquals(listOf(null, planId), after.sessions.map { it.session.planId })
            assertEquals(120 * 60_000L, after.progress.single().todayMillis)
            assertEquals(1, after.progress.single().completedDays)
            assertTrue(after.progress.single().completedToday)
            assertEquals(setOf(day), after.daysWithEntries)

            val tomorrow = date.plusDays(1)
            calendar.save(savedPlan.copy(day = tomorrow.toEpochDay(), minuteOfDay = 20 * 60))
            val originalDay = calendar.readDay(date)
            val movedDay = calendar.readDay(tomorrow)
            assertTrue(originalDay.plans.isEmpty())
            assertEquals(2, originalDay.sessions.size)
            assertEquals(planId, originalDay.sessions.last().session.planId)
            assertEquals(planId, movedDay.plans.single().id)
            assertTrue(movedDay.sessions.isEmpty())
            assertFalse(movedDay.progress.single().completedToday)
            assertEquals(setOf(day, tomorrow.toEpochDay()), movedDay.daysWithEntries)
            assertNull(database.timer().active())
        } finally {
            database.close()
        }
    }
}
