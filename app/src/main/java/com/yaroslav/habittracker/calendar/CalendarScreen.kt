package com.yaroslav.habittracker.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.yaroslav.habittracker.work.ReportMode
import com.yaroslav.habittracker.work.ShiftCard
import com.yaroslav.habittracker.work.ShiftEditorSheet
import com.yaroslav.habittracker.work.ShiftMarker
import com.yaroslav.habittracker.work.WORK_FIRST_DATE
import com.yaroslav.habittracker.work.WORK_LAST_DATE
import com.yaroslav.habittracker.work.WorkError
import com.yaroslav.habittracker.work.WorkLoad
import com.yaroslav.habittracker.work.WorkMark
import com.yaroslav.habittracker.work.WorkPeriod
import com.yaroslav.habittracker.work.WorkReportSheet
import com.yaroslav.habittracker.work.WorkShift
import com.yaroslav.habittracker.work.WorkSummary
import com.yaroslav.habittracker.work.WorkViewModel
import com.yaroslav.habittracker.work.continuingOn
import com.yaroslav.habittracker.work.startingOn
import com.yaroslav.habittracker.work.workMonthLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val russian = Locale.forLanguageTag("ru")
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM, EEEE", russian)
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

internal fun calendarDateLabel(date: LocalDate): String = dayFormat.format(date)

internal fun planClockLabel(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(clockFormat)

private enum class CalendarFilter(val label: Int) {
    All(R.string.work_all), Shifts(R.string.work_shifts), Plans(R.string.work_personal_plans),
}

@Composable
internal fun CalendarScreen(
    model: CalendarViewModel,
    work: WorkViewModel,
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

    val month = YearMonth.from(state.date)
    val monthPeriod = remember(month) { WorkPeriod.month(month) }
    val visibleShifts by key(monthPeriod) {
        val source = remember(monthPeriod, work) { work.observe(monthPeriod, includeOvernight = true) }
        source.collectAsStateWithLifecycle(WorkLoad.Loading)
    }
    val shifts = (visibleShifts as? WorkLoad.Ready)?.value.orEmpty()
    val templates by work.templates.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(CalendarFilter.All) }
    var shiftEditorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmWorked by rememberSaveable { mutableStateOf(false) }
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    val choice = work.reportChoice
    val period = choice.period(month)
    val report = if (period == monthPeriod) visibleShifts else key(period) {
        val source = remember(period, work) { work.observe(period) }
        val stateForPeriod by source.collectAsStateWithLifecycle(WorkLoad.Loading)
        stateForPeriod
    }
    val dayActions = remember { BringIntoViewRequester() }
    val scrollScope = rememberCoroutineScope()

    fun editShift(id: Long, worked: Boolean = false) {
        work.beginEdit()
        shiftEditorId = id
        confirmWorked = worked
    }

    ScreenContent(horizontalPadding = 5.dp) {
        Text(stringResource(R.string.work_tab), Modifier.padding(horizontal = 7.dp),
            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        if (activeTimer != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp)) { activeTimer() }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp)) {
            WorkSummary(choice, period, report) { reportOpen = true }
        }
        val entryDates = when (filter) {
            CalendarFilter.All -> agenda?.daysWithEntries.orEmpty()
            CalendarFilter.Shifts -> emptySet()
            CalendarFilter.Plans -> agenda?.datesWithPlans.orEmpty()
        }
        MonthPanel(
            selected = state.date,
            entryDates = entryDates,
            shifts = shifts,
            filter = filter,
            changeFilter = { filter = it },
            select = {
                model.select(it)
                scrollScope.launch { dayActions.bringIntoView() }
            },
            moveMonth = model::moveMonth,
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(calendarDateLabel(state.date), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Row(
                Modifier.bringIntoViewRequester(dayActions),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button({ editShift(0L) }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.work_add_shift), textAlign = TextAlign.Center)
                }
                OutlinedButton({ openSheet() }, Modifier.weight(1.18f), enabled = agenda != null) {
                    Text(stringResource(R.string.work_add_plan), textAlign = TextAlign.Center)
                }
            }
            if (filter != CalendarFilter.Plans) {
                when (visibleShifts) {
                    WorkLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    WorkLoad.Failed -> WorkError(work::retry)
                    is WorkLoad.Ready -> {
                        val day = state.date.toEpochDay()
                        val starts = shifts.startingOn(day)
                        val continues = shifts.continuingOn(day)
                        if (starts.isEmpty() && continues.isEmpty()) {
                            val emptyMessage = when {
                                filter == CalendarFilter.Shifts -> R.string.work_empty_shifts
                                agenda != null && agenda.plans.isEmpty() && agenda.sessions.isEmpty() &&
                                    agenda.marks.isEmpty() && agenda.progress.isEmpty() -> R.string.calendar_empty_day
                                else -> null
                            }
                            emptyMessage?.let {
                                Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        for (shift in continues + starts) {
                            ShiftCard(
                                shift,
                                continuation = shift.day != day,
                                goTo = { model.select(LocalDate.ofEpochDay(shift.day)) },
                                edit = { editShift(shift.id) },
                                worked = { editShift(shift.id, worked = true) },
                            )
                        }
                    }
                }
            }

            if (filter != CalendarFilter.Shifts) {
                when (state) {
                    is CalendarState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is CalendarState.Failed -> Panel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.calendar_load_error))
                            TextButton(onClick = model::retry) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    is CalendarState.Ready -> Agenda(
                        agenda = state.agenda,
                        personalOnly = filter == CalendarFilter.Plans,
                        edit = { if (it.repeatMask == 0) openSheet(it.id) else scopeId = it.id },
                        launchTimer = launchTimer,
                        allowStart = activeTimer == null,
                    )
                }
            }
        }
    }

    if (reportOpen) {
        WorkReportSheet(
            choice = choice,
            period = period,
            state = report,
            changeMode = { work.reportChoice = choice.copy(mode = it, year = choice.year ?: month.year) },
            move = { step ->
                if (choice.mode == ReportMode.Month) model.moveMonth(step)
                if (choice.mode == ReportMode.Year) {
                    val year = (period.from.year + step).toInt().coerceIn(WORK_FIRST_DATE.year, WORK_LAST_DATE.year)
                    work.reportChoice = choice.copy(year = year)
                }
            },
            applyRange = { work.reportChoice = choice.copy(range = it) },
            retry = work::retry,
            openDay = { reportOpen = false; filter = CalendarFilter.All; model.select(it) },
            close = { reportOpen = false },
        )
    }
    shiftEditorId?.let { id ->
        val existing = shifts.find { it.id == id }
        if (id == 0L || existing != null) {
            ShiftEditorSheet(
                model = work, date = state.date, existing = existing, confirmWorked = confirmWorked,
                templates = templates, dismiss = { shiftEditorId = null },
                saved = {
                    shiftEditorId = null
                    filter = CalendarFilter.All
                    model.select(it)
                },
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
    personalOnly: Boolean,
    edit: (DayPlan) -> Unit,
    launchTimer: (DayPlan) -> Unit,
    allowStart: Boolean,
) {
    if (!personalOnly) {
        for (progress in agenda.progress) CalendarProgressCard(progress)
    }
    if (personalOnly && agenda.plans.isEmpty()) {
        Text(stringResource(R.string.work_empty_plans), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (agenda.plans.isNotEmpty()) {
        val names = agenda.habits.associate { it.id to it.name }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(R.string.work_personal_plans)
            for (plan in agenda.plans) {
                PlanCard(plan, names[plan.habitId], allowStart, { edit(plan) }, { launchTimer(plan) })
            }
        }
    }
    if (personalOnly || (agenda.sessions.isEmpty() && agenda.marks.isEmpty())) return
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthPanel(
    selected: LocalDate,
    entryDates: Set<Long>,
    shifts: List<WorkShift>,
    filter: CalendarFilter,
    changeFilter: (CalendarFilter) -> Unit,
    select: (LocalDate) -> Unit,
    moveMonth: (Long) -> Unit,
) {
    val month = YearMonth.from(selected)
    val days = month.atDay(1).dayOfWeek.value - 1
    val rows = (days + month.lengthOfMonth() + 6) / 7
    val first = month.atDay(1).minusDays(days.toLong())
    val weekdays = stringArrayResource(R.array.calendar_weekdays)
    val marksByDay = remember(shifts) {
        buildMap<Long, MutableSet<WorkMark>> {
            for (shift in shifts) {
                getOrPut(shift.day) { linkedSetOf() }.add(if (shift.night) WorkMark.Night else WorkMark.Day)
                if (shift.hours.crossesMidnight) getOrPut(shift.day + 1) { linkedSetOf() }.add(WorkMark.Carry)
            }
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { moveMonth(-1) },
                    enabled = month.year != CALENDAR_YEARS.first || month.monthValue > 1,
                ) {
                    AppIcon(R.drawable.ic_back, description = stringResource(R.string.calendar_previous_month))
                }
                Text(workMonthLabel(selected), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { moveMonth(1) },
                    enabled = month.year != CALENDAR_YEARS.last || month.monthValue < 12,
                ) {
                    AppIcon(R.drawable.ic_next, description = stringResource(R.string.calendar_next_month))
                }
            }
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in CalendarFilter.entries) {
                    FilterChip(
                        selected = filter == item,
                        onClick = { changeFilter(item) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                for (weekday in weekdays) {
                    Text(weekday, Modifier.weight(1f), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.selectableGroup()) {
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (column in 0 until 7) {
                            val date = first.plusDays((row * 7 + column).toLong())
                            val day = date.toEpochDay()
                            MonthDay(
                                date,
                                selected = date == selected,
                                inMonth = YearMonth.from(date) == month,
                                hasEntries = day in entryDates,
                                marks = if (filter == CalendarFilter.Plans) emptySet() else marksByDay[day].orEmpty(),
                                select = { select(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            WorkLegend(filter)
        }
    }
}

@Composable
private fun MonthDay(
    date: LocalDate,
    selected: Boolean,
    inMonth: Boolean,
    hasEntries: Boolean,
    marks: Set<WorkMark>,
    select: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = calendarDateLabel(date)
    val descriptions = marks.map { stringResource(markDescription(it)) } +
        if (hasEntries) listOf(stringResource(R.string.work_calendar_personal)) else emptyList()
    val description = if (descriptions.isEmpty()) label
        else stringResource(R.string.work_day_description, label, descriptions.joinToString(", "))
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .selectable(selected, enabled = date.year in CALENDAR_YEARS, onClick = select, role = Role.Button)
            .semantics { contentDescription = description }
            .padding(1.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(when {
                selected -> colors.primary
                date == LocalDate.now() -> colors.primaryContainer
                inMonth -> colors.surfaceVariant.copy(alpha = 0.55f)
                else -> colors.surface
            }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            Text(date.dayOfMonth.toString(),
                color = if (selected) colors.onPrimary else if (inMonth) colors.onSurface else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.heightIn(min = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (mark in marks.take(2)) ShiftMarker(mark, Modifier.size(12.dp))
                if (hasEntries) Box(Modifier.size(4.dp).background(
                    if (selected) colors.onPrimary else colors.primary, CircleShape,
                ))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkLegend(filter: CalendarFilter) {
    FlowRow(Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (filter != CalendarFilter.Plans) {
            for (mark in WorkMark.entries) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShiftMarker(mark, Modifier.size(13.dp))
                    Text(stringResource(when (mark) {
                        WorkMark.Day -> R.string.work_day_shift
                        WorkMark.Night -> R.string.work_night_shift
                        WorkMark.Carry -> R.string.work_continues
                    }), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (filter != CalendarFilter.Shifts) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(stringResource(R.string.work_calendar_personal), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun markDescription(mark: WorkMark) = when (mark) {
    WorkMark.Day -> R.string.work_calendar_day_shift
    WorkMark.Night -> R.string.work_calendar_night_shift
    WorkMark.Carry -> R.string.work_continues
}
