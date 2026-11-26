package com.yaroslav.habittracker.settings

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.habits.HabitCompletion
import com.yaroslav.habittracker.habits.HabitDatabase
import com.yaroslav.habittracker.habits.HabitSnapshot
import com.yaroslav.habittracker.planning.PlanSkip
import com.yaroslav.habittracker.planning.WORKDAYS
import com.yaroslav.habittracker.tasks.ChecklistTask
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.SessionHabit
import com.yaroslav.habittracker.timer.TimerMoment
import com.yaroslav.habittracker.timer.TimerRepository
import com.yaroslav.habittracker.work.ShiftHours
import com.yaroslav.habittracker.work.ShiftTemplate
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreRepositoryTest {
    private val day = LocalDate.of(2026, 11, 26).toEpochDay()
    private val template = ShiftTemplate(1, "Ночная смена", true, ShiftHours(1_320, 360, 30), 40_000)
    private val shift = template.shift(day).copy(id = 2, actual = ShiftHours(1_320, 300, 30))
    private val snapshot = HabitSnapshot(
        habitats(),
        listOf(HabitCompletion(8, day)),
        listOf(FocusSession("focus", day, 1_800_000, null, 1_800_000, planId = 6)),
        listOf(SessionHabit("focus", 8)),
        listOf(DayPlan(6, "Учёба", day, 120, 8, 30, WORKDAYS, 5)),
        listOf(PlanSkip(6, day + 1)),
        listOf(ChecklistTask(4, "Собрать конспект", day + 2, true)),
        shifts = listOf(shift),
        templates = listOf(template),
    )

    @Test
    fun roundTripRestoresAllRecordsOnAnEmptyInstallation() = withDatabase { database ->
        val repository = RestoreRepository(database)
        val empty = repository.counts()
        assertFalse(empty.hasRecords)

        val backup = readBackup(snapshot.toBackupDocument().byteInputStream())
        assertEquals(BACKUP_FORMAT, backup.version)
        assertEquals(RestoreResult.Applied, repository.replace(backup.snapshot, empty))
        assertEquals(snapshot, database.habits().snapshot())
        assertEquals(1, database.habits().record(8)?.progress(day)?.completedDays)
    }

    @Test
    fun legacyCopyAndDamagedFilesAreHandledWithoutChangingExistingData() = withDatabase { database ->
        val repository = RestoreRepository(database)
        repository.replace(snapshot, repository.counts())
        val legacy = """
            {
              "formatVersion": 1,
              "mosaic": {"template":"growing_square","initialSide":4},
              "habits": [{"id":1,"name":"Прогулка","description":""}],
              "completions": [{"habitId":1,"date":"2026-11-26"}]
            }
        """.trimIndent()
        val decoded = readBackup(legacy.byteInputStream())
        assertEquals(1, decoded.version)
        assertEquals(127, decoded.snapshot.habits.single().weekdays)
        assertEquals(listOf(HabitCompletion(1, day)), decoded.snapshot.completions)
        assertThrows(UnsupportedBackup::class.java) {
            readBackup(legacy.replace("\"formatVersion\": 1", "\"formatVersion\": 99").byteInputStream())
        }
        assertThrows(InvalidBackup::class.java) {
            readBackup(legacy.replace("\"habitId\":1", "\"habitId\":2").byteInputStream())
        }
        assertEquals(snapshot, database.habits().snapshot())
        assertEquals(RestoreResult.Applied, repository.replace(decoded.snapshot, repository.counts()))
        assertEquals(decoded.snapshot, database.habits().snapshot())
    }

    @Test
    fun versionFourCopyRemainsReadableAndInvalidShiftCannotTouchExistingData() = withDatabase { database ->
        val repository = RestoreRepository(database)
        repository.replace(snapshot, repository.counts())
        val legacy = JSONObject(snapshot.toBackupDocument()).apply {
            put("formatVersion", 4)
            remove("shifts")
            remove("shiftTemplates")
        }
        val decoded = readBackup(legacy.toString().byteInputStream())
        assertEquals(4, decoded.version)
        assertEquals(snapshot.copy(shifts = emptyList(), templates = emptyList()), decoded.snapshot)

        val damaged = JSONObject(snapshot.toBackupDocument())
        damaged.getJSONArray("shifts").getJSONObject(0).getJSONObject("actual").put("breakMinutes", 1_439)
        assertThrows(InvalidBackup::class.java) { readBackup(damaged.toString().byteInputStream()) }
        assertEquals(snapshot, database.habits().snapshot())
    }

    @Test
    fun activeTimerAndWriteFailureLeaveTheDatabaseIntact() = withDatabase { database ->
        val repository = RestoreRepository(database)
        val empty = repository.counts()
        repository.replace(snapshot, empty)
        assertEquals(RestoreResult.Changed(snapshot.counts()), repository.replace(snapshot, empty))

        val timer = TimerRepository(database) { TimerMoment(1_000, 1_000, 1) }
        val active = timer.start(60_000, setOf(8))
        assertEquals(RestoreResult.RunningTimer, repository.replace(snapshot, snapshot.counts()))
        timer.finish(active.id, expiredOnly = false)

        try {
            repository.replace(snapshot.copy(links = listOf(SessionHabit("focus", 123))), snapshot.counts())
            throw AssertionError("Expected a foreign-key failure")
        } catch (_: SQLiteConstraintException) {
            assertEquals(snapshot, database.habits().snapshot())
        }
    }

    private fun withDatabase(block: suspend (HabitDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun habitats() = listOf(Habit(8, "Учёба", "Читать"))
}
