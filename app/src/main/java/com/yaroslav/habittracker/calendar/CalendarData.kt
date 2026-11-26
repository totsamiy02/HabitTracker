package com.yaroslav.habittracker.calendar

import androidx.room.ColumnInfo
import androidx.room.Dao
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
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.withTransaction
import com.yaroslav.habittracker.habits.ALL_WEEKDAYS
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitDatabase
import com.yaroslav.habittracker.habits.HabitProgress
import com.yaroslav.habittracker.planning.DayTotals
import com.yaroslav.habittracker.planning.MAX_PLAN_MINUTES
import com.yaroslav.habittracker.planning.PlanEditScope
import com.yaroslav.habittracker.planning.PlanSchedule
import com.yaroslav.habittracker.planning.PlanSkip
import com.yaroslav.habittracker.planning.REMINDER_OFFSETS
import com.yaroslav.habittracker.planning.covers
import com.yaroslav.habittracker.planning.weekTotals
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.SessionHabit
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal const val PLAN_TITLE_LIMIT = 70
internal val CALENDAR_YEARS = 1900..2100

@Entity(
    tableName = "day_plans",
    indices = [Index(value = ["day", "minuteOfDay"]), Index("habitId")],
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class DayPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val day: Long,
    val minuteOfDay: Int,
    val habitId: Long? = null,
    val durationMinutes: Int? = null,
    @ColumnInfo(defaultValue = "0") val repeatMask: Int = 0,
    val reminderMinutes: Int? = null,
    val endsBefore: Long? = null,
)

internal data class CalendarSession(
    @Embedded val session: FocusSession,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(SessionHabit::class, parentColumn = "sessionId", entityColumn = "habitId"),
    )
    val habits: List<Habit>,
)

internal data class DayPlanProgress(val plan: DayPlan, val recordedMillis: Long)

internal data class CalendarDay(
    val date: LocalDate,
    val daysWithEntries: Set<Long>,
    val plans: List<DayPlan>,
    val sessions: List<CalendarSession>,
    val marks: List<Habit>,
    val progress: List<HabitProgress>,
    val habits: List<Habit>,
)

@Dao
internal interface CalendarDao {
    @Query("SELECT * FROM day_plans WHERE id = :id")
    suspend fun find(id: Long): DayPlan?

    @Query("SELECT * FROM day_plans WHERE id = :id")
    fun observePlan(id: Long): Flow<DayPlan?>

    @Query(
        """
        SELECT * FROM day_plans
        WHERE day <= :last AND (repeatMask != 0 OR day >= :first)
        AND (endsBefore IS NULL OR endsBefore > :first)
        """,
    )
    suspend fun plansInRange(first: Long, last: Long): List<DayPlan>

    @Query("SELECT * FROM day_plans WHERE reminderMinutes IS NOT NULL")
    suspend fun reminderPlans(): List<DayPlan>

    @Query("SELECT * FROM plan_skips WHERE day BETWEEN :first AND :last")
    suspend fun skipsInRange(first: Long, last: Long): List<PlanSkip>

    @Query("SELECT * FROM plan_skips WHERE planId = :id AND day >= :first")
    suspend fun futureSkips(id: Long, first: Long): List<PlanSkip>

    @Query("SELECT EXISTS(SELECT 1 FROM plan_skips WHERE planId = :id AND day = :day)")
    suspend fun isSkipped(id: Long, day: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun skipOccurrences(skips: List<PlanSkip>)

    @Query("SELECT * FROM focus_sessions WHERE day BETWEEN :first AND :last")
    suspend fun actualSessions(first: Long, last: Long): List<FocusSession>

    @Transaction
    @Query(
        """
        SELECT * FROM focus_sessions WHERE day = :day
        ORDER BY startedAtMillis IS NULL, startedAtMillis, endedAtMillis, id
        """,
    )
    suspend fun sessionsOn(day: Long): List<CalendarSession>

    @Query(
        """
        SELECT habits.* FROM habit_completions
        JOIN habits ON habits.id = habit_completions.habitId
        WHERE habit_completions.day = :day
        ORDER BY habits.id
        """,
    )
    suspend fun marksOn(day: Long): List<Habit>

    @Query(
        """
        SELECT day FROM focus_sessions WHERE day BETWEEN :first AND :last
        UNION SELECT day FROM habit_completions WHERE day BETWEEN :first AND :last
        """,
    )
    suspend fun entryDates(first: Long, last: Long): List<Long>

    @Insert
    suspend fun insert(plan: DayPlan): Long

    @Update
    suspend fun update(plan: DayPlan): Int
}

internal class CalendarRepository(private val database: HabitDatabase) {
    private val calendar = database.calendar()
    private val habits = database.habits()

    fun observeDay(date: LocalDate): Flow<CalendarDay> =
        database.invalidationTracker.createFlow(
            "day_plans", "plan_skips", "focus_sessions", "session_habits", "habit_completions", "habits",
        ).map { readDay(date) }

    suspend fun readDay(date: LocalDate): CalendarDay = database.withTransaction {
        val day = date.toEpochDay()
        val month = YearMonth.from(date)
        val first = month.atDay(1).toEpochDay()
        val last = month.atEndOfMonth().toEpochDay()
        val schedule = scheduleInRange(first, last)
        val plans = schedule.on(day)
        val sessions = calendar.sessionsOn(day)
        val marks = calendar.marksOn(day)
        val habitIds = buildSet {
            plans.mapNotNullTo(this) { it.habitId }
            for (session in sessions) session.habits.mapTo(this) { it.id }
            marks.mapTo(this) { it.id }
        }
        val daysWithEntries = calendar.entryDates(first, last).toMutableSet()
        for (candidate in first..last) {
            if (schedule.on(candidate).isNotEmpty()) daysWithEntries.add(candidate)
        }
        CalendarDay(
            date = date,
            daysWithEntries = daysWithEntries,
            plans = plans,
            sessions = sessions,
            marks = marks,
            progress = if (habitIds.isEmpty()) emptyList() else habits.records(habitIds).map { it.progress(day) },
            habits = habits.allHabits(),
        )
    }

    fun observePlanProgress(date: LocalDate): Flow<List<DayPlanProgress>> =
        database.invalidationTracker.createFlow("day_plans", "plan_skips", "focus_sessions")
            .map {
                database.withTransaction {
                    val day = date.toEpochDay()
                    val recordedMillis = calendar.actualSessions(day, day)
                        .filter { it.planId != null }
                        .groupingBy { it.planId }
                        .fold(0L) { total, session -> total + session.durationMillis }
                    scheduleInRange(day, day).on(day).map { plan ->
                        DayPlanProgress(plan, recordedMillis[plan.id] ?: 0L)
                    }
                }
            }

    fun observeWeek(monday: LocalDate): Flow<List<DayTotals>> =
        database.invalidationTracker.createFlow("day_plans", "plan_skips", "focus_sessions")
            .map {
                database.withTransaction {
                    val first = monday.toEpochDay()
                    val last = monday.plusDays(6).toEpochDay()
                    weekTotals(monday, scheduleInRange(first, last), calendar.actualSessions(first, last))
                }
            }

    private suspend fun scheduleInRange(first: Long, last: Long): PlanSchedule =
        PlanSchedule(calendar.plansInRange(first, last), calendar.skipsInRange(first, last).toSet())

    suspend fun save(
        plan: DayPlan,
        occurrenceDay: Long? = null,
        scope: PlanEditScope = PlanEditScope.Following,
    ): Long = database.withTransaction {
        val title = plan.title.trim()
        require(title.length in 1..PLAN_TITLE_LIMIT)
        require(plan.minuteOfDay in 0 until 24 * 60)
        require(LocalDate.ofEpochDay(plan.day).year in CALENDAR_YEARS)
        require(plan.repeatMask in 0..ALL_WEEKDAYS)
        require(plan.durationMinutes == null || plan.durationMinutes in 1..MAX_PLAN_MINUTES)
        require(plan.reminderMinutes == null || plan.reminderMinutes in REMINDER_OFFSETS)
        val updated = plan.copy(title = title, endsBefore = null)
        if (updated.id == 0L) return@withTransaction calendar.insert(updated)

        val original = requireNotNull(calendar.find(updated.id))
        if (original.repeatMask == 0) {
            check(calendar.update(updated) == 1)
            return@withTransaction updated.id
        }

        val occurrence = requireNotNull(occurrenceDay)
        require(original.covers(occurrence) && !calendar.isSkipped(original.id, occurrence))
        if (scope == PlanEditScope.Single) {
            calendar.skipOccurrences(listOf(PlanSkip(original.id, occurrence)))
            return@withTransaction calendar.insert(updated.copy(id = 0, repeatMask = 0))
        }

        require(updated.day >= occurrence)
        require(original.endsBefore == null || updated.day < original.endsBefore)
        check(calendar.update(original.copy(endsBefore = occurrence)) == 1)
        val cutoff = original.endsBefore.takeIf { updated.repeatMask != 0 }
        val next = updated.copy(id = 0, endsBefore = cutoff)
        val nextId = calendar.insert(next)
        if (next.repeatMask != 0) {
            val futureSkips = calendar.futureSkips(original.id, occurrence)
                .filter { next.covers(it.day) }
                .map { it.copy(planId = nextId) }
            calendar.skipOccurrences(futureSkips)
        }
        nextId
    }
}
