package com.yaroslav.habittracker.settings

import android.content.ContentResolver
import android.net.Uri
import com.yaroslav.habittracker.habits.HabitDao
import com.yaroslav.habittracker.habits.HabitSnapshot
import com.yaroslav.habittracker.habits.INITIAL_MOSAIC_SIDE
import com.yaroslav.habittracker.work.ShiftHours
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class BackupExporter(private val dao: HabitDao, private val resolver: ContentResolver) {
    suspend fun writeTo(destination: Uri) = withContext(Dispatchers.IO) {
        val backup = dao.snapshot().toBackupDocument()
        val output = resolver.openOutputStream(destination, "wt")
            ?: throw IOException("Cannot open backup document")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(backup) }
    }
}

internal fun HabitSnapshot.toBackupDocument(): String {
    val habitEntries = JSONArray()
    for (habit in habits) {
        habitEntries.put(JSONObject().apply {
            put("id", habit.id)
            put("name", habit.name)
            put("description", habit.description)
            put("weekdays", habit.weekdays)
            put("targetMinutes", habit.targetMinutes ?: JSONObject.NULL)
        })
    }

    val completionEntries = JSONArray()
    for (completion in completions) {
        completionEntries.put(JSONObject().apply {
            put("habitId", completion.habitId)
            put("date", LocalDate.ofEpochDay(completion.day).toString())
        })
    }

    val linksBySession = links.groupBy { it.sessionId }
    val sessionEntries = JSONArray()
    for (session in sessions) {
        sessionEntries.put(JSONObject().apply {
            put("id", session.id)
            put("date", LocalDate.ofEpochDay(session.day).toString())
            put("durationMillis", session.durationMillis)
            put("startedAtMillis", session.startedAtMillis ?: JSONObject.NULL)
            put("endedAtMillis", session.endedAtMillis)
            put("habitIds", JSONArray(linksBySession[session.id].orEmpty().map { it.habitId }))
            put("planId", session.planId ?: JSONObject.NULL)
        })
    }

    val planEntries = JSONArray()
    for (plan in plans) {
        planEntries.put(JSONObject().apply {
            put("id", plan.id)
            put("title", plan.title)
            put("date", LocalDate.ofEpochDay(plan.day).toString())
            put("minuteOfDay", plan.minuteOfDay)
            put("habitId", plan.habitId ?: JSONObject.NULL)
            put("durationMinutes", plan.durationMinutes ?: JSONObject.NULL)
            put("repeatMask", plan.repeatMask)
            put("reminderMinutes", plan.reminderMinutes ?: JSONObject.NULL)
            put("endsBefore", plan.endsBefore?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL)
        })
    }

    val skipEntries = JSONArray(skips.map { skip ->
        JSONObject().apply {
            put("planId", skip.planId)
            put("date", LocalDate.ofEpochDay(skip.day).toString())
        }
    })
    val taskEntries = JSONArray(tasks.map { task ->
        JSONObject().apply {
            put("id", task.id)
            put("title", task.title)
            put("deadline", task.deadlineDay?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL)
            put("done", task.done)
        }
    })
    val shiftEntries = JSONArray(shifts.map { shift ->
        JSONObject().apply {
            put("id", shift.id)
            put("date", LocalDate.ofEpochDay(shift.day).toString())
            put("title", shift.title)
            put("night", shift.night)
            put("planned", shift.planned.toJson())
            put("actual", shift.actual?.toJson() ?: JSONObject.NULL)
            put("hourlyKopecks", shift.hourlyKopecks ?: JSONObject.NULL)
        }
    })
    val templateEntries = JSONArray(templates.map { template ->
        JSONObject().apply {
            put("id", template.id)
            put("title", template.title)
            put("night", template.night)
            put("hours", template.hours.toJson())
            put("hourlyKopecks", template.hourlyKopecks ?: JSONObject.NULL)
        }
    })
    return JSONObject().apply {
        put("formatVersion", BACKUP_FORMAT)
        put("mosaic", JSONObject().apply {
            put("template", "growing_square")
            put("initialSide", INITIAL_MOSAIC_SIDE)
        })
        put("habits", habitEntries)
        put("completions", completionEntries)
        put("sessions", sessionEntries)
        put("plans", planEntries)
        put("skips", skipEntries)
        put("tasks", taskEntries)
        put("shifts", shiftEntries)
        put("shiftTemplates", templateEntries)
    }.toString(2)
}

private fun ShiftHours.toJson() = JSONObject().apply {
    put("startMinute", startMinute)
    put("endMinute", endMinute)
    put("breakMinutes", breakMinutes)
}
