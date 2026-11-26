package com.yaroslav.habittracker.settings

import android.content.ContentResolver
import android.net.Uri
import com.yaroslav.habittracker.habits.HabitDao
import com.yaroslav.habittracker.habits.INITIAL_MOSAIC_SIDE
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class BackupExporter(private val dao: HabitDao, private val resolver: ContentResolver) {
    suspend fun writeTo(destination: Uri) = withContext(Dispatchers.IO) {
        val snapshot = dao.snapshot()
        val habits = JSONArray()
        for (habit in snapshot.habits) {
            habits.put(JSONObject().apply {
                put("id", habit.id)
                put("name", habit.name)
                put("description", habit.description)
            })
        }

        val completions = JSONArray()
        for (completion in snapshot.completions) {
            completions.put(JSONObject().apply {
                put("habitId", completion.habitId)
                put("date", LocalDate.ofEpochDay(completion.day).toString())
            })
        }

        val backup = JSONObject().apply {
            put("formatVersion", 1)
            put("mosaic", JSONObject().apply {
                put("template", "growing_square")
                put("initialSide", INITIAL_MOSAIC_SIDE)
            })
            put("habits", habits)
            put("completions", completions)
        }.toString(2)

        val output = resolver.openOutputStream(destination, "wt")
            ?: throw IOException("Cannot open backup document")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(backup) }
    }
}
