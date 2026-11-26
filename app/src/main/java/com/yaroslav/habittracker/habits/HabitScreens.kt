package com.yaroslav.habittracker.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import com.yaroslav.habittracker.ui.theme.HabitTrackerTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val russian = Locale.forLanguageTag("ru-RU")
private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", russian)

@Composable
internal fun TodayScreen(
    state: HabitsState.Loaded,
    create: () -> Unit,
    open: (Long) -> Unit,
) {
    ScreenContent {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold)
            Text(
                state.date.format(dateFormat).replaceFirstChar { it.titlecase(russian) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.habits.isEmpty()) {
            FirstHabitCard(create)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.habits), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                FilledTonalIconButton(onClick = create) {
                    AppIcon(R.drawable.ic_add, description = stringResource(R.string.create_habit))
                }
            }
            for (progress in state.habits) {
                HabitCard(progress) { open(progress.habit.id) }
            }
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
            Button(onClick = create, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.create_habit))
            }
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
                Text(
                    stringResource(if (progress.completedToday) R.string.habit_done else R.string.habit_pending),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                val layout = mosaicLayout(progress.completedDays)
                Text(
                    stringResource(R.string.mosaic_progress, progress.completedDays, layout.capacity),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Mosaic(progress.completedDays, Modifier.size(68.dp))
            AppIcon(R.drawable.ic_next, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun CreateHabitScreen(saving: Boolean, save: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = { Text(stringResource(R.string.habit_name)) },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    minLines = 3,
                    label = { Text(stringResource(R.string.habit_description)) },
                    shape = RoundedCornerShape(14.dp),
                )
            }
            Button(
                onClick = { save(name, description) },
                enabled = name.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                Text(stringResource(R.string.create))
            }
        }
    }
}

@Composable
internal fun HabitDetailsScreen(progress: HabitProgress, marking: Boolean, markToday: () -> Unit) {
    val layout = mosaicLayout(progress.completedDays)

    ScreenContent {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(progress.habit.name, style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold)
            if (progress.habit.description.isNotBlank()) {
                Text(progress.habit.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.mosaic), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.mosaic_progress, progress.completedDays, layout.capacity),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                Mosaic(
                    completedDays = progress.completedDays,
                    modifier = Modifier.widthIn(max = minOf(51.dp * layout.side, 400.dp)).fillMaxWidth(),
                    highlightNext = !progress.completedToday && layout.capacity > progress.completedDays,
                    emphasizeLast = progress.completedToday,
                    onMarkToday = if (marking || progress.completedToday) null else markToday,
                )
            }
        }

        if (progress.completedToday) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(R.drawable.ic_check)
                    Text(stringResource(R.string.habit_done_today), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else {
            if (layout.capacity > progress.completedDays) {
                Text(
                    stringResource(R.string.habit_mark_hint),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = markToday, enabled = !marking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.habit_mark_today))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FirstHabitPreview() {
    HabitTrackerTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            TodayScreen(HabitsState.Loaded(LocalDate.of(2026, 11, 26), emptyList()), {}, {})
        }
    }
}
