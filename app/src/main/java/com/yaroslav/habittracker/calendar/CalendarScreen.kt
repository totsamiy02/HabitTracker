package com.yaroslav.habittracker.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.habits.HabitDayProgress
import com.yaroslav.habittracker.habits.HabitProgress
import com.yaroslav.habittracker.habits.Mosaic
import com.yaroslav.habittracker.habits.mosaicLayout
import com.yaroslav.habittracker.planning.PlanEditScope
import com.yaroslav.habittracker.planning.reminderLabel
import com.yaroslav.habittracker.planning.repeatLabel
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val russian = Locale.forLanguageTag("ru")
private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", russian)
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM, EEEE", russian)
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

internal fun calendarDateLabel(date: LocalDate): String = dayFormat.format(date)

internal fun planClockLabel(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(clockFormat)

@Composable
internal fun CalendarScreen(
    model: CalendarViewModel,
    state: CalendarState,
    activeTimer: (@Composable () -> Unit)?,
    launchTimer: (DayPlan) -> Unit,
) {
    val agenda = (state as? CalendarState.Ready)?.agenda
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var scopeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var scope by rememberSaveable { mutableStateOf(PlanEditScope.Single) }

    fun openSheet(id: Long? = null, editScope: PlanEditScope = PlanEditScope.Single) {
        model.beginEdit()
        editingId = id
        scope = editScope
        scopeId = null
        sheetOpen = true
    }

    ScreenContent {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.calendar_tab),
                Modifier.weight(1f),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            FilledTonalIconButton(onClick = { openSheet() }, enabled = agenda != null) {
                AppIcon(R.drawable.ic_add, description = stringResource(R.string.calendar_add_plan))
            }
        }

        activeTimer?.invoke()

        MonthPanel(
            selected = state.date,
            entryDates = agenda?.daysWithEntries.orEmpty(),
            select = model::select,
            moveMonth = model::moveMonth,
        )

        Text(
            calendarDateLabel(state.date),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        when (state) {
            is CalendarState.Loading -> Box(
                Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is CalendarState.Failed -> Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.calendar_load_error))
                    TextButton(onClick = model::retry) { Text(stringResource(R.string.retry)) }
                }
            }
            is CalendarState.Ready -> Agenda(
                agenda = state.agenda,
                add = { openSheet() },
                edit = { if (it.repeatMask == 0) openSheet(it.id) else scopeId = it.id },
                launchTimer = launchTimer,
                allowStart = activeTimer == null,
            )
        }
    }

    scopeId?.let { id ->
        AlertDialog(
            onDismissRequest = { scopeId = null },
            title = { Text(stringResource(R.string.plan_repeat_scope)) },
            text = {
                Column {
                    TextButton(onClick = { openSheet(id, PlanEditScope.Single) }) {
                        Text(stringResource(R.string.plan_repeat_single))
                    }
                    TextButton(onClick = { openSheet(id, PlanEditScope.Following) }) {
                        Text(stringResource(R.string.plan_repeat_following))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scopeId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (sheetOpen && agenda != null) {
        val existing = agenda.plans.firstOrNull { it.id == editingId }
        if (editingId == null || existing != null) {
            PlanEditorSheet(
                initial = existing,
                date = agenda.date,
                scope = scope,
                habits = agenda.habits,
                writing = model.writing,
                failed = model.saveFailed,
                dismiss = { sheetOpen = false },
                save = { plan -> model.save(plan, existing?.day, scope) { sheetOpen = false } },
            )
        }
    }
}

@Composable
private fun Agenda(
    agenda: CalendarDay,
    add: () -> Unit,
    edit: (DayPlan) -> Unit,
    launchTimer: (DayPlan) -> Unit,
    allowStart: Boolean,
) {
    if (agenda.plans.isEmpty() && agenda.sessions.isEmpty() && agenda.marks.isEmpty()) {
        Panel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(stringResource(R.string.calendar_empty_day), fontWeight = FontWeight.SemiBold)
                FilledTonalButton(onClick = add) { Text(stringResource(R.string.calendar_add_plan)) }
            }
        }
        return
    }

    for (progress in agenda.progress) {
        CalendarProgressCard(progress)
    }
    if (agenda.plans.isNotEmpty()) {
        val names = agenda.habits.associate { it.id to it.name }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(R.string.calendar_plans)
            for (plan in agenda.plans) {
                PlanCard(plan, names[plan.habitId], allowStart, { edit(plan) }, { launchTimer(plan) })
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(R.string.calendar_actual)
        for (entry in agenda.sessions) SessionCard(entry)
        for (habit in agenda.marks) {
            Panel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(R.drawable.ic_check)
                    Text(habit.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    CalendarChip(stringResource(R.string.calendar_mark))
                }
            }
        }
        if (agenda.sessions.isEmpty() && agenda.marks.isEmpty()) {
            Text(stringResource(R.string.calendar_no_records), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun CalendarProgressCard(progress: HabitProgress) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(progress.habit.name, fontWeight = FontWeight.Bold)
                HabitDayProgress(progress)
                Text(
                    stringResource(
                        R.string.mosaic_progress,
                        progress.completedDays,
                        mosaicLayout(progress.completedDays).capacity,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Mosaic(progress.completedDays, Modifier.size(54.dp), emphasizeLast = progress.completedToday)
        }
    }
}

@Composable
private fun PlanCard(
    plan: DayPlan,
    habitName: String?,
    allowStart: Boolean,
    edit: () -> Unit,
    start: () -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.calendar_event_title, planClockLabel(plan.minuteOfDay), plan.title),
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                CalendarChip(stringResource(R.string.calendar_plan_label))
            }
            if (habitName != null) {
                Text(
                    stringResource(R.string.calendar_linked_habit, habitName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val duration = plan.durationMinutes?.let { stringResource(R.string.calendar_duration_minutes, it) }
            val repeat = repeatLabel(plan.repeatMask).takeIf { plan.repeatMask != 0 }
            val reminder = reminderLabel(plan.reminderMinutes).takeIf { plan.reminderMinutes != null }
            val details = listOfNotNull(duration, repeat, reminder)
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (habitName != null) {
                    TextButton(onClick = start, enabled = allowStart, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.calendar_start_timer), textAlign = TextAlign.Center)
                    }
                }
                TextButton(onClick = edit) { Text(stringResource(R.string.calendar_edit)) }
            }
        }
    }
}

@Composable
private fun SessionCard(entry: CalendarSession) {
    val session = entry.session
    val names = entry.habits.joinToString(", ") { it.name }
    val name = names.ifEmpty { stringResource(R.string.calendar_session) }
    val time = session.startedAtMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clockFormat)
    }
    val durationMinutes = session.durationMillis / 60_000L
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    time?.let { stringResource(R.string.calendar_event_title, it, name) } ?: name,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                CalendarChip(
                    if (durationMinutes > 0) {
                        stringResource(R.string.calendar_duration_minutes, durationMinutes)
                    } else {
                        stringResource(R.string.calendar_duration_seconds, maxOf(1L, session.durationMillis / 1_000))
                    },
                    actual = true,
                )
            }
            Text(
                stringResource(
                    when {
                        time == null -> R.string.time_manual
                        session.planId != null -> R.string.calendar_session_from_plan
                        else -> R.string.calendar_session
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarChip(label: String, actual: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        modifier = Modifier.background(
            if (actual) colors.tertiaryContainer else colors.primaryContainer,
            CircleShape,
        ).padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (actual) colors.onTertiaryContainer else colors.onPrimaryContainer,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun MonthPanel(
    selected: LocalDate,
    entryDates: Set<Long>,
    select: (LocalDate) -> Unit,
    moveMonth: (Long) -> Unit,
) {
    val month = YearMonth.from(selected)
    val days = month.atDay(1).dayOfWeek.value - 1
    val rows = (days + month.lengthOfMonth() + 6) / 7
    val weekdays = stringArrayResource(R.array.calendar_weekdays)
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { moveMonth(-1) },
                    enabled = month.year != CALENDAR_YEARS.first || month.monthValue > 1,
                ) {
                    AppIcon(R.drawable.ic_back, description = stringResource(R.string.calendar_previous_month))
                }
                Text(
                    month.format(monthFormat).replaceFirstChar { it.titlecase(russian) },
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = { moveMonth(1) },
                    enabled = month.year != CALENDAR_YEARS.last || month.monthValue < 12,
                ) {
                    AppIcon(R.drawable.ic_next, description = stringResource(R.string.calendar_next_month))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                for (weekday in weekdays) {
                    Text(
                        weekday,
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(Modifier.selectableGroup()) {
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (column in 0 until 7) {
                            val day = row * 7 + column - days + 1
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.atDay(day)
                                MonthDay(
                                    date,
                                    selected = date == selected,
                                    hasEntries = date.toEpochDay() in entryDates,
                                    select = { select(date) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Box(Modifier.weight(1f).heightIn(min = 42.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDay(
    date: LocalDate,
    selected: Boolean,
    hasEntries: Boolean,
    select: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = calendarDateLabel(date)
    val description = if (hasEntries) stringResource(R.string.calendar_day_with_entries, label) else label
    Box(
        modifier
            .heightIn(min = 42.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.primary else colors.surface)
            .selectable(selected, onClick = select, role = Role.Button)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            color = if (selected) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (hasEntries) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp)
                    .background(if (selected) colors.onPrimary else colors.primary, CircleShape),
            )
        }
    }
}
