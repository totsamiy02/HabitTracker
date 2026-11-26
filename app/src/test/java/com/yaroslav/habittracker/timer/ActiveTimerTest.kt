package com.yaroslav.habittracker.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveTimerTest {
    private val timer = ActiveTimer(
        id = "timer",
        limitMillis = 60_000,
        selectedHabitIds = "1,2",
        startedAtMillis = 100_000,
        zoneId = "UTC",
        accumulatedMillis = 10_000,
        runningSinceElapsed = 10_000,
        runningSinceWall = 100_000,
        bootCount = 1,
    )

    @Test
    fun usesElapsedTimeThroughSleepAndClockChanges() {
        val shiftedClock = TimerMoment(wallMillis = 20_000, elapsedMillis = 40_000, bootCount = 1)
        assertEquals(40_000L, timer.elapsedAt(shiftedClock))
        assertEquals(20_000L, timer.remainingAt(shiftedClock))
    }

    @Test
    fun countdownCapsAtTargetAfterRebootAndPauseDoesNotAdvance() {
        val later = TimerMoment(wallMillis = 170_000, elapsedMillis = 1_000, bootCount = 2)
        assertEquals(0L, timer.remainingAt(later))
        assertEquals(150_000L, timer.expiryWallMillis())
        val paused = timer.copy(runningSinceElapsed = null)
        assertEquals(10_000L, paused.elapsedAt(later))
    }

    @Test
    fun formatsHoursAndRoundsRemainingTimeUp() {
        assertEquals("00:01", clockText(250, countdown = true))
        assertEquals("1:01:01", clockText(3_661_000))
    }
}
