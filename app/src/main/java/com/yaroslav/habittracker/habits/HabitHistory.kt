package com.yaroslav.habittracker.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.MAX_SESSION_MINUTES
import com.yaroslav.habittracker.timer.clockText
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val hourFormat = DateTimeFormatter.ofPattern("HH:mm", russianLocale)

@Composable
internal fun HistoryScreen(
    progress: HabitProgress,
    today: LocalDate,
    writing: Boolean,
    changeMark: (LocalDate, Boolean, Long?) -> Unit,
    addTime: () -> Unit,
    editTime: (String) -> Unit,
) {
    var chooseDate by rememberSaveable { mutableStateOf(false) }
    var armMove by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedMark by rememberSaveable { mutableStateOf<Long?>(null) }
    val timed = progress.habit.targetMinutes != null

    ScreenContent {
        Text(progress.habit.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        if (timed) {
            HabitDayProgress(progress)
            Button(addTime, Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_time)) }
        } else {
            Button(
                { armMove = null; chooseDate = true },
                Modifier.fillMaxWidth(),
                enabled = !writing,
            ) {
                Text(stringResource(R.string.history_add_mark))
            }
        }

        if (progress.marks.isEmpty() && progress.sessions.isEmpty()) {
            Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (progress.marks.isNotEmpty()) {
            Text(stringResource(R.string.history_marks), style = MaterialTheme.typography.titleLarge)
        }
        for (day in progress.marks) {
            HistoryRow(
                title = LocalDate.ofEpochDay(day).historyLabel(),
                hint = null,
                open = { selectedMark = day },
            )
        }
        if (progress.sessions.isNotEmpty()) {
            Text(stringResource(R.string.history_sessions), style = MaterialTheme.typography.titleLarge)
        }
        for (session in progress.sessions) {
            val date = LocalDate.ofEpochDay(session.day).historyLabel()
            val start = session.startedAtMillis?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(hourFormat)
            } ?: stringResource(R.string.time_manual)
            HistoryRow(title = date, hint = start, end = clockText(session.durationMillis)) { editTime(session.id) }
        }
    }

    selectedMark?.let { mark ->
        AlertDialog(
            onDismissRequest = { selectedMark = null },
            title = { Text(LocalDate.ofEpochDay(mark).historyLabel()) },
            confirmButton = {
                TextButton(onClick = {
                    selectedMark = null
                    armMove = mark
                    chooseDate = true
                }) { Text(stringResource(R.string.history_move_mark)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedMark = null
                    changeMark(LocalDate.ofEpochDay(mark), false, null)
                }) { Text(stringResource(R.string.history_remove_mark)) }
            },
        )
    }
    if (chooseDate) {
        DayPicker(
            initial = armMove?.let(LocalDate::ofEpochDay) ?: today.minusDays(1),
            maxDate = today,
            dismiss = { chooseDate = false },
        ) { selected ->
            chooseDate = false
            changeMark(selected, true, armMove)
            armMove = null
        }
    }
}

@Composable
private fun HistoryRow(title: String, hint: String?, end: String? = null, open: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), onClick = open) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (hint != null) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (end != null) Text(end, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            AppIcon(R.drawable.ic_next)
        }
    }
}

@Composable
internal fun ManualTimeScreen(
    habit: Habit,
    session: FocusSession?,
    today: LocalDate,
    writing: Boolean,
    save: (LocalDate, Long) -> Unit,
    remove: (() -> Unit)?,
) {
    var day by rememberSaveable(session?.id) { mutableStateOf(session?.day ?: today.toEpochDay()) }
    var minutes by rememberSaveable(session?.id) {
        mutableStateOf(session?.let { (it.durationMillis / 60_000L).toString() }.orEmpty())
    }
    var seconds by rememberSaveable(session?.id) {
        mutableStateOf(session?.let { ((it.durationMillis / 1000L) % 60).toString() }.orEmpty())
    }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val enteredSeconds = (minutes.toLongOrNull() ?: 0L) * 60 + (seconds.toLongOrNull() ?: 0L)
    val duration = session?.durationMillis?.takeIf { it / 1000L == enteredSeconds }
        ?: enteredSeconds * 1000L
    val date = LocalDate.ofEpochDay(day)

    ScreenContent(Modifier.imePadding()) {
        Text(habit.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        OutlinedButton({ choosing = true }, Modifier.fillMaxWidth(), enabled = !writing) {
            Text(stringResource(R.string.date) + " · " + date.historyLabel())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(minutes, { if (it.length <= 4 && it.all(Char::isDigit)) minutes = it },
                Modifier.weight(1f), enabled = !writing, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.timer_minutes)) })
            OutlinedTextField(seconds, { if (it.length <= 2 && it.all(Char::isDigit)) seconds = it },
                Modifier.weight(1f), enabled = !writing, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.timer_seconds)) })
        }
        if (session != null) {
            Text(stringResource(R.string.time_shared_notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            { save(date, duration) },
            Modifier.fillMaxWidth(),
            enabled = !writing && duration in 1..MAX_SESSION_MINUTES * 60_000L &&
                (seconds.toIntOrNull() ?: 0) in 0..59,
        ) {
            Text(stringResource(R.string.save_time))
        }
        if (remove != null) {
            TextButton({ confirmingDelete = true }, Modifier.fillMaxWidth(), enabled = !writing) {
                Text(stringResource(R.string.time_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (choosing) {
        DayPicker(date, today, dismiss = { choosing = false }) {
            day = it.toEpochDay()
            choosing = false
        }
    }
    if (confirmingDelete && remove != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.time_delete_title)) },
            text = { Text(stringResource(R.string.time_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; remove() }) {
                    Text(stringResource(R.string.time_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(
    initial: LocalDate,
    maxDate: LocalDate,
    dismiss: () -> Unit,
    select: (LocalDate) -> Unit,
) {
    val selectable = remember(maxDate) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcDay(utcTimeMillis).isAfter(maxDate)
            override fun isSelectableYear(year: Int): Boolean = year <= maxDate.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            val selected = state.selectedDateMillis?.let(::utcDay)
            TextButton(onClick = { selected?.let(select) }, enabled = selected != null && !selected.isAfter(maxDate)) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state, title = { Text(stringResource(R.string.choose_date), Modifier.padding(24.dp)) })
    }
}

private fun utcDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
