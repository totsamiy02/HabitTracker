package com.yaroslav.habittracker.habits

import com.yaroslav.habittracker.timer.FocusSession
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitProgressTest {
    @Test
    fun sessionsAccumulateToOneCellAndRecalculateAfterCorrection() {
        val habit = Habit(id = 1, name = "Учёба", description = "", targetMinutes = 120)
        val first = FocusSession("morning", 4, 45L * 60_000, null, 0)
        val second = FocusSession("evening", 4, 75L * 60_000, null, 1)
        val complete = HabitRecord(habit, emptyList(), listOf(first, second))
        val corrected = complete.copy(sessions = listOf(first, second.copy(durationMillis = 44L * 60_000)))

        assertEquals(1, complete.progress(4).completedDays)
        assertTrue(complete.progress(4).completedToday)
        assertEquals(89L * 60_000, corrected.progress(4).todayMillis)
        assertFalse(corrected.progress(4).completedToday)
        assertEquals(0, corrected.progress(4).completedDays)
    }

    @Test
    fun manualAndTimeCompletionForTheSameDayDoNotDoubleCount() {
        val habit = Habit(id = 2, name = "Чтение", description = "", targetMinutes = 1)
        val record = HabitRecord(
            habit, listOf(HabitCompletion(habit.id, 4)),
            listOf(FocusSession("shared", 4, 120_000, null, 0)),
        )
        assertEquals(1, record.progress(4).completedDays)
        assertFalse(record.progress(5).completedToday)
    }

    @Test
    fun weeklyScheduleUsesIndependentDays() {
        val plan = weekdayBit(DayOfWeek.MONDAY) or weekdayBit(DayOfWeek.FRIDAY)
        val habit = Habit(name = "Прогулка", description = "", weekdays = plan)
        assertTrue(habit.isScheduled(DayOfWeek.FRIDAY))
        assertFalse(habit.isScheduled(DayOfWeek.SATURDAY))
    }
}
