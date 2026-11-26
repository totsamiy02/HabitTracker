package com.yaroslav.habittracker.timer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.calendar.planClockLabel
import com.yaroslav.habittracker.habits.HabitDayProgress
import com.yaroslav.habittracker.habits.HabitProgress
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import kotlinx.coroutines.delay

private const val TICK_MILLIS = 250L

@Composable
private fun timerReading(model: TimerViewModel, timer: ActiveTimer): TimerMoment {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reading by produceState(model.now(), timer, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = model.now()
            while (timer.running && timer.remainingAt(value) != 0L) {
                delay(TICK_MILLIS)
                value = model.now()
            }
        }
    }
    return reading
}

@Composable
internal fun ActiveTimerBar(
    model: TimerViewModel,
    timer: ActiveTimer,
    habits: List<HabitProgress>,
    open: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = timerReading(model, timer)
    val duration = timer.remainingAt(now) ?: timer.elapsedAt(now)
    val names = habits.filter { it.habit.id in timer.habitIds }.joinToString(", ") { it.habit.name }
    Panel(modifier.fillMaxWidth(), onClick = open) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(R.drawable.ic_timer_status)
            Column(Modifier.weight(1f)) {
                Text(
                    if (!timer.running) stringResource(R.string.timer_paused)
                    else names.ifBlank { stringResource(R.string.timer) },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Text(clockText(duration, timer.limitMillis != null), fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
            FilledTonalIconButton(onClick = { model.toggle(timer) }, enabled = !model.busy) {
                AppIcon(
                    if (timer.running) R.drawable.ic_timer_pause else R.drawable.ic_timer_play,
                    description = stringResource(if (timer.running) R.string.timer_pause else R.string.timer_resume),
                )
            }
        }
    }
}

@Composable
internal fun TimerScreen(
    model: TimerViewModel,
    active: ActiveTimer?,
    habits: List<HabitProgress>,
    initialHabit: Long?,
    initialPlan: DayPlan?,
    activePlan: DayPlan?,
    returnToCalendar: Boolean,
    startTimer: (TimerStartRequest) -> Unit,
    onSaved: (FocusSession) -> Unit,
) {
    val saved = model.saved
    when {
        active != null -> {
            val plan = activePlan?.takeIf { it.id == active.planId }
                ?: initialPlan?.takeIf { it.id == active.planId }
            RunningTimerScreen(model, active, habits, plan)
        }
        saved != null -> SavedTimerScreen(saved, habits, returnToCalendar && saved.planId != null) {
            onSaved(saved)
        }
        else -> {
            val targets = habits.filter { it.habit.targetMinutes != null || it.habit.id == initialPlan?.habitId }
            TimerSetupScreen(model, targets, initialHabit, initialPlan, startTimer)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerSetupScreen(
    model: TimerViewModel,
    habits: List<HabitProgress>,
    initialHabit: Long?,
    plan: DayPlan?,
    startTimer: (TimerStartRequest) -> Unit,
) {
    val plannedHabit = habits.firstOrNull { it.habit.id == plan?.habitId }
    val suggestedSeconds = suggestedPlanSeconds(plan, plannedHabit)
    var countdown by rememberSaveable { mutableStateOf(true) }
    var minutes by rememberSaveable(plan?.id) { mutableStateOf((suggestedSeconds / 60).toString()) }
    var seconds by rememberSaveable(plan?.id) {
        mutableStateOf((suggestedSeconds % 60).toString().padStart(2, '0'))
    }
    var selected by rememberSaveable(initialHabit) {
        mutableStateOf(initialHabit?.let { longArrayOf(it) } ?: longArrayOf())
    }


    val duration = (minutes.toLongOrNull() ?: 0) * 60_000L + (seconds.toLongOrNull() ?: 0) * 1000L
    val validDuration = duration in 1..MAX_SESSION_MINUTES * 60_000L &&
        (seconds.toIntOrNull() ?: 0) in 0..59

    ScreenContent(Modifier.imePadding()) {
        plan?.let { TimerPlanCaption(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(countdown, { countdown = true }, { Text(stringResource(R.string.timer_countdown)) })
            FilterChip(!countdown, { countdown = false }, { Text(stringResource(R.string.timer_stopwatch)) })
        }
        if (countdown) {
            Text(stringResource(R.string.timer_duration), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(minutes, { if (it.length <= 4 && it.all(Char::isDigit)) minutes = it },
                    Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.timer_minutes)) })
                OutlinedTextField(seconds, { if (it.length <= 2 && it.all(Char::isDigit)) seconds = it },
                    Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.timer_seconds)) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (preset in listOf(15, 25, 45, 60)) {
                    FilterChip(minutes.toIntOrNull() == preset && seconds.toIntOrNull() == 0,
                        { minutes = preset.toString(); seconds = "00" },
                        { Text(stringResource(R.string.timer_preset, preset)) })
                }
            }
        }
        Text(stringResource(R.string.timer_links), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Text(stringResource(if (habits.isEmpty()) R.string.timer_no_targets else R.string.timer_links_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (progress in habits) {
            val id = progress.habit.id
            val fromPlan = id == plan?.habitId
            val checked = fromPlan || id in selected
            val toggle: (() -> Unit)? = if (fromPlan) null else {
                { selected = (if (checked) selected.filter { it != id } else selected.toList() + id).toLongArray() }
            }
            Panel(Modifier.fillMaxWidth(), onClick = toggle) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(progress.habit.name, fontWeight = FontWeight.SemiBold)
                        HabitDayProgress(progress)
                    }
                }
            }
        }

        Button(
            onClick = {
                startTimer(TimerStartRequest(if (countdown) duration else null, selected.toSet(), plan?.id))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy && (!countdown || validDuration),
        ) {
            Text(stringResource(R.string.timer_start))
        }
        model.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningTimerScreen(
    model: TimerViewModel,
    timer: ActiveTimer,
    habits: List<HabitProgress>,
    plan: DayPlan?,
) {
    val now = timerReading(model, timer)
    val limit = timer.limitMillis
    val elapsed = timer.elapsedAt(now)
    val remaining = timer.remainingAt(now)
    val requestFinish = rememberTimerFinish(model, timer, elapsed)
    val fraction = if (limit != null) (requireNotNull(remaining).toDouble() / limit).toFloat()
        else (elapsed % 60_000L / 60_000f)
    val note = when {
        !timer.running -> stringResource(R.string.timer_paused)
        limit != null -> stringResource(R.string.timer_remaining, clockText(limit))
        else -> stringResource(R.string.timer_elapsed)
    }
    ScreenContent {
        plan?.let { TimerPlanCaption(it) }
        TimerDial(
            time = clockText(remaining ?: elapsed, countdown = limit != null),
            note = note,
            fraction = fraction,
            paused = !timer.running,
        )

        val linked = habits.filter { it.habit.id in timer.habitIds }
        if (linked.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.timer_links), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (target in linked) Text(target.habit.name, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Button({ model.toggle(timer) }, Modifier.fillMaxWidth(), enabled = !model.busy) {
            Text(stringResource(if (timer.running) R.string.timer_pause else R.string.timer_resume))
        }
        OutlinedButton(requestFinish, Modifier.fillMaxWidth(), enabled = !model.busy) {
            Text(stringResource(R.string.timer_finish))
        }
        model.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }

}


internal fun suggestedPlanSeconds(plan: DayPlan?, habit: HabitProgress?, recordedMillis: Long = 0L): Long {
    val planned = plan?.durationMinutes?.times(60_000L)
    val remaining = if (planned != null) {
        (planned - recordedMillis).takeIf { it > 0L } ?: planned
    } else {
        habit?.habit?.targetMinutes?.let { it * 60_000L - habit.todayMillis }
    }
    return if (remaining != null && remaining > 0L) (remaining + 999L) / 1_000L else 25 * 60L
}

@Composable
internal fun TodayTimerBar(
    model: TimerViewModel,
    timer: ActiveTimer,
    plan: DayPlan?,
    habits: List<HabitProgress>,
    open: () -> Unit,
) {
    val now = timerReading(model, timer)
    val elapsed = timer.elapsedAt(now)
    val finish = rememberTimerFinish(model, timer, elapsed)
    val remaining = timer.remainingAt(now)
    val limit = timer.limitMillis
    val progress = if (limit != null) (elapsed.toDouble() / limit).toFloat().coerceIn(0f, 1f)
        else (elapsed % 60_000L / 60_000f)
    val names = habits.filter { it.habit.id in timer.habitIds }.joinToString(", ") { it.habit.name }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.primary,
        contentColor = colors.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.today_open_timer), onClick = open)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.matchParentSize().clearAndSetSemantics {},
                        color = colors.onPrimary,
                        trackColor = colors.onPrimary.copy(alpha = 0.3f),
                        strokeWidth = 2.dp,
                    )
                    AppIcon(R.drawable.ic_timer_status, Modifier.size(17.dp), tint = colors.onPrimary)
                }
                Column {
                    Text(
                        if (timer.running) plan?.title ?: names.ifBlank { stringResource(R.string.timer) }
                        else stringResource(R.string.timer_paused),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        clockText(remaining ?: elapsed, countdown = remaining != null),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            IconButton(onClick = { model.toggle(timer) }, enabled = !model.busy) {
                AppIcon(
                    if (timer.running) R.drawable.ic_timer_pause else R.drawable.ic_timer_play,
                    description = stringResource(if (timer.running) R.string.timer_pause else R.string.timer_resume),
                    tint = colors.onPrimary,
                )
            }
            TextButton(
                onClick = finish,
                enabled = !model.busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.onPrimary,
                    disabledContentColor = colors.onPrimary.copy(alpha = 0.65f),
                ),
            ) { Text(stringResource(R.string.timer_finish), textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun rememberTimerFinish(model: TimerViewModel, timer: ActiveTimer, elapsed: Long): () -> Unit {
    var confirm by rememberSaveable(timer.id) { mutableStateOf(false) }
    var resumeOnCancel by rememberSaveable(timer.id) { mutableStateOf(false) }

    fun requestFinish() {
        resumeOnCancel = timer.running
        confirm = true
        if (timer.running) model.toggle(timer)
    }
    fun cancelFinish() {
        if (model.busy) return
        confirm = false
        if (resumeOnCancel && !timer.running) model.toggle(timer)
    }

    val fromNotification = model.confirmFromNotification
    LaunchedEffect(fromNotification, model.busy) {
        if (fromNotification && !model.busy) {
            model.clearConfirmation()
            resumeOnCancel = false
            confirm = true
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = ::cancelFinish,
            title = { Text(stringResource(R.string.timer_confirm_finish)) },
            text = { Text(stringResource(R.string.timer_to_save, clockText(elapsed))) },
            confirmButton = {
                TextButton(onClick = { model.finish(timer); confirm = false }, enabled = !model.busy) {
                    Text(stringResource(R.string.timer_save_session))
                }
            },
            dismissButton = {
                TextButton(onClick = ::cancelFinish, enabled = !model.busy) {
                    Text(stringResource(R.string.timer_back_to_run))
                }
            },
        )
    }

    return ::requestFinish
}

@Composable
private fun TimerPlanCaption(plan: DayPlan) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_calendar)
            Text(
                stringResource(R.string.calendar_timer_plan, plan.title, planClockLabel(plan.minuteOfDay)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TimerDial(time: String, note: String, fraction: Float, paused: Boolean) {
    val colors = MaterialTheme.colorScheme
    val progress by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "timer-ring",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 268.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val width = 8.dp.toPx()
                val margin = width / 2 + 6.dp.toPx()
                val bounds = Size(size.width - margin * 2, size.height - margin * 2)
                val top = Offset(margin, margin)
                drawCircle(colors.surface, radius = bounds.width / 2)
                drawArc(colors.outlineVariant, -90f, 360f, false, top, bounds, style = Stroke(width))
                if (progress > 0f) {
                    drawArc(
                        if (paused) colors.primary.copy(alpha = 0.55f) else colors.primary,
                        -90f, progress * 360f, false, top, bounds,
                        style = Stroke(width, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = time,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                    label = "timer-digits",
                ) { value ->
                    Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        style = if (value.length > 6) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium)
                }
                Text(note, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SavedTimerScreen(
    session: FocusSession,
    habits: List<HabitProgress>,
    returnToCalendar: Boolean,
    done: () -> Unit,
) {
    ScreenContent {
        AppIcon(R.drawable.ic_check, Modifier.size(52.dp))
        Text(stringResource(R.string.timer_saved), style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.timer_saved_time, clockText(session.durationMillis)))
        for (habit in habits.filter { progress -> progress.sessions.any { it.id == session.id } }) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(habit.habit.name, fontWeight = FontWeight.Bold)
                    HabitDayProgress(habit)
                }
            }
        }
        Button(done, Modifier.fillMaxWidth()) {
            Text(stringResource(if (returnToCalendar) R.string.calendar_back else R.string.timer_back_to_today))
        }
    }
}
