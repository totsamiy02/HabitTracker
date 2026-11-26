package com.yaroslav.habittracker.habits

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
)

@Entity(
    tableName = "habit_completions",
    primaryKeys = ["habitId", "day"],
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

data class HabitProgress(
    @Embedded val habit: Habit,
    val completedDays: Int,
    val completedToday: Boolean,
)

data class HabitSnapshot(val habits: List<Habit>, val completions: List<HabitCompletion>)

@Dao
interface HabitDao {
    @Query(
        """
        SELECT habits.*, COUNT(completions.day) AS completedDays,
            MAX(CASE WHEN completions.day = :day THEN 1 ELSE 0 END) AS completedToday
        FROM habits
        LEFT JOIN habit_completions AS completions ON completions.habitId = habits.id
        GROUP BY habits.id
        ORDER BY habits.id DESC
        """
    )
    fun observeProgress(day: Long): Flow<List<HabitProgress>>

    @Insert
    suspend fun insert(habit: Habit): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(completion: HabitCompletion): Long

    @Query("SELECT * FROM habits ORDER BY id")
    suspend fun allHabits(): List<Habit>

    @Query("SELECT * FROM habit_completions ORDER BY habitId, day")
    suspend fun allCompletions(): List<HabitCompletion>

    @Transaction
    suspend fun snapshot(): HabitSnapshot = HabitSnapshot(allHabits(), allCompletions())
}

@Database(entities = [Habit::class, HabitCompletion::class], version = 1)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habits(): HabitDao
}
