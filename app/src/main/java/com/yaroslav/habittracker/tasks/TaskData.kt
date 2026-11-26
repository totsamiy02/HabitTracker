package com.yaroslav.habittracker.tasks

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.calendar.CALENDAR_YEARS
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val TASK_TITLE_LIMIT = 100

@Entity(tableName = "tasks")
data class ChecklistTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val deadlineDay: Long? = null,
    val done: Boolean = false,
)

@Dao
internal interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY deadlineDay IS NULL, deadlineDay, id")
    fun observe(): Flow<List<ChecklistTask>>

    @Insert
    suspend fun insert(task: ChecklistTask)

    @Query("UPDATE tasks SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)
}

internal sealed interface TaskState {
    data object Loading : TaskState
    data object Failed : TaskState
    data class Ready(val items: List<ChecklistTask>) : TaskState
}

internal class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as HabitTrackerApplication).database.tasks()
    private val attempt = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tasks = attempt.flatMapLatest {
        dao.observe()
            .map<List<ChecklistTask>, TaskState> { TaskState.Ready(it) }
            .catch { emit(TaskState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskState.Loading)

    fun retry() = attempt.update { it + 1 }

    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun clearError() {
        failed = false
    }

    fun add(title: String, deadline: LocalDate?, saved: () -> Unit) {
        val normalized = title.trim()
        if (normalized.length !in 1..TASK_TITLE_LIMIT) return
        if (deadline != null && deadline.year !in CALENDAR_YEARS) return
        write {
            dao.insert(ChecklistTask(title = normalized, deadlineDay = deadline?.toEpochDay()))
            saved()
        }
    }

    fun toggle(task: ChecklistTask) = write { dao.setDone(task.id, !task.done) }

    private fun write(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
}
