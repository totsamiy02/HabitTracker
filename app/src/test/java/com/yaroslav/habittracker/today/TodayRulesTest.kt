package com.yaroslav.habittracker.today

import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitCompletion
import com.yaroslav.habittracker.habits.HabitRecord
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.suggestedPlanSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayRulesTest {
    private val habit = Habit(1, "Учёба", "", targetMinutes = 120)
    private val morning = FocusSession("morning", 4, 45 * 60_000L, null, 0)
    private val evening = FocusSession("evening", 4, 75 * 60_000L, null, 1, planId = 8)

    @Test
    fun finishesTheDailyGoalOnlyOnce() {
        val next = evening.copy(id = "extra", durationMillis = 60_000L)
        val record = HabitRecord(habit, emptyList(), listOf(morning, evening, next))

        assertTrue(record.progress(4).earnedCellFrom(evening))
        assertFalse(record.progress(4).earnedCellFrom(next))
        assertFalse(record.progress(4).earnedCellFrom(evening.copy(id = "other")))
        assertFalse(record.progress(5).earnedCellFrom(evening))
        assertEquals(1, record.progress(4).completedDays)
    }

    @Test
    fun preservesTheExistingCellAndPartialProgress() {
        val short = evening.copy(durationMillis = 32 * 60_000L)
        val partial = HabitRecord(habit, emptyList(), listOf(morning, short))
        val marked = HabitRecord(habit, listOf(HabitCompletion(1, 4)), listOf(morning, evening))

        assertFalse(partial.progress(4).earnedCellFrom(short))
        assertEquals(77 * 60_000L, partial.progress(4).todayMillis)
        assertFalse(marked.progress(4).earnedCellFrom(evening))
    }

    @Test
    fun startsForThePlannedTimeOrItsRemainder() {
        val plan = DayPlan(8, "Учёба", 4, 19 * 60, habitId = 1, durationMinutes = 75)
        val progress = HabitRecord(habit, emptyList(), listOf(morning)).progress(4)

        assertEquals(4_500L, suggestedPlanSeconds(plan, progress))
        assertEquals(2_580L, suggestedPlanSeconds(plan, progress, recordedMillis = 32 * 60_000L))
        assertEquals(4_500L, suggestedPlanSeconds(plan.copy(durationMinutes = null), progress))
        assertEquals(1_500L, suggestedPlanSeconds(plan.copy(durationMinutes = null), null))
    }
}
