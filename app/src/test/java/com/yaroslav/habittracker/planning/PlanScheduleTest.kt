package com.yaroslav.habittracker.planning

import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.timer.FocusSession
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanScheduleTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private val day = monday.toEpochDay()

    @Test
    fun weekdaysKeepFirstDateButRespectExceptionsAndSeriesEnd() {
        val sunday = day - 1
        val plan = DayPlan(1, "Учёба", sunday, 120, repeatMask = WORKDAYS, endsBefore = day + 2)
        val schedule = PlanSchedule(listOf(plan), setOf(PlanSkip(plan.id, day + 1)))

        assertEquals(sunday, schedule.on(sunday).single().day)
        assertEquals(day, schedule.on(day).single().day)
        assertTrue(schedule.on(day + 1).isEmpty())
        assertTrue(schedule.on(day + 2).isEmpty())
        assertTrue(schedule.on(sunday - 1).isEmpty())
    }

    @Test
    fun reminderBeforeMidnightOpensTheFollowingDayAndKeepsTies() {
        val zone = ZoneId.of("Europe/Moscow")
        val lateEvening = monday.atTime(23, 30).atZone(zone)
        val first = DayPlan(1, "Учёба", day + 1, 5, reminderMinutes = 15)
        val second = first.copy(id = 2)
        val withoutReminder = first.copy(id = 3, reminderMinutes = null)
        val schedule = PlanSchedule(listOf(first, second, withoutReminder), emptySet())

        val alarms = schedule.nextReminders(lateEvening)
        assertEquals(listOf(1L, 2L), alarms.map { it.plan.id })
        assertEquals(day + 1, alarms.first().day)
        val expected = monday.atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, alarms.first().atMillis)
        assertTrue(schedule.nextReminders(lateEvening.plusMinutes(21)).isEmpty())
    }

    @Test
    fun weeklyPlanCountsOccurrencesAndFactCountsUnlinkedSessions() {
        val weekdayPlan = DayPlan(1, "Учёба", day, 120, durationMinutes = 30, repeatMask = WORKDAYS)
        val untimed = DayPlan(2, "Прогулка", day, 300)
        val schedule = PlanSchedule(listOf(weekdayPlan, untimed), setOf(PlanSkip(1, day + 1)))
        val sessions = listOf(
            FocusSession("linked", day, 2_700_000, 0L, 2_700_000, planId = 1),
            FocusSession("manual", day + 1, 900_000, null, 3_600_000),
            FocusSession("outside", day + 7, 3_600_000, null, 3_600_000),
        )

        val days = weekTotals(monday, schedule, sessions)
        assertEquals(monday, monday.plusDays(6).weekStart())
        assertEquals(7, days.size)
        assertEquals(120L, days.sumOf { it.plannedMinutes })
        assertEquals(3_600_000L, days.sumOf { it.actualMillis })
        assertEquals(DayTotals(monday.plusDays(1), 0, 900_000), days[1])
    }
}
