package com.yaroslav.habittracker.work

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.yaroslav.habittracker.calendar.CALENDAR_YEARS
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

internal const val SHIFT_TITLE_LIMIT = 70
internal const val MAX_HOURLY_KOPECKS = 100_000_000L
private const val MINUTES_PER_DAY = 1_440

data class ShiftHours(val startMinute: Int, val endMinute: Int, val breakMinutes: Int) {
    val crossesMidnight: Boolean get() = endMinute < startMinute
    val duration: Int get() = endMinute - startMinute + if (crossesMidnight) MINUTES_PER_DAY else 0
    val paidMinutes: Int get() = duration - breakMinutes

    internal fun isValid(): Boolean =
        startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY &&
            startMinute != endMinute && breakMinutes in 0 until duration
}

@Entity(tableName = "work_shifts", indices = [Index("day")])
data class WorkShift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val title: String,
    val night: Boolean,
    @Embedded val planned: ShiftHours,
    val hourlyKopecks: Long? = null,
    @Embedded(prefix = "actual_") val actual: ShiftHours? = null,
) {
    val hours: ShiftHours get() = actual ?: planned
    internal fun isValid(): Boolean = day in WORK_FIRST_DATE.toEpochDay()..WORK_LAST_DATE.toEpochDay() &&
        validShiftDetails(title, planned, hourlyKopecks) && (actual == null || actual.isValid())
}

@Entity(tableName = "shift_templates")
data class ShiftTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val night: Boolean,
    @Embedded val hours: ShiftHours,
    val hourlyKopecks: Long? = null,
) {
    internal fun isValid(): Boolean = validShiftDetails(title, hours, hourlyKopecks)

    internal fun shift(day: Long) = WorkShift(day = day, title = title, night = night, planned = hours,
        hourlyKopecks = hourlyKopecks)
}

private fun validShiftDetails(title: String, hours: ShiftHours, hourlyKopecks: Long?) =
    title.isNotBlank() && title.length <= SHIFT_TITLE_LIMIT && hours.isValid() &&
        (hourlyKopecks == null || hourlyKopecks in 0L..MAX_HOURLY_KOPECKS)

internal val WORK_FIRST_DATE: LocalDate = LocalDate.of(CALENDAR_YEARS.first, 1, 1)
internal val WORK_LAST_DATE: LocalDate = LocalDate.of(CALENDAR_YEARS.last, 12, 31)

internal data class WorkPeriod(val from: LocalDate, val through: LocalDate) {
    init {
        require(!through.isBefore(from) && from >= WORK_FIRST_DATE && through <= WORK_LAST_DATE)
    }

    companion object {
        fun month(month: YearMonth) = WorkPeriod(month.atDay(1), month.atEndOfMonth())
        fun year(year: Int) = WorkPeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
    }
}

internal data class ShiftTotals(val count: Int, val minutes: Long, val kopecks: Long, val missingRates: Int)
internal data class WorkTotals(val worked: ShiftTotals, val planned: ShiftTotals)
internal data class WorkGroup(val key: LocalDate, val period: WorkPeriod, val shifts: List<WorkShift>, val totals: ShiftTotals)

internal fun WorkShift.earnings(): Long? = hourlyKopecks?.let { (it * hours.paidMinutes + 30L) / 60L }

internal fun shiftTotals(shifts: List<WorkShift>) = ShiftTotals(
    count = shifts.size,
    minutes = shifts.sumOf { it.hours.paidMinutes.toLong() },
    kopecks = shifts.sumOf { it.earnings() ?: 0L },
    missingRates = shifts.count { it.hourlyKopecks == null },
)

internal fun workTotals(shifts: List<WorkShift>, period: WorkPeriod): WorkTotals {
    val (worked, planned) = shifts.filter { it.day in period.from.toEpochDay()..period.through.toEpochDay() }
        .partition { it.actual != null }
    return WorkTotals(shiftTotals(worked), shiftTotals(planned))
}

internal fun workGroups(shifts: List<WorkShift>, period: WorkPeriod, byMonth: Boolean): List<WorkGroup> =
    shifts.filter { it.actual != null && it.day in period.from.toEpochDay()..period.through.toEpochDay() }
        .groupBy {
            val start = LocalDate.ofEpochDay(it.day)
            if (byMonth) start.withDayOfMonth(1)
            else start.minusDays((start.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        }
        .toSortedMap()
        .map { (key, entries) ->
            val end = if (byMonth) YearMonth.from(key).atEndOfMonth() else key.plusDays(6)
            WorkGroup(key, WorkPeriod(maxOf(key, period.from), minOf(end, period.through)), entries, shiftTotals(entries))
        }

internal fun List<WorkShift>.startingOn(day: Long) = filter { it.day == day }
internal fun List<WorkShift>.continuingOn(day: Long) = filter { it.day == day - 1 && it.hours.crossesMidnight }

@Dao
internal interface WorkDao {
    @Query("SELECT COUNT(*) FROM work_shifts")
    fun observeShiftCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM shift_templates")
    fun observeTemplateCount(): Flow<Int>

    @Query("SELECT * FROM work_shifts WHERE day BETWEEN :first AND :last ORDER BY day, startMinute, id")
    fun observeShifts(first: Long, last: Long): Flow<List<WorkShift>>

    @Query("SELECT * FROM shift_templates ORDER BY title COLLATE NOCASE, id")
    fun observeTemplates(): Flow<List<ShiftTemplate>>

    @Insert
    suspend fun insertShift(shift: WorkShift): Long

    @Update
    suspend fun updateShift(shift: WorkShift): Int

    @Delete
    suspend fun deleteShift(shift: WorkShift)

    @Insert
    suspend fun insertTemplate(template: ShiftTemplate): Long

    @Update
    suspend fun updateTemplate(template: ShiftTemplate): Int

    @Delete
    suspend fun deleteTemplate(template: ShiftTemplate)
}
