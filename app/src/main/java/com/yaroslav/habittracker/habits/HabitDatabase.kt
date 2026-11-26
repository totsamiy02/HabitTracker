package com.yaroslav.habittracker.habits

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.yaroslav.habittracker.calendar.CalendarDao
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.planning.PlanSkip
import com.yaroslav.habittracker.settings.RestoreDao
import com.yaroslav.habittracker.tasks.ChecklistTask
import com.yaroslav.habittracker.tasks.TaskDao
import com.yaroslav.habittracker.timer.ActiveTimer
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.SessionHabit
import com.yaroslav.habittracker.timer.TimerDao
import com.yaroslav.habittracker.work.ShiftTemplate
import com.yaroslav.habittracker.work.WorkDao
import com.yaroslav.habittracker.work.WorkShift
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val MILLIS_PER_MINUTE = 60_000L
internal const val ALL_WEEKDAYS = 127

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    @ColumnInfo(defaultValue = "127") val weekdays: Int = ALL_WEEKDAYS,
    val targetMinutes: Int? = null,
) {
    fun isScheduled(day: DayOfWeek): Boolean = weekdays and weekdayBit(day) != 0
}

internal fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

@Entity(
    tableName = "habit_completions",
    primaryKeys = ["habitId", "day"],
    indices = [Index("day")],
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HabitCompletion(val habitId: Long, val day: Long)

data class HabitRecord(
    @Embedded val habit: Habit,
    @Relation(parentColumn = "id", entityColumn = "habitId")
    val marks: List<HabitCompletion>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(SessionHabit::class, parentColumn = "habitId", entityColumn = "sessionId"),
    )
    val sessions: List<FocusSession>,
) {
    fun progress(day: Long): HabitProgress {
        val minutes = sessions.groupingBy { it.day }.fold(0L) { sum, session ->
            sum + session.durationMillis
        }
        val completedDates = marks.mapTo(mutableSetOf()) { it.day }
        habit.targetMinutes?.let { goal ->
            for ((date, duration) in minutes) {
                if (duration >= goal * MILLIS_PER_MINUTE) completedDates.add(date)
            }
        }
        return HabitProgress(
            habit = habit,
            completedDays = completedDates.size,
            completedToday = day in completedDates,
            todayMillis = minutes[day] ?: 0L,
            marks = marks.map { it.day }.sortedDescending(),
            sessions = sessions.sortedWith(compareByDescending<FocusSession> { it.day }
                .thenByDescending { it.endedAtMillis }),
        )
    }
}

data class HabitProgress(
    val habit: Habit,
    val completedDays: Int,
    val completedToday: Boolean,
    val todayMillis: Long = 0,
    val marks: List<Long> = emptyList(),
    val sessions: List<FocusSession> = emptyList(),
)

data class HabitSnapshot(
    val habits: List<Habit>,
    val completions: List<HabitCompletion>,
    val sessions: List<FocusSession>,
    val links: List<SessionHabit>,
    val plans: List<DayPlan>,
    val skips: List<PlanSkip>,
    val tasks: List<ChecklistTask>,
    val shifts: List<WorkShift> = emptyList(),
    val templates: List<ShiftTemplate> = emptyList(),
)

@Dao
interface HabitDao {
    @Transaction
    @Query("SELECT * FROM habits ORDER BY id DESC")
    fun observeRecords(): Flow<List<HabitRecord>>

    @Transaction
    @Query("SELECT * FROM habits WHERE id IN (:ids) ORDER BY id DESC")
    suspend fun records(ids: Set<Long>): List<HabitRecord>

    fun observeProgress(day: Long): Flow<List<HabitProgress>> =
        observeRecords().map { records -> records.map { it.progress(day) } }

    @Transaction
    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun record(id: Long): HabitRecord?

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun find(id: Long): Habit?

    @Insert
    suspend fun insert(habit: Habit): Long

    @Update
    suspend fun update(habit: Habit)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(completion: HabitCompletion): Long

    @Delete
    suspend fun removeMark(completion: HabitCompletion)

    @Query("SELECT EXISTS(SELECT 1 FROM habit_completions WHERE habitId = :habitId AND day = :day)")
    suspend fun hasMark(habitId: Long, day: Long): Boolean

    @Transaction
    suspend fun moveMark(habitId: Long, from: Long, to: Long): Boolean {
        if (!hasMark(habitId, from)) return false
        if (from == to) return true
        if (mark(HabitCompletion(habitId, to)) == -1L) return false
        removeMark(HabitCompletion(habitId, from))
        return true
    }

    @Transaction
    suspend fun markSeveral(habitIds: Set<Long>, day: Long) {
        for (id in habitIds) {
            val habit = find(id) ?: continue
            if (habit.targetMinutes == null) mark(HabitCompletion(id, day))
        }
    }

    @Query("SELECT * FROM habits ORDER BY id")
    suspend fun allHabits(): List<Habit>

    @Query("SELECT * FROM habit_completions ORDER BY habitId, day")
    suspend fun allCompletions(): List<HabitCompletion>

    @Query("SELECT COUNT(*) FROM focus_sessions")
    fun observeSessionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM day_plans")
    fun observePlanCount(): Flow<Int>

    @Query("SELECT * FROM focus_sessions ORDER BY day, endedAtMillis, id")
    suspend fun allSessions(): List<FocusSession>

    @Query("SELECT * FROM session_habits ORDER BY sessionId, habitId")
    suspend fun allLinks(): List<SessionHabit>

    @Query("SELECT * FROM day_plans ORDER BY day, minuteOfDay, id")
    suspend fun allPlans(): List<DayPlan>

    @Query("SELECT * FROM plan_skips ORDER BY planId, day")
    suspend fun allSkips(): List<PlanSkip>

    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun allTasks(): List<ChecklistTask>

    @Query("SELECT * FROM work_shifts ORDER BY day, startMinute, id")
    suspend fun allShifts(): List<WorkShift>

    @Query("SELECT * FROM shift_templates ORDER BY id")
    suspend fun allTemplates(): List<ShiftTemplate>

    @Transaction
    suspend fun snapshot() = HabitSnapshot(
        allHabits(), allCompletions(), allSessions(), allLinks(), allPlans(), allSkips(), allTasks(), allShifts(), allTemplates(),
    )
}

@Database(
    entities = [
        Habit::class, HabitCompletion::class, FocusSession::class, SessionHabit::class,
        ActiveTimer::class, DayPlan::class, PlanSkip::class, ChecklistTask::class,
        WorkShift::class, ShiftTemplate::class,
    ],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
    ],
)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habits(): HabitDao
    internal abstract fun timer(): TimerDao
    internal abstract fun calendar(): CalendarDao
    internal abstract fun tasks(): TaskDao
    internal abstract fun work(): WorkDao
    internal abstract fun restore(): RestoreDao
}
