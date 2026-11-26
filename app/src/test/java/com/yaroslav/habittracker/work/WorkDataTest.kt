package com.yaroslav.habittracker.work

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkDataTest {
    private val lastDay = LocalDate.of(2026, 12, 31)
    private val night = ShiftTemplate(
        title = "Ночная", night = true, hours = ShiftHours(22 * 60, 6 * 60, 30), hourlyKopecks = 40_000,
    ).shift(lastDay.toEpochDay())

    @Test
    fun overnightContinuesOnNextDateButIsCountedOnlyOnItsStartDate() {
        val worked = night.copy(actual = night.planned)
        val shifts = listOf(worked)
        val nextDay = lastDay.plusDays(1)

        assertEquals(450, worked.hours.paidMinutes)
        assertEquals(listOf(worked), shifts.continuingOn(nextDay.toEpochDay()))
        assertTrue(shifts.startingOn(nextDay.toEpochDay()).isEmpty())
        assertEquals(ShiftTotals(1, 450, 300_000, 0), workTotals(shifts, WorkPeriod.year(2026)).worked)
        assertEquals(0, workTotals(shifts, WorkPeriod.year(2027)).worked.count)
        assertTrue(workGroups(shifts, WorkPeriod.month(YearMonth.from(nextDay)), byMonth = false).isEmpty())
    }

    @Test
    fun customPeriodIsInclusiveAndConfirmedHoursReplacePlannedEstimate() {
        val first = lastDay.minusDays(2)
        val period = WorkPeriod(first, lastDay)
        val worked = night.copy(actual = ShiftHours(22 * 60, 5 * 60, 60))
        val planned = night.copy(day = first.toEpochDay())
        val outside = night.copy(day = lastDay.plusDays(1).toEpochDay(), actual = night.planned)
        val shifts = listOf(planned, worked, outside)
        val totals = workTotals(shifts, period)

        assertEquals(ShiftTotals(1, 360, 240_000, 0), totals.worked)
        assertEquals(ShiftTotals(1, 450, 300_000, 0), totals.planned)
        val group = workGroups(shifts, period, byMonth = false).single()
        assertEquals(listOf(worked), group.shifts)
        assertEquals(period, group.period)
    }

    @Test
    fun rateCanBeMissingOrZeroAndEarningsRoundToKopeck() {
        val duration = ShiftHours(60, 61, 0)
        val missing = night.copy(planned = duration, hourlyKopecks = null)
        val zero = missing.copy(hourlyKopecks = 0)
        val rounded = missing.copy(hourlyKopecks = 30)

        assertNull(missing.earnings())
        assertEquals(0L, zero.earnings())
        assertEquals(1L, rounded.earnings())
        assertEquals(ShiftTotals(3, 3, 1, 1), shiftTotals(listOf(missing, zero, rounded)))
        assertEquals(40_025L, parseHourlyRate("400,25"))
        assertEquals(40_020L, parseHourlyRate("400.2"))
        assertNull(parseHourlyRate("400,251"))
        assertNull(parseHourlyRate("1000000.01"))
    }

    @Test
    fun existingShiftKeepsTheValuesFromItsTemplateAtCreation() {
        val template = ShiftTemplate(
            title = "Дневная", night = false, hours = ShiftHours(540, 1_080, 30), hourlyKopecks = 30_000,
        )
        val created = template.shift(lastDay.toEpochDay())
        val edited = template.copy(title = "Длинная", hours = ShiftHours(480, 1_200, 60), hourlyKopecks = 45_000)

        assertEquals("Дневная", created.title)
        assertEquals(510, created.hours.paidMinutes)
        assertEquals(30_000L, created.hourlyKopecks)
        assertEquals("Длинная", edited.shift(lastDay.toEpochDay()).title)
        assertFalse(ShiftHours(60, 60, 0).isValid())
        assertFalse(ShiftHours(60, 90, 30).isValid())
    }
}
