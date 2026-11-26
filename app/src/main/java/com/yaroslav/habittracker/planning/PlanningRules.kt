package com.yaroslav.habittracker.planning

import androidx.room.Entity
import androidx.room.ForeignKey
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.habits.weekdayBit
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

internal const val MAX_PLAN_MINUTES = 1_440
internal const val WORKDAYS = 31
internal val REMINDER_OFFSETS = listOf(0, 5, 15, 30)

internal enum class PlanEditScope { Single, Following }

@Entity(
    tableName = "plan_skips",
    primaryKeys = ["planId", "day"],
    foreignKeys = [
        ForeignKey(
            entity = DayPlan::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlanSkip(val planId: Long, val day: Long)

internal fun DayPlan.covers(date: Long): Boolean {
    if (date < day || endsBefore?.let { date >= it } == true) return false
    if (date == day) return true
    val weekday = LocalDate.ofEpochDay(date).dayOfWeek
    return repeatMask and weekdayBit(weekday) != 0
}

internal data class PlanSchedule(val plans: List<DayPlan>, val skips: Set<PlanSkip>) {
    fun on(day: Long): List<DayPlan> = plans.asSequence()
        .filter { it.covers(day) && PlanSkip(it.id, day) !in skips }
        .sortedWith(compareBy(DayPlan::minuteOfDay, DayPlan::id))
        .map { it.copy(day = day) }
        .toList()

    fun nextReminders(now: ZonedDateTime): List<PlanReminder> {
        val candidates = plans.mapNotNull { nextReminder(it, now) }
        val earliest = candidates.minOfOrNull { it.atMillis } ?: return emptyList()
        return candidates.filter { it.atMillis == earliest }
    }

    private fun nextReminder(plan: DayPlan, now: ZonedDateTime): PlanReminder? {
        val offset = plan.reminderMinutes ?: return null
        val limit = LocalDate.of(2100, 12, 31).toEpochDay()
        var day = maxOf(plan.day, now.plusMinutes(offset.toLong()).toLocalDate().toEpochDay())
        if (plan.repeatMask == 0 && day != plan.day) return null

        while (day <= limit && (plan.endsBefore == null || day < plan.endsBefore)) {
            if (plan.covers(day) && PlanSkip(plan.id, day) !in skips) {
                val time = LocalDate.ofEpochDay(day)
                    .atTime(LocalTime.ofSecondOfDay(plan.minuteOfDay * 60L))
                    .atZone(now.zone)
                    .minusMinutes(offset.toLong())
                if (time.isAfter(now)) return PlanReminder(plan, day, time.toInstant().toEpochMilli())
            }
            if (plan.repeatMask == 0) return null
            day++
        }
        return null
    }
}

internal data class PlanReminder(val plan: DayPlan, val day: Long, val atMillis: Long)
