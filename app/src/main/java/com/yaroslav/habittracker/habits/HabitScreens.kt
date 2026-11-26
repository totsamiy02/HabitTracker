package com.yaroslav.habittracker.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val russianLocale: Locale = Locale.forLanguageTag("ru-RU")
private val todayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", russianLocale)
private val historyFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", russianLocale)

internal fun LocalDate.historyLabel(): String = format(historyFormat)

@Composable
internal fun TodayScreen(
    state: HabitsState.Loaded,
    create: () -> Unit,
    open: (Long) -> Unit,
    showAll: () -> Unit,
    openWeek: () -> Unit,
    openBulk: () -> Unit,
    openTimer: () -> Unit,
    activeTimer: (@Composable () -> Unit)?,
) {
    ScreenContent {
        Text(stringResource(R.string.today), style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold)
        Text(state.date.format(todayFormat).replaceFirstChar { it.titlecase(russianLocale) },
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        activeTimer?.invoke()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.habits), Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (activeTimer == null) {
                FilledTonalIconButton(onClick = openTimer) {
                    AppIcon(R.drawable.ic_timer_status, description = stringResource(R.string.timer))
                }
            }
            if (state.habits.isNotEmpty()) {
                FilledTonalIconButton(onClick = create) {
                    AppIcon(R.drawable.ic_add, description = stringResource(R.string.create_habit))
                }
            }
        }

        if (state.habits.isEmpty()) {
            FirstHabitCard(create)
        } else {
            val scheduled = state.scheduled
            if (scheduled.count { !it.completedToday && it.habit.targetMinutes == null } >= 2) {
                OutlinedButton(openBulk, Modifier.fillMaxWidth()) { Text(stringResource(R.string.bulk_mark)) }
            }
            if (scheduled.isEmpty()) {
                Text(stringResource(R.string.no_habits_today), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (progress in scheduled) {
                HabitCard(progress) { open(progress.habit.id) }
            }
            FilledTonalButton(showAll, Modifier.fillMaxWidth()) { Text(stringResource(R.string.all_habits)) }
        }
        OutlinedButton(openWeek, Modifier.fillMaxWidth()) { Text(stringResource(R.string.week_title)) }
    }
}

@Composable
internal fun AllHabitsScreen(state: HabitsState.Loaded, create: () -> Unit, open: (Long) -> Unit) {
    ScreenContent {
        Button(create, Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_habit)) }
        for (progress in state.habits) {
            HabitCard(progress) { open(progress.habit.id) }
        }
    }
}

@Composable
private fun FirstHabitCard(create: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Mosaic(0, Modifier.size(86.dp))
            Text(stringResource(R.string.first_habit), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(stringResource(R.string.first_habit_hint), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(create, Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_habit)) }
        }
    }
}

@Composable
private fun HabitCard(progress: HabitProgress, open: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), onClick = open) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(progress.habit.name, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                HabitDayProgress(progress)
                Text(
                    stringResource(R.string.mosaic_progress, progress.completedDays, mosaicLayout(progress.completedDays).capacity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Mosaic(progress.completedDays, Modifier.size(60.dp))
            AppIcon(R.drawable.ic_next, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun HabitDayProgress(progress: HabitProgress) {
    val target = progress.habit.targetMinutes
    if (target == null) {
        Text(
            stringResource(if (progress.completedToday) R.string.habit_done else R.string.habit_pending),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        Text(stringResource(R.string.time_progress, progress.todayMillis / 60_000L, target),
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { (progress.todayMillis.toDouble() / (target * 60_000L)).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun BulkMarkScreen(state: HabitsState.Loaded, writing: Boolean, save: (Set<Long>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(longArrayOf()) }
    val candidates = state.scheduled.filter { it.habit.targetMinutes == null && !it.completedToday }
    val eligible = candidates.map { it.habit.id }.toSet()
    val valid = selected.filter { it in eligible }.toSet()
    ScreenContent {
        Text(stringResource(R.string.bulk_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (candidates.isEmpty()) Text(stringResource(R.string.no_habits_today))
        for (progress in candidates) {
            val id = progress.habit.id
            val checked = id in valid
            Panel(Modifier.fillMaxWidth(), onClick = {
                if (!writing) selected = (if (checked) selected.filter { it != id }
                    else selected.toList() + id).toLongArray()
            }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column {
                        Text(progress.habit.name, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.mosaic_progress, progress.completedDays,
                            mosaicLayout(progress.completedDays).capacity), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button({ save(valid) }, Modifier.fillMaxWidth(), enabled = valid.isNotEmpty() && !writing) {
            Text(pluralStringResource(R.plurals.bulk_action, valid.size, valid.size))
        }
    }
}

@Composable
internal fun HabitDetailsScreen(
    progress: HabitProgress,
    writing: Boolean,
    markToday: () -> Unit,
    undoToday: () -> Unit,
    openTimer: () -> Unit,
    addTime: () -> Unit,
    history: () -> Unit,
) {
    val layout = mosaicLayout(progress.completedDays)
    val timed = progress.habit.targetMinutes != null

    ScreenContent {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(progress.habit.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            if (progress.habit.description.isNotBlank()) {
                Text(progress.habit.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(scheduleLabel(progress.habit.weekdays), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (timed) HabitDayProgress(progress)
        Panel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mosaic), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.mosaic_progress, progress.completedDays, layout.capacity),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Mosaic(
                    completedDays = progress.completedDays,
                    modifier = Modifier.widthIn(max = minOf(51.dp * layout.side, 400.dp)).fillMaxWidth(),
                    highlightNext = !timed && !progress.completedToday && layout.capacity > progress.completedDays,
                    emphasizeLast = progress.completedToday,
                    onMarkToday = if (writing || timed || progress.completedToday) null else markToday,
                )
            }
        }

        if (progress.completedToday) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_check)
                    Text(stringResource(if (timed) R.string.habit_goal_met else R.string.habit_done_today),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (timed) {
            Button(openTimer, Modifier.fillMaxWidth()) { Text(stringResource(R.string.timer_start_from_habit)) }
            OutlinedButton(addTime, Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_time)) }
        } else if (progress.completedToday) {
            OutlinedButton(undoToday, Modifier.fillMaxWidth(), enabled = !writing) {
                Text(stringResource(R.string.habit_undo))
            }
        } else {
            if (layout.capacity > progress.completedDays) {
                Text(stringResource(R.string.habit_mark_hint), Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(markToday, Modifier.fillMaxWidth(), enabled = !writing) {
                Text(stringResource(R.string.habit_mark_today))
            }
        }
        FilledTonalButton(history, Modifier.fillMaxWidth()) { Text(stringResource(R.string.habit_history)) }
    }
}
