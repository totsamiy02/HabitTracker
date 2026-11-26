package com.yaroslav.habittracker.timer

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.habits.Habit
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "focus_sessions",
    indices = [Index("day"), Index("planId")],
    foreignKeys = [
        ForeignKey(
            entity = DayPlan::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class FocusSession(
    @PrimaryKey val id: String,
    val day: Long,
    val durationMillis: Long,
    val startedAtMillis: Long?,
    val endedAtMillis: Long,
    val planId: Long? = null,
)

@Entity(
    tableName = "session_habits",
    primaryKeys = ["sessionId", "habitId"],
    indices = [Index("habitId")],
    foreignKeys = [
        ForeignKey(
            entity = FocusSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionHabit(val sessionId: String, val habitId: Long)

@Entity(
    tableName = "active_timer",
    indices = [Index("planId")],
    foreignKeys = [
        ForeignKey(
            entity = DayPlan::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
internal data class ActiveTimer(
    @PrimaryKey val slot: Int = 1,
    val id: String,
    val limitMillis: Long?,
    val selectedHabitIds: String,
    val startedAtMillis: Long,
    val zoneId: String,
    val accumulatedMillis: Long,
    val runningSinceElapsed: Long?,
    val runningSinceWall: Long,
    val bootCount: Int,
    val planId: Long? = null,
) {
    val running: Boolean get() = runningSinceElapsed != null

    val habitIds: List<Long>
        get() = selectedHabitIds.split(',').mapNotNull(String::toLongOrNull)

    fun elapsedAt(now: TimerMoment): Long {
        val anchor = runningSinceElapsed ?: return accumulatedMillis
        val segment = if (bootCount == now.bootCount && now.elapsedMillis >= anchor) {
            now.elapsedMillis - anchor
        } else {
            (now.wallMillis - runningSinceWall).coerceAtLeast(0L)
        }
        val total = accumulatedMillis + segment
        return limitMillis?.let { total.coerceAtMost(it) } ?: total
    }

    fun remainingAt(now: TimerMoment): Long? = limitMillis?.minus(elapsedAt(now))

    fun expiryWallMillis(): Long = runningSinceWall + requireNotNull(limitMillis) - accumulatedMillis
}

internal data class TimerMoment(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int)

@Dao
internal interface TimerDao {
    @Query("SELECT * FROM active_timer WHERE slot = 1")
    fun observeActive(): Flow<ActiveTimer?>

    @Query("SELECT * FROM active_timer WHERE slot = 1")
    suspend fun active(): ActiveTimer?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActive(timer: ActiveTimer): Long

    @Update
    suspend fun updateActive(timer: ActiveTimer)

    @Query("DELETE FROM active_timer WHERE id = :id")
    suspend fun clearActive(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: FocusSession): Long

    @Insert
    suspend fun linkHabits(links: List<SessionHabit>)

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun session(id: String): FocusSession?

    @Update
    suspend fun updateSession(session: FocusSession)

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)
}
