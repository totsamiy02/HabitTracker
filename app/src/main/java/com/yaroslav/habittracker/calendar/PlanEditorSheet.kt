package com.yaroslav.habittracker.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.habits.Habit
import com.yaroslav.habittracker.planning.MAX_PLAN_MINUTES
import com.yaroslav.habittracker.planning.PlanEditScope
import com.yaroslav.habittracker.planning.ReminderPicker
import com.yaroslav.habittracker.planning.RepeatPicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanEditorSheet(
    initial: DayPlan?,
    date: LocalDate,
    scope: PlanEditScope,
    habits: List<Habit>,
    writing: Boolean,
    failed: Boolean,
    dismiss: () -> Unit,
    save: (DayPlan) -> Unit,
) {
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var day by rememberSaveable(initial?.id) { mutableLongStateOf(initial?.day ?: date.toEpochDay()) }
    var minute by rememberSaveable(initial?.id) { mutableIntStateOf(initial?.minuteOfDay ?: 19 * 60) }
    var habitId by rememberSaveable(initial?.id) { mutableStateOf(initial?.habitId) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var showHabits by rememberSaveable { mutableStateOf(false) }
    val selectedHabit = habits.firstOrNull { it.id == habitId }
    var duration by rememberSaveable(initial?.id) { mutableStateOf(initial?.durationMinutes?.toString().orEmpty()) }
    val durationMinutes = duration.toIntOrNull()?.takeIf { it in 1..MAX_PLAN_MINUTES }
    val durationValid = duration.isBlank() || durationMinutes != null
    val movingSeries = initial != null && initial.repeatMask != 0 && scope == PlanEditScope.Following
    val dateValid = !movingSeries || (
        initial != null && day >= initial.day && (initial.endsBefore == null || day < initial.endsBefore)
    )
    val repeatingAllowed = initial?.repeatMask == null || initial.repeatMask == 0 || scope == PlanEditScope.Following
    var repeatMask by rememberSaveable(initial?.id, scope) {
        mutableIntStateOf(if (repeatingAllowed) initial?.repeatMask ?: 0 else 0)
    }
    var reminder by rememberSaveable(initial?.id) { mutableStateOf(initial?.reminderMinutes) }

    ModalBottomSheet(
        onDismissRequest = { if (!writing) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (initial == null) R.string.calendar_new_plan else R.string.calendar_edit_plan),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= PLAN_TITLE_LIMIT) title = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !writing,
                singleLine = true,
                label = { Text(stringResource(R.string.habit_name)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ showDate = true }, Modifier.weight(1f), enabled = !writing) {
                    Text(
                        stringResource(R.string.calendar_date_field, calendarDateLabel(LocalDate.ofEpochDay(day))),
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton({ showTime = true }, Modifier.weight(1f), enabled = !writing) {
                    Text(
                        stringResource(R.string.calendar_time_field, planClockLabel(minute)),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (!dateValid) {
                Text(stringResource(R.string.plan_repeat_date_invalid), color = MaterialTheme.colorScheme.error)
            }
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton({ showHabits = true }, Modifier.fillMaxWidth(), enabled = !writing) {
                    Text(
                        selectedHabit?.let { stringResource(R.string.calendar_linked_habit, it.name) }
                            ?: stringResource(R.string.calendar_no_habit),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = showHabits, onDismissRequest = { showHabits = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.calendar_no_habit)) },
                        onClick = { habitId = null; showHabits = false },
                    )
                    for (habit in habits) {
                        DropdownMenuItem(
                            text = { Text(habit.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = { habitId = habit.id; showHabits = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = duration,
                onValueChange = { if (it.length <= 4 && (it.isEmpty() || it.toIntOrNull() != null)) duration = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !writing,
                singleLine = true,
                isError = !durationValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.plan_duration)) },
                supportingText = {
                    Text(stringResource(if (durationValid) R.string.plan_duration_optional else R.string.plan_duration_invalid))
                },
            )
            if (repeatingAllowed) RepeatPicker(repeatMask, { repeatMask = it }, !writing)
            ReminderPicker(reminder, { reminder = it }, !writing)
            if (failed) {
                Text(stringResource(R.string.calendar_save_error), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(dismiss, Modifier.weight(1f), enabled = !writing) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        save(DayPlan(
                            id = initial?.id ?: 0L,
                            title = title,
                            day = day,
                            minuteOfDay = minute,
                            habitId = selectedHabit?.id,
                            durationMinutes = durationMinutes,
                            repeatMask = repeatMask,
                            reminderMinutes = reminder,
                        ))
                    },
                    modifier = Modifier.weight(1.5f),
                    enabled = !writing && title.isNotBlank() && durationValid && dateValid,
                ) {
                    Text(stringResource(R.string.calendar_save_plan), textAlign = TextAlign.Center)
                }
            }
        }
    }
    if (showDate) CalendarDateDialog(day, dismiss = { showDate = false }) {
        day = it
        showDate = false
    }
    if (showTime) PlanTimeDialog(minute, dismiss = { showTime = false }) {
        minute = it
        showTime = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDateDialog(epochDay: Long, dismiss: () -> Unit, select: (Long) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        yearRange = CALENDAR_YEARS,
    )
    val selected = state.selectedDateMillis?.let {
        Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
    }
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            TextButton(onClick = { selected?.let(select) }, enabled = selected != null) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state, title = { Text(stringResource(R.string.choose_date), Modifier.padding(24.dp)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanTimeDialog(minuteOfDay: Int, dismiss: () -> Unit, select: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.calendar_choose_time)) },
        text = { TimeInput(state) },
        confirmButton = {
            TextButton(onClick = { select(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
