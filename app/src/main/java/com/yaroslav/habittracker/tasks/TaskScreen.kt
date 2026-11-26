package com.yaroslav.habittracker.tasks

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.CalendarDateDialog
import com.yaroslav.habittracker.calendar.calendarDateLabel
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.ErrorScreen
import com.yaroslav.habittracker.ui.LoadingScreen
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.LocalDate

private enum class TaskFilter(val label: Int) {
    Active(R.string.task_active), Done(R.string.done), All(R.string.task_all),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskScreen(
    model: TaskViewModel,
    state: TaskState,
    activeTimer: (@Composable () -> Unit)?,
) {
    var filter by rememberSaveable { mutableStateOf(TaskFilter.Active) }
    var creating by rememberSaveable { mutableStateOf(false) }

    when (state) {
        TaskState.Loading -> LoadingScreen()
        TaskState.Failed -> ErrorScreen(R.string.task_load_error, model::retry)
        is TaskState.Ready -> ScreenContent {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.task_tab),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                FilledTonalIconButton(onClick = {
                    model.clearError()
                    creating = true
                }) {
                    AppIcon(R.drawable.ic_add, description = stringResource(R.string.task_add))
                }
            }
            activeTimer?.invoke()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in TaskFilter.entries) {
                    FilterChip(filter == item, { filter = item }, { Text(stringResource(item.label)) })
                }
            }
            if (model.failed && !creating) {
                Text(stringResource(R.string.task_save_error), color = MaterialTheme.colorScheme.error)
            }

            val shown = state.items.filter { filter == TaskFilter.All || it.done == (filter == TaskFilter.Done) }
            if (shown.isEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.task_empty), Modifier.padding(18.dp))
                }
            }
            for (task in shown) {
                TaskCard(task, !model.busy) { model.toggle(task) }
            }
        }
    }

    if (creating) {
        TaskEditor(
            writing = model.busy,
            failed = model.failed,
            dismiss = { creating = false },
            save = { title, date ->
                model.add(title, date) {
                    creating = false
                    filter = TaskFilter.Active
                }
            },
        )
    }
}

@Composable
private fun TaskCard(task: ChecklistTask, enabled: Boolean, toggle: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = task.done, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle() })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(task.done, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                )
                Text(
                    task.deadlineDay?.let { calendarDateLabel(LocalDate.ofEpochDay(it)) }
                        ?: stringResource(R.string.task_no_deadline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(
    writing: Boolean,
    failed: Boolean,
    dismiss: () -> Unit,
    save: (String, LocalDate?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var deadline by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!writing) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(stringResource(R.string.task_new), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                title, { if (it.length <= TASK_TITLE_LIMIT) title = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !writing,
                singleLine = true,
                label = { Text(stringResource(R.string.habit_name)) },
            )
            OutlinedButton({ picking = true }, Modifier.fillMaxWidth(), enabled = !writing) {
                Text(
                    deadline?.let { stringResource(R.string.task_deadline, calendarDateLabel(LocalDate.ofEpochDay(it))) }
                        ?: stringResource(R.string.task_set_deadline),
                )
            }
            if (deadline != null) {
                TextButton(onClick = { deadline = null }, enabled = !writing) {
                    Text(stringResource(R.string.task_no_deadline))
                }
            }
            if (failed) {
                Text(stringResource(R.string.task_save_error), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(dismiss, Modifier.weight(1f), enabled = !writing) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { save(title, deadline?.let(LocalDate::ofEpochDay)) },
                    modifier = Modifier.weight(1.5f),
                    enabled = title.isNotBlank() && !writing,
                ) {
                    Text(stringResource(R.string.task_save))
                }
            }
        }
    }
    if (picking) CalendarDateDialog(deadline ?: LocalDate.now().toEpochDay(), { picking = false }) {
        deadline = it
        picking = false
    }
}
