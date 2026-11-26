package com.yaroslav.habittracker.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.CalendarViewModel
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.calendar.DayPlanProgress
import com.yaroslav.habittracker.calendar.PlanProgressState
import com.yaroslav.habittracker.calendar.planClockLabel
import com.yaroslav.habittracker.habits.FirstHabitCard
import com.yaroslav.habittracker.habits.HabitDayProgress
import com.yaroslav.habittracker.habits.HabitProgress
import com.yaroslav.habittracker.habits.HabitsState
import com.yaroslav.habittracker.habits.HabitsViewModel
import com.yaroslav.habittracker.habits.Mosaic
import com.yaroslav.habittracker.habits.Screen
import com.yaroslav.habittracker.habits.Tab
import com.yaroslav.habittracker.habits.mosaicLayout
import com.yaroslav.habittracker.habits.russianLocale
import com.yaroslav.habittracker.tasks.TaskCard
import com.yaroslav.habittracker.tasks.TaskState
import com.yaroslav.habittracker.tasks.TaskViewModel
import com.yaroslav.habittracker.timer.ActiveTimer
import com.yaroslav.habittracker.timer.FocusSession
import com.yaroslav.habittracker.timer.TimerStartRequest
import com.yaroslav.habittracker.timer.TimerViewModel
import com.yaroslav.habittracker.timer.TodayTimerBar
import com.yaroslav.habittracker.timer.suggestedPlanSeconds
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val todayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", russianLocale)
private val monthShort = DateTimeFormatter.ofPattern("LLL", russianLocale)

@Composable
internal fun TodayScreen(
    state: HabitsState.Loaded,
    habits: HabitsViewModel,
    calendar: CalendarViewModel,
    tasks: TaskViewModel,
    timer: TimerViewModel,
    active: ActiveTimer?,
    openTimer: () -> Unit,
    startTimer: (TimerStartRequest) -> Unit,
) {
    val date = state.date
    val day = date.toEpochDay()
    val taskState by tasks.tasks.collectAsStateWithLifecycle()
    var plansAttempt by remember(date) { mutableIntStateOf(0) }
    val planState by key(date) {
        val plans = remember(calendar, date, plansAttempt) { calendar.observePlanProgress(date) }
        plans.collectAsStateWithLifecycle(initialValue = PlanProgressState.Loading)
    }
    val plans = (planState as? PlanProgressState.Ready)?.entries.orEmpty()
    val activeToday = active?.takeIf { running ->
        Instant.ofEpochMilli(running.startedAtMillis).atZone(ZoneId.of(running.zoneId))
            .toLocalDate() == date
    }
    val linkedPlan = plans.firstOrNull { it.plan.id == activeToday?.planId }?.plan
    val saved = timer.saved?.takeIf { session ->
        session.day == day && session.planId != null &&
            state.habits.any { habit -> habit.sessions.any { it.id == session.id } }
    }
    val linkedHabits = plans.mapNotNull { it.plan.habitId }.toSet()
    val shownHabits = state.habits.filter { progress ->
        progress.habit.isScheduled(date.dayOfWeek) || progress.habit.id in linkedHabits
    }

    fun openCalendar() {
        calendar.select(date)
        habits.selectTab(Tab.Calendar)
    }

    ScreenContent {
        TodayHeader(date)
        if (active != null) {
            TodayTimerBar(timer, active, linkedPlan, state.habits, open = openTimer)
        } else if (saved != null) {
            SavedSessionBanner(saved, state.habits)
        }
        timer.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(R.string.calendar_plans)
            when (val current = planState) {
                PlanProgressState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                PlanProgressState.Failed -> TodaySectionError(R.string.calendar_load_error) { plansAttempt++ }
                is PlanProgressState.Ready -> {
                    if (current.entries.isEmpty()) {
                        TodayEmptyCard(R.string.today_plans_empty, R.string.calendar_back, ::openCalendar)
                    }
                    for (entry in current.entries) {
                        val planTimer = activeToday?.takeIf { it.planId == entry.plan.id }
                        TodayPlanCard(entry, planTimer, !timer.busy && active == null) { plan ->
                            val habit = state.habits.firstOrNull { it.habit.id == plan.habitId }
                            val seconds = suggestedPlanSeconds(plan, habit, entry.recordedMillis)
                            startTimer(TimerStartRequest(seconds * 1_000L, planId = plan.id))
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeading(R.string.habits, Modifier.weight(1f))
                if (active == null) {
                    FilledTonalIconButton(onClick = openTimer) {
                        AppIcon(R.drawable.ic_timer_status, description = stringResource(R.string.timer))
                    }
                }
                if (state.habits.isNotEmpty()) {
                    FilledTonalIconButton(onClick = { habits.open(Screen.HabitEditor()) }) {
                        AppIcon(R.drawable.ic_add, description = stringResource(R.string.create_habit))
                    }
                }
            }
            if (state.habits.isEmpty()) {
                FirstHabitCard { habits.open(Screen.HabitEditor()) }
            } else {
                if (state.scheduled.count { !it.completedToday && it.habit.targetMinutes == null } >= 2) {
                    OutlinedButton({ habits.open(Screen.BulkMark) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bulk_mark))
                    }
                }
                if (shownHabits.isEmpty()) {
                    Text(stringResource(R.string.no_habits_today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (progress in shownHabits) {
                    TodayHabitCard(progress) { habits.open(Screen.HabitDetails(progress.habit.id)) }
                }
                TextButton({ habits.open(Screen.AllHabits) }, Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.all_habits))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(R.string.today_tasks)
            when (val current = taskState) {
                TaskState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                TaskState.Failed -> TodaySectionError(R.string.task_load_error, tasks::retry)
                is TaskState.Ready -> {
                    val due = current.items.filter { it.deadlineDay == day }
                    if (due.isEmpty()) {
                        TodayEmptyCard(R.string.today_tasks_empty, R.string.today_tasks_open) {
                            habits.selectTab(Tab.Tasks)
                        }
                    }
                    for (task in due) {
                        TaskCard(task, enabled = !tasks.busy, today = true) { tasks.toggle(task) }
                    }
                    if (tasks.failed) {
                        Text(stringResource(R.string.task_save_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        OutlinedButton({ habits.open(Screen.Week) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.week_title))
        }
    }
}

@Composable
private fun TodayHeader(date: LocalDate) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.today),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                date.format(todayFormat).replaceFirstChar { it.titlecase(russianLocale) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Surface(
            modifier = Modifier.clearAndSetSemantics {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.format(monthShort).trimEnd('.').uppercase(russianLocale), style = MaterialTheme.typography.labelSmall)
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun TodayPlanCard(
    entry: DayPlanProgress,
    active: ActiveTimer?,
    canStart: Boolean,
    start: (DayPlan) -> Unit,
) {
    val plan = entry.plan
    val recorded = entry.recordedMillis
    val hasFinished = recorded > 0 && (plan.durationMinutes == null || recorded >= plan.durationMinutes * 60_000L)
    val hint = when {
        recorded > 0 -> stringResource(R.string.today_plan_recorded, durationLabel(recorded))
        plan.durationMinutes != null -> stringResource(R.string.calendar_duration_minutes, plan.durationMinutes)
        else -> null
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(planClockLabel(plan.minuteOfDay), Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (hint != null && !hasFinished) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                active != null -> PlanStatus(stringResource(if (active.running) R.string.today_plan_running else R.string.today_plan_paused))
                hasFinished && hint != null -> PlanStatus(hint)
                plan.habitId != null -> {
                    val label = if (recorded > 0) R.string.today_plan_start_again else R.string.timer_start
                    val description = stringResource(R.string.today_plan_start_description, plan.title)
                    Button(
                        onClick = { start(plan) },
                        modifier = Modifier.semantics { contentDescription = description },
                        enabled = canStart,
                    ) { Text(stringResource(label)) }
                }
            }
        }
    }
}

@Composable
private fun PlanStatus(text: String) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TodayHabitCard(progress: HabitProgress, open: () -> Unit) {
    val target = progress.habit.targetMinutes
    val mosaicSize = if (target == null) 36.dp else 66.dp
    Panel(Modifier.fillMaxWidth(), onClick = open) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (target == null && progress.completedToday) {
                AppIcon(R.drawable.ic_check, Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(progress.habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                HabitDayProgress(progress)
                if (target != null) {
                    val remaining = target * 60_000L - progress.todayMillis
                    val label = if (progress.completedToday) stringResource(R.string.habit_goal_met)
                    else stringResource(R.string.today_goal_remaining, durationLabel(remaining))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mosaic(progress.completedDays, Modifier.size(mosaicSize), emphasizeLast = progress.completedToday)
                Text(
                    stringResource(R.string.mosaic_progress, progress.completedDays, mosaicLayout(progress.completedDays).capacity),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SavedSessionBanner(session: FocusSession, habits: List<HabitProgress>) {
    val addedCell = habits.any { it.earnedCellFrom(session) }
    Surface(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_check, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                stringResource(if (addedCell) R.string.today_timer_saved_cell else R.string.today_timer_saved,
                    durationLabel(session.durationMillis)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

internal fun HabitProgress.earnedCellFrom(session: FocusSession): Boolean {
    val target = (habit.targetMinutes ?: return false) * 60_000L
    if (!completedToday || session.day in marks || todayMillis - session.durationMillis >= target) return false
    return todayMillis >= target && sessions.any { it.id == session.id }
}

@Composable
private fun durationLabel(millis: Long): String = if (millis >= 60_000L) {
    stringResource(R.string.calendar_duration_minutes, millis / 60_000L)
} else {
    stringResource(R.string.calendar_duration_seconds, (millis / 1_000L).coerceAtLeast(1L))
}

@Composable
private fun TodayEmptyCard(message: Int, action: Int, open: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 14.dp)) {
            Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(open, Modifier.align(Alignment.End)) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun TodaySectionError(message: Int, retry: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
        }
    }
}
