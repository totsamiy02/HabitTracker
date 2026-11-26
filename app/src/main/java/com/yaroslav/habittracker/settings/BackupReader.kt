package com.yaroslav.habittracker.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import com.yaroslav.habittracker.calendar.CALENDAR_YEARS
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.calendar.PLAN_TITLE_LIMIT
import com.yaroslav.habittracker.habits.ALL_WEEKDAYS
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitCompletion
import com.yaroslav.habittracker.habits.HabitSnapshot
import com.yaroslav.habittracker.habits.INITIAL_MOSAIC_SIDE
import com.yaroslav.habittracker.planning.MAX_PLAN_MINUTES
import com.yaroslav.habittracker.planning.PlanSkip
import com.yaroslav.habittracker.planning.REMINDER_OFFSETS
import com.yaroslav.habittracker.tasks.ChecklistTask
import com.yaroslav.habittracker.tasks.TASK_TITLE_LIMIT
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.MAX_SESSION_MINUTES
import com.yaroslav.habittracker.timer.SessionHabit
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val BACKUP_FORMAT = 4
private const val MAX_BACKUP_BYTES = 8L * 1024 * 1024
private const val MAX_JSON_DEPTH = 16

internal class InvalidBackup : IOException("Invalid HabitTracker backup")
internal class UnsupportedBackup : IOException("Unsupported HabitTracker backup version")

internal data class BackupPayload(val version: Int, val snapshot: HabitSnapshot)
internal data class SelectedBackup(val name: String?, val payload: BackupPayload)

internal class BackupReader(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri): SelectedBackup = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw InvalidBackup()
        val payload = stream.use(::readBackup)
        SelectedBackup(displayName(uri), payload)
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0).take(160) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

internal fun readBackup(input: InputStream): BackupPayload {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val root = JsonReader(InputStreamReader(CappedBackupStream(input), decoder)).use { reader ->
        val document = reader.value(0).objectValue()
        verify(reader.peek() == JsonToken.END_DOCUMENT)
        document
    }
    val version = root.number("formatVersion")
    if (version !in 1L..BACKUP_FORMAT.toLong()) throw UnsupportedBackup()

    val mosaic = root.required("mosaic").objectValue()
    verify(mosaic.string("template") == "growing_square")
    verify(mosaic.number("initialSide") == INITIAL_MOSAIC_SIDE.toLong())

    val habits = root.objects("habits").map { habit ->
        Habit(
            id = habit.id("id"),
            name = habit.title("name", 1_024),
            description = habit.string("description").also { verify(it.length <= 16_384) },
            weekdays = if (version >= 2L) habit.int("weekdays", 1..ALL_WEEKDAYS) else ALL_WEEKDAYS,
            targetMinutes = if (version >= 2L) habit.optionalInt("targetMinutes", 1..MAX_SESSION_MINUTES) else null,
        )
    }
    val habitIds = unique(habits.map(Habit::id))
    val completions = root.objects("completions").map { mark ->
        HabitCompletion(mark.id("habitId").relatedTo(habitIds), mark.date("date"))
    }
    unique(completions)

    val plans = if (version >= 3L) root.objects("plans").map { plan ->
        DayPlan(
            id = plan.id("id"),
            title = plan.title("title", PLAN_TITLE_LIMIT),
            day = plan.date("date"),
            minuteOfDay = plan.int("minuteOfDay", 0..1_439),
            habitId = plan.optionalId("habitId")?.relatedTo(habitIds),
            durationMinutes = if (version >= 4L) plan.optionalInt("durationMinutes", 1..MAX_PLAN_MINUTES) else null,
            repeatMask = if (version >= 4L) plan.int("repeatMask", 0..ALL_WEEKDAYS) else 0,
            reminderMinutes = if (version >= 4L) plan.optionalInt("reminderMinutes", 0..30)?.also {
                verify(it in REMINDER_OFFSETS)
            } else null,
            endsBefore = if (version >= 4L) plan.optionalDate("endsBefore") else null,
        ).also { verify(it.endsBefore == null || (it.repeatMask != 0 && it.endsBefore >= it.day)) }
    } else emptyList()
    val planIds = unique(plans.map(DayPlan::id))
    val plansById = plans.associateBy(DayPlan::id)

    val links = mutableListOf<SessionHabit>()
    val sessions = if (version >= 2L) root.objects("sessions").map { session ->
        val id = session.title("id", 128)
        val duration = session.number("durationMillis")
        verify(duration in 1..Long.MAX_VALUE / MAX_BACKUP_BYTES)
        val ids = session.items("habitIds").map { it.asId().relatedTo(habitIds) }
        unique(ids)
        links += ids.map { SessionHabit(id, it) }
        FocusSession(
            id,
            session.date("date"),
            duration,
            session.optionalNumber("startedAtMillis"),
            session.number("endedAtMillis"),
            if (version >= 3L) session.optionalId("planId")?.relatedTo(planIds) else null,
        )
    } else emptyList()
    unique(sessions.map(FocusSession::id))

    val skips = if (version >= 4L) root.objects("skips").map { skip ->
        val id = skip.id("planId").relatedTo(planIds)
        val day = skip.date("date")
        val plan = plansById.getValue(id)
        verify(plan.repeatMask != 0 && day >= plan.day)
        PlanSkip(id, day)
    } else emptyList()
    unique(skips)

    val tasks = if (version >= 4L) root.objects("tasks").map { task ->
        ChecklistTask(
            task.id("id"), task.title("title", TASK_TITLE_LIMIT), task.optionalDate("deadline"),
            task.required("done") as? Boolean ?: throw InvalidBackup(),
        )
    } else emptyList()
    unique(tasks.map(ChecklistTask::id))

    return BackupPayload(version.toInt(), HabitSnapshot(habits, completions, sessions, links, plans, skips, tasks))
}

private class CappedBackupStream(input: InputStream) : FilterInputStream(input) {
    private var readBytes = 0L

    override fun read(): Int = super.read().also { if (it != -1) count(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) count(it) }

    private fun count(bytes: Int) {
        readBytes += bytes
        verify(readBytes <= MAX_BACKUP_BYTES)
    }
}

private data class JsonNumber(val value: String)

private fun JsonReader.value(depth: Int): Any? {
    verify(depth <= MAX_JSON_DEPTH)
    return when (peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val values = mutableMapOf<String, Any?>()
            beginObject()
            while (hasNext()) {
                val name = nextName()
                verify(!values.containsKey(name))
                values[name] = value(depth + 1)
            }
            endObject()
            values
        }
        JsonToken.BEGIN_ARRAY -> {
            val values = mutableListOf<Any?>()
            beginArray()
            while (hasNext()) values.add(value(depth + 1))
            endArray()
            values
        }
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> JsonNumber(nextString())
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); null }
        else -> throw InvalidBackup()
    }
}

private fun verify(valid: Boolean) {
    if (!valid) throw InvalidBackup()
}

private fun Any?.objectValue(): Map<*, *> = this as? Map<*, *> ?: throw InvalidBackup()
private fun Any?.asNumber(): Long = (this as? JsonNumber)?.value?.toLongOrNull() ?: throw InvalidBackup()
private fun Any?.asId(): Long = asNumber().also { verify(it in 1 until Long.MAX_VALUE) }
private fun Any?.asDate(): Long {
    val date = LocalDate.parse(this as? String ?: throw InvalidBackup())
    verify(date.year in CALENDAR_YEARS)
    return date.toEpochDay()
}
private fun Map<*, *>.required(key: String): Any? {
    verify(containsKey(key))
    return this[key]
}
private fun Map<*, *>.string(key: String): String = required(key) as? String ?: throw InvalidBackup()
private fun Map<*, *>.title(key: String, max: Int): String = string(key).also { verify(it.isNotBlank() && it.length <= max) }
private fun Map<*, *>.number(key: String): Long = required(key).asNumber()
private fun Map<*, *>.optionalNumber(key: String): Long? = this[key]?.asNumber()
private fun Map<*, *>.int(key: String, range: IntRange): Int = number(key).checkedInt(range)
private fun Map<*, *>.optionalInt(key: String, range: IntRange): Int? = optionalNumber(key)?.checkedInt(range)
private fun Long.checkedInt(range: IntRange): Int {
    verify(this in range.first.toLong()..range.last.toLong())
    return toInt()
}
private fun Map<*, *>.id(key: String): Long = required(key).asId()
private fun Map<*, *>.optionalId(key: String): Long? = this[key]?.asId()
private fun Map<*, *>.date(key: String): Long = required(key).asDate()
private fun Map<*, *>.optionalDate(key: String): Long? = this[key]?.asDate()
private fun Map<*, *>.items(key: String): List<*> = required(key) as? List<*> ?: throw InvalidBackup()
private fun Map<*, *>.objects(key: String): List<Map<*, *>> = items(key).map { it.objectValue() }
private fun Long.relatedTo(values: Set<Long>): Long = also { verify(it in values) }
private fun <T> unique(values: List<T>): Set<T> = values.toSet().also { verify(it.size == values.size) }
