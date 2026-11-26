package com.yaroslav.habittracker.planning

import com.yaroslav.habittracker.timer.FocusSession
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal data class DayTotals(val date: LocalDate, val plannedMinutes: Long, val actualMillis: Long)

internal fun LocalDate.weekStart(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal fun weekTotals(
    monday: LocalDate,
    schedule: PlanSchedule,
    sessions: List<FocusSession>,
): List<DayTotals> {
    val actualByDay = sessions.groupingBy { it.day }.fold(0L) { sum, session -> sum + session.durationMillis }
    return (0L..6L).map { offset ->
        val date = monday.plusDays(offset)
        val day = date.toEpochDay()
        DayTotals(
            date,
            schedule.on(day).sumOf { it.durationMinutes?.toLong() ?: 0L },
            actualByDay[day] ?: 0L,
        )
    }
}
