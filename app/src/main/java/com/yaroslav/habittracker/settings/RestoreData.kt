package com.yaroslav.habittracker.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitCompletion
import com.yaroslav.habittracker.habits.HabitDatabase
import com.yaroslav.habittracker.habits.HabitSnapshot
import com.yaroslav.habittracker.planning.PlanSkip
import com.yaroslav.habittracker.tasks.ChecklistTask
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.SessionHabit

internal data class BackupCounts(
    val habits: Int,
    val marks: Int,
    val sessions: Int,
    val plans: Int,
    val tasks: Int,
) {
    val hasRecords: Boolean get() = habits + marks + sessions + plans + tasks > 0
}

internal fun HabitSnapshot.counts() =
    BackupCounts(habits.size, completions.size, sessions.size, plans.size, tasks.size)

@Dao
internal interface RestoreDao {
    @Query(
        """
        SELECT
            (SELECT count(*) FROM habits) AS habits,
            (SELECT count(*) FROM habit_completions) AS marks,
            (SELECT count(*) FROM focus_sessions) AS sessions,
            (SELECT count(*) FROM day_plans) AS plans,
            (SELECT count(*) FROM tasks) AS tasks
        """,
    )
    suspend fun counts(): BackupCounts

    @Query("DELETE FROM focus_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM day_plans")
    suspend fun clearPlans()

    @Query("DELETE FROM habits")
    suspend fun clearHabits()

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()

    @Insert
    suspend fun insertHabits(items: List<Habit>)

    @Insert
    suspend fun insertMarks(items: List<HabitCompletion>)

    @Insert
    suspend fun insertPlans(items: List<DayPlan>)

    @Insert
    suspend fun insertSkips(items: List<PlanSkip>)

    @Insert
    suspend fun insertSessions(items: List<FocusSession>)

    @Insert
    suspend fun insertLinks(items: List<SessionHabit>)

    @Insert
    suspend fun insertTasks(items: List<ChecklistTask>)
}

internal sealed interface RestoreResult {
    data object Applied : RestoreResult
    data object RunningTimer : RestoreResult
    data class Changed(val counts: BackupCounts) : RestoreResult
}

internal class RestoreRepository(private val database: HabitDatabase) {
    private val dao = database.restore()

    suspend fun counts() = dao.counts()

    suspend fun replace(snapshot: HabitSnapshot, expected: BackupCounts): RestoreResult = database.withTransaction {
        if (database.timer().active() != null) return@withTransaction RestoreResult.RunningTimer
        val current = dao.counts()
        if (current != expected) return@withTransaction RestoreResult.Changed(current)

        dao.clearSessions()
        dao.clearPlans()
        dao.clearHabits()
        dao.clearTasks()
        dao.insertHabits(snapshot.habits)
        dao.insertMarks(snapshot.completions)
        dao.insertPlans(snapshot.plans)
        dao.insertSkips(snapshot.skips)
        dao.insertSessions(snapshot.sessions)
        dao.insertLinks(snapshot.links)
        dao.insertTasks(snapshot.tasks)
        RestoreResult.Applied
    }
}
