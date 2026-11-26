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
import com.yaroslav.habittracker.work.ShiftTemplate
import com.yaroslav.habittracker.work.WorkShift

internal data class BackupCounts(
    val habits: Int,
    val marks: Int,
    val sessions: Int,
    val plans: Int,
    val tasks: Int,
    val shifts: Int = 0,
    val templates: Int = 0,
) {
    val hasRecords: Boolean get() = habits + marks + sessions + plans + tasks + shifts + templates > 0
}

internal fun HabitSnapshot.counts() =
    BackupCounts(habits.size, completions.size, sessions.size, plans.size, tasks.size, shifts.size, templates.size)

@Dao
internal interface RestoreDao {
    @Query(
        """
        SELECT
            (SELECT count(*) FROM habits) AS habits,
            (SELECT count(*) FROM habit_completions) AS marks,
            (SELECT count(*) FROM focus_sessions) AS sessions,
            (SELECT count(*) FROM day_plans) AS plans,
            (SELECT count(*) FROM tasks) AS tasks,
            (SELECT count(*) FROM work_shifts) AS shifts,
            (SELECT count(*) FROM shift_templates) AS templates
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

    @Query("DELETE FROM work_shifts")
    suspend fun clearShifts()

    @Query("DELETE FROM shift_templates")
    suspend fun clearTemplates()

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

    @Insert
    suspend fun insertShifts(items: List<WorkShift>)

    @Insert
    suspend fun insertTemplates(items: List<ShiftTemplate>)
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
        dao.clearShifts()
        dao.clearTemplates()
        dao.insertHabits(snapshot.habits)
        dao.insertMarks(snapshot.completions)
        dao.insertPlans(snapshot.plans)
        dao.insertSkips(snapshot.skips)
        dao.insertSessions(snapshot.sessions)
        dao.insertLinks(snapshot.links)
        dao.insertTasks(snapshot.tasks)
        dao.insertShifts(snapshot.shifts)
        dao.insertTemplates(snapshot.templates)
        RestoreResult.Applied
    }
}
