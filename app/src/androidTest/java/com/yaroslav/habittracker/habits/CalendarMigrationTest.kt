package com.yaroslav.habittracker.habits

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarMigrationTest {
    @Test
    fun versionTwoMarksSessionsAndActiveTimerSurviveCalendarMigration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "calendar-migration-${UUID.randomUUID()}.db"
        val schema = readSchema(2)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.setForeignKeyConstraintsEnabled(true)
                createSchema(old, schema, 2)
                old.execSQL("INSERT INTO habits (id, name, description, targetMinutes) VALUES (9, 'Учёба', '', 2)")
                old.execSQL("INSERT INTO habit_completions (habitId, day) VALUES (9, 14000)")
                old.execSQL(
                    """
                    INSERT INTO focus_sessions (id, day, durationMillis, startedAtMillis, endedAtMillis)
                    VALUES ('previous-session', 15000, 120000, 100000, 220000)
                    """.trimIndent(),
                )
                old.execSQL("INSERT INTO session_habits (sessionId, habitId) VALUES ('previous-session', 9)")
                old.execSQL(
                    """
                    INSERT INTO active_timer (
                        slot, id, limitMillis, selectedHabitIds, startedAtMillis, zoneId,
                        accumulatedMillis, runningSinceWall, bootCount
                    ) VALUES (1, 'previous-timer', 60000, '9', 220000, 'UTC', 15000, 235000, 1)
                    """.trimIndent(),
                )
            }

            val database = Room.databaseBuilder(context, HabitDatabase::class.java, name).build()
            try {
                val snapshot = database.habits().snapshot()
                assertEquals("Учёба", snapshot.habits.single().name)
                assertEquals(14_000L, snapshot.completions.single().day)
                assertEquals("previous-session", snapshot.sessions.single().id)
                assertEquals(9L, snapshot.links.single().habitId)
                assertNull(snapshot.sessions.single().planId)
                assertTrue(snapshot.plans.isEmpty())

                val draft = checkNotNull(database.timer().active())
                assertEquals("previous-timer", draft.id)
                assertEquals(15_000L, draft.accumulatedMillis)
                assertEquals(listOf(9L), draft.habitIds)
                assertNull(draft.planId)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    fun existingPlanSurvivesVersionFourWithNoRepeatOrReminder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "planning-migration-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                createSchema(old, readSchema(3), 3)
                old.execSQL("INSERT INTO day_plans (id, title, day, minuteOfDay) VALUES (7, 'Учёба', 20000, 540)")
                old.execSQL(
                    """
                    INSERT INTO focus_sessions (id, day, durationMillis, endedAtMillis, planId)
                    VALUES ('linked', 20000, 60000, 60000, 7)
                    """.trimIndent(),
                )
            }
            val database = Room.databaseBuilder(context, HabitDatabase::class.java, name).build()
            try {
                val snapshot = database.habits().snapshot()
                val plan = snapshot.plans.single()
                assertEquals(7L, plan.id)
                assertEquals(540, plan.minuteOfDay)
                assertEquals(0, plan.repeatMask)
                assertNull(plan.durationMinutes)
                assertNull(plan.reminderMinutes)
                assertEquals(7L, snapshot.sessions.single().planId)
                assertTrue(snapshot.tasks.isEmpty())
                assertTrue(snapshot.skips.isEmpty())
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun readSchema(version: Int): JSONObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schemaPath = "com.yaroslav.habittracker.habits.HabitDatabase/$version.json"
        return assets.open(schemaPath).bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
    }

    private fun createSchema(database: SQLiteDatabase, schema: JSONObject, version: Int) {
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val name = entity.getString("tableName")
            database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (position in 0 until indices.length()) {
                val sql = indices.getJSONObject(position).getString("createSql")
                database.execSQL(sql.replace("\${TABLE_NAME}", name))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (index in 0 until setup.length()) database.execSQL(setup.getString(index))
        database.version = version
    }
}
