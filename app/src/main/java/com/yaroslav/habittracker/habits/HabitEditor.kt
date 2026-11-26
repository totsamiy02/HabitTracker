package com.yaroslav.habittracker.habits

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.timer.MAX_SESSION_MINUTES
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.DayOfWeek

@StringRes
private val weekdayLabels = listOf(
    R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed, R.string.weekday_thu,
    R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun,
)

@Composable
internal fun scheduleLabel(mask: Int): String {
    if (mask == ALL_WEEKDAYS) return stringResource(R.string.every_day)
    val days = mutableListOf<String>()
    for ((index, label) in weekdayLabels.withIndex()) {
        if (mask and (1 shl index) != 0) days.add(stringResource(label))
    }
    return days.joinToString(", ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HabitEditorScreen(
    habit: Habit?,
    writing: Boolean,
    save: (String, String, Int, Int?) -> Unit,
) {
    var name by rememberSaveable(habit?.id) { mutableStateOf(habit?.name.orEmpty()) }
    var description by rememberSaveable(habit?.id) { mutableStateOf(habit?.description.orEmpty()) }
    var timed by rememberSaveable(habit?.id) { mutableStateOf(habit?.targetMinutes != null) }
    var goal by rememberSaveable(habit?.id) { mutableStateOf(habit?.targetMinutes?.toString() ?: "30") }
    var days by rememberSaveable(habit?.id) { mutableIntStateOf(habit?.weekdays ?: ALL_WEEKDAYS) }
    val validGoal = goal.toIntOrNull()?.takeIf { it in 1..MAX_SESSION_MINUTES }

    ScreenContent(Modifier.imePadding()) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !writing,
            label = { Text(stringResource(R.string.habit_name)) }, singleLine = true)
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), enabled = !writing,
            label = { Text(stringResource(R.string.habit_description)) }, minLines = 2)

        Text(stringResource(R.string.habit_kind), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!timed, { timed = false }, { Text(stringResource(R.string.habit_kind_mark)) }, enabled = !writing)
            FilterChip(timed, { timed = true }, { Text(stringResource(R.string.habit_kind_time)) }, enabled = !writing)
        }
        if (timed) {
            OutlinedTextField(
                value = goal,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) goal = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !writing,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.daily_goal)) },
            )
        }
        Text(stringResource(R.string.schedule), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((index, day) in DayOfWeek.entries.withIndex()) {
                val bit = weekdayBit(day)
                FilterChip(days and bit != 0, { days = days xor bit },
                    { Text(stringResource(weekdayLabels[index])) }, enabled = !writing)
            }
        }
        if (days != 0) Text(scheduleLabel(days), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { save(name, description, days, if (timed) validGoal else null) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !writing && name.isNotBlank() && days != 0 && (!timed || validGoal != null),
        ) {
            Text(stringResource(if (habit == null) R.string.create_habit else R.string.save_changes))
        }
    }
}
