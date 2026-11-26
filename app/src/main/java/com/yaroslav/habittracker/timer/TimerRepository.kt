package com.yaroslav.habittracker.timer

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.yaroslav.habittracker.habits.HabitDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal const val MAX_SESSION_MINUTES = 24 * 60

internal class TimerRepository(
    private val database: HabitDatabase,
    private val clock: () -> TimerMoment,
) {
    private val timer = database.timer()
    private val habits = database.habits()
    val active = timer.observeActive()

    fun now(): TimerMoment = clock()

    suspend fun start(
        limitMillis: Long?,
        habitIds: Set<Long>,
        planId: Long? = null,
    ): ActiveTimer = database.withTransaction {
        timer.active()?.let { return@withTransaction it }
        require(limitMillis == null || limitMillis in 1..MAX_SESSION_MINUTES * 60_000L)
        val selected = habitIds.filter { habits.find(it)?.targetMinutes != null }.toMutableSet()
        if (planId != null) {
            val habitId = requireNotNull(database.calendar().find(planId)?.habitId)
            selected.add(habitId)
        }
        val now = now()
        val draft = ActiveTimer(
            id = UUID.randomUUID().toString(),
            limitMillis = limitMillis,
            selectedHabitIds = selected.joinToString(","),
            startedAtMillis = now.wallMillis,
            zoneId = ZoneId.systemDefault().id,
            accumulatedMillis = 0L,
            runningSinceElapsed = now.elapsedMillis,
            runningSinceWall = now.wallMillis,
            bootCount = now.bootCount,
            planId = planId,
        )
        timer.insertActive(draft)
        draft
    }

    suspend fun togglePause(id: String, pause: Boolean) = database.withTransaction {
        val draft = timer.active()?.takeIf { it.id == id } ?: return@withTransaction
        if (draft.running != pause) return@withTransaction
        val now = now()
        if (draft.running && draft.remainingAt(now) == 0L) return@withTransaction
        timer.updateActive(draft.copy(
            accumulatedMillis = draft.elapsedAt(now),
            runningSinceElapsed = if (pause) null else now.elapsedMillis,
            runningSinceWall = now.wallMillis,
            bootCount = now.bootCount,
        ))
    }

    suspend fun finish(id: String, expiredOnly: Boolean): FocusSession? = database.withTransaction {
        val draft = timer.active()?.takeIf { it.id == id } ?: return@withTransaction null
        val now = now()
        val expired = draft.running && draft.remainingAt(now) == 0L
        if (expiredOnly && !expired) return@withTransaction null

        val elapsed = draft.elapsedAt(now)
        timer.clearActive(id)
        if (elapsed <= 0L) return@withTransaction null

        val end = if (expired) draft.expiryWallMillis() else now.wallMillis
        val session = FocusSession(
            id = draft.id,
            day = Instant.ofEpochMilli(end).atZone(ZoneId.of(draft.zoneId)).toLocalDate().toEpochDay(),
            durationMillis = elapsed,
            startedAtMillis = draft.startedAtMillis,
            endedAtMillis = end,
            planId = draft.planId,
        )
        val linked = draft.habitIds.filter { habits.find(it) != null }
        if (timer.insertSession(session) != -1L) {
            timer.linkHabits(linked.map { SessionHabit(session.id, it) })
            session
        } else {
            null
        }
    }

    suspend fun saveManual(habitId: Long, date: LocalDate, durationMillis: Long, sessionId: String?) =
        database.withTransaction {
            require(durationMillis in 1..MAX_SESSION_MINUTES * 60_000L && !date.isAfter(LocalDate.now()))
            if (sessionId != null) {
                val record = requireNotNull(habits.record(habitId))
                val current = requireNotNull(record.sessions.firstOrNull { it.id == sessionId })
                timer.updateSession(current.copy(day = date.toEpochDay(), durationMillis = durationMillis))
            } else {
                require(habits.find(habitId)?.targetMinutes != null)
                val id = UUID.randomUUID().toString()
                timer.insertSession(FocusSession(id, date.toEpochDay(), durationMillis, null, now().wallMillis))
                timer.linkHabits(listOf(SessionHabit(id, habitId)))
            }
        }

    suspend fun deleteSession(habitId: Long, id: String) = database.withTransaction {
        val record = requireNotNull(habits.record(habitId))
        require(record.sessions.any { it.id == id })
        timer.deleteSession(id)
    }
}

internal fun deviceTimerClock(context: Context): TimerMoment =
    TimerMoment(
        wallMillis = System.currentTimeMillis(),
        elapsedMillis = SystemClock.elapsedRealtime(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
    )
