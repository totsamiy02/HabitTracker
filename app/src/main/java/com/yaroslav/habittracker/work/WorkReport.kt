package com.yaroslav.habittracker.work

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.CalendarDateDialog
import com.yaroslav.habittracker.calendar.calendarDateLabel
import com.yaroslav.habittracker.habits.russianLocale
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal enum class ReportMode(@param:StringRes val label: Int) {
    Month(R.string.work_report_month), Year(R.string.work_report_year), Range(R.string.work_report_range),
}

internal data class ReportChoice(
    val mode: ReportMode = ReportMode.Month,
    val year: Int? = null,
    val range: WorkPeriod? = null,
) {
    fun period(month: YearMonth): WorkPeriod = when (mode) {
        ReportMode.Month -> WorkPeriod.month(month)
        ReportMode.Year -> WorkPeriod.year(year ?: month.year)
        ReportMode.Range -> range ?: WorkPeriod.month(month)
    }
}

private val workDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", russianLocale)
private val workMonthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", russianLocale)

internal fun workMonthLabel(date: LocalDate) =
    date.format(workMonthFormat).replaceFirstChar { it.titlecase(russianLocale) }

@Composable
private fun periodLabel(choice: ReportChoice, period: WorkPeriod): String = when (choice.mode) {
    ReportMode.Month -> workMonthLabel(period.from)
    ReportMode.Year -> stringResource(R.string.work_year_label, period.from.year)
    ReportMode.Range -> workRangeLabel(period)
}

@Composable
private fun workRangeLabel(period: WorkPeriod): String =
    stringResource(R.string.work_range_label, period.from.format(workDateFormat), period.through.format(workDateFormat))

@Composable
internal fun workMoney(kopecks: Long): String {
    val format = remember {
        NumberFormat.getNumberInstance(russianLocale).apply { maximumFractionDigits = 2 }
    }
    return stringResource(R.string.work_amount, format.format(BigDecimal.valueOf(kopecks, 2)))
}

@Composable
internal fun workDuration(minutes: Long): String = when {
    minutes < 60L -> stringResource(R.string.calendar_duration_minutes, minutes)
    minutes % 60L == 0L -> stringResource(R.string.work_duration_hours, minutes / 60L)
    else -> stringResource(R.string.work_duration_hours_minutes, minutes / 60L, minutes % 60L)
}

@Composable
private fun totalLabel(totals: ShiftTotals): String = stringResource(
    R.string.work_total_line,
    stringResource(R.string.work_shift_count, totals.count),
    workDuration(totals.minutes),
    if (totals.missingRates == totals.count && totals.count > 0) stringResource(R.string.work_no_rate)
    else workMoney(totals.kopecks),
)

@Composable
internal fun WorkSummary(
    choice: ReportChoice,
    period: WorkPeriod,
    state: WorkLoad<List<WorkShift>>,
    open: () -> Unit,
) {
    Panel(Modifier.fillMaxWidth(), onClick = open) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.work_period_summary, periodLabel(choice, period)), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.work_select_period), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                }
                when (state) {
                    is WorkLoad.Ready -> WorkTotalsLines(workTotals(state.value, period))
                    WorkLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    WorkLoad.Failed -> Text(stringResource(R.string.work_load_error))
                }
            }
        }
    }
}

@Composable
private fun WorkTotalsLines(totals: WorkTotals) {
    WorkTotalLine(R.string.work_worked, totals.worked, emphasized = true)
    WorkTotalLine(R.string.work_planned, totals.planned)
    if (totals.worked.missingRates + totals.planned.missingRates > 0) {
        Text(stringResource(R.string.work_missing_rates), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WorkTotalLine(@StringRes title: Int, totals: ShiftTotals, emphasized: Boolean = false) {
    Text(
        stringResource(R.string.work_card_calculation, stringResource(title), totalLabel(totals)),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WorkReportSheet(
    choice: ReportChoice,
    period: WorkPeriod,
    state: WorkLoad<List<WorkShift>>,
    changeMode: (ReportMode) -> Unit,
    move: (Long) -> Unit,
    applyRange: (WorkPeriod) -> Unit,
    retry: () -> Unit,
    openDay: (LocalDate) -> Unit,
    close: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.work_report), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                for (mode in ReportMode.entries) {
                    FilterChip(selected = mode == choice.mode, onClick = { changeMode(mode) },
                        label = { Text(stringResource(mode.label)) })
                }
            }
            if (choice.mode == ReportMode.Range) {
                WorkRangePicker(period, applyRange)
                Text(periodLabel(choice, period), style = MaterialTheme.typography.labelLarge)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { move(-1) }, enabled = period.from > WORK_FIRST_DATE) {
                        AppIcon(R.drawable.ic_back, description = stringResource(R.string.work_previous_period))
                    }
                    Text(periodLabel(choice, period), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { move(1) }, enabled = period.through < WORK_LAST_DATE) {
                        AppIcon(R.drawable.ic_next, description = stringResource(R.string.work_next_period))
                    }
                }
            }
            when (state) {
                WorkLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                WorkLoad.Failed -> WorkError(retry)
                is WorkLoad.Ready -> WorkReportDetails(state.value, period, choice.mode == ReportMode.Year, openDay)
            }
        }
    }
}

@Composable
private fun WorkRangePicker(period: WorkPeriod, applyRange: (WorkPeriod) -> Unit) {
    var from by rememberSaveable(period.from.toEpochDay()) { mutableLongStateOf(period.from.toEpochDay()) }
    var through by rememberSaveable(period.through.toEpochDay()) { mutableLongStateOf(period.through.toEpochDay()) }
    var pickStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val valid = through >= from
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton({ pickStart = true }, Modifier.weight(1f)) {
                Text(stringResource(R.string.work_period_start, LocalDate.ofEpochDay(from).format(workDateFormat)),
                    textAlign = TextAlign.Center)
            }
            OutlinedButton({ pickStart = false }, Modifier.weight(1f)) {
                Text(stringResource(R.string.work_period_end, LocalDate.ofEpochDay(through).format(workDateFormat)),
                    textAlign = TextAlign.Center)
            }
        }
        if (!valid) Text(stringResource(R.string.work_invalid_range), color = MaterialTheme.colorScheme.error)
        Button(
            onClick = { applyRange(WorkPeriod(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(through))) },
            modifier = Modifier.align(Alignment.End),
            enabled = valid,
        ) { Text(stringResource(R.string.work_show_totals)) }
    }
    pickStart?.let { start ->
        CalendarDateDialog(if (start) from else through, dismiss = { pickStart = null }) { selected ->
            if (start) from = selected else through = selected
            pickStart = null
        }
    }
}

@Composable
private fun WorkReportDetails(shifts: List<WorkShift>, period: WorkPeriod, year: Boolean, openDay: (LocalDate) -> Unit) {
    val monthly = year || ChronoUnit.DAYS.between(period.from, period.through) > 62
    val groups = remember(shifts, period, monthly) { workGroups(shifts, period, monthly) }
    val totals = remember(shifts, period) { workTotals(shifts, period) }
    var selectedKey by rememberSaveable(period.from.toEpochDay(), period.through.toEpochDay(), monthly) {
        mutableLongStateOf(groups.lastOrNull()?.key?.toEpochDay() ?: Long.MIN_VALUE)
    }
    val selected = groups.find { it.key.toEpochDay() == selectedKey } ?: groups.lastOrNull()
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkTotalsLines(totals)
        }
    }
    val worked = groups.flatMap { it.shifts }
    if (groups.isEmpty()) {
        Text(stringResource(R.string.work_empty_report), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(stringResource(R.string.work_days_and_nights), fontWeight = FontWeight.SemiBold)
    Column {
        val day = shiftTotals(worked.filterNot { it.night })
        val night = shiftTotals(worked.filter { it.night })
        Text(stringResource(R.string.work_day_count, totalLabel(day)))
        Text(stringResource(R.string.work_night_count, totalLabel(night)))
    }
    Text(stringResource(if (monthly) R.string.work_by_month else R.string.work_by_week),
        fontWeight = FontWeight.SemiBold)
    val peak = groups.maxOf { it.totals.minutes }.coerceAtLeast(1L)
    for (group in groups) {
        Panel(Modifier.fillMaxWidth(), onClick = { selectedKey = group.key.toEpochDay() }) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (monthly) workMonthLabel(group.key) else workRangeLabel(group.period),
                    fontWeight = if (selected == group) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected == group) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(totalLabel(group.totals), style = MaterialTheme.typography.bodySmall)
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Box(
                        Modifier.fillMaxWidth(group.totals.minutes.toFloat() / peak)
                            .height(4.dp).background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
    selected?.let { group ->
        Text(stringResource(R.string.work_period_shifts), fontWeight = FontWeight.SemiBold)
        for (shift in group.shifts) {
            val day = LocalDate.ofEpochDay(shift.day)
            Panel(Modifier.fillMaxWidth(), onClick = { openDay(day) }) {
                Column(Modifier.padding(11.dp)) {
                    Text(shift.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.work_card_calculation, calendarDateLabel(day),
                            shift.earnings()?.let { workMoney(it) } ?: stringResource(R.string.work_no_rate)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun WorkError(retry: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text(stringResource(R.string.work_load_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
        }
    }
}
