package com.yaroslav.habittracker.planning

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.CALENDAR_YEARS
import com.yaroslav.habittracker.calendar.CalendarRepository
import com.yaroslav.habittracker.calendar.calendarDateLabel
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal data class WeekState(val monday: LocalDate, val days: List<DayTotals>? = null, val failed: Boolean = false)

internal class WeekViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CalendarRepository((application as HabitTrackerApplication).database)
    private data class WeekQuery(val monday: LocalDate = LocalDate.now().weekStart(), val attempt: Int = 0)
    private val query = MutableStateFlow(WeekQuery())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = query.flatMapLatest { (monday) ->
        repository.observeWeek(monday)
            .map { WeekState(monday, it) }
            .onStart { emit(WeekState(monday)) }
            .catch { emit(WeekState(monday, failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekState(query.value.monday))

    fun move(direction: Long) {
        query.update {
            val next = it.monday.plusWeeks(direction)
            if (next.plusDays(6).year in CALENDAR_YEARS && next.year in CALENDAR_YEARS) it.copy(monday = next)
            else it
        }
    }

    fun retry() = query.update { it.copy(attempt = it.attempt + 1) }
}

private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

@Composable
internal fun WeekScreen(state: WeekState, model: WeekViewModel) {
    val end = state.monday.plusDays(6)
    val dateRange = if (state.monday.month == end.month) {
        stringResource(R.string.week_range_month, state.monday.dayOfMonth, end.format(dayMonth))
    } else {
        stringResource(R.string.week_range, state.monday.format(dayMonth), end.format(dayMonth))
    }
    ScreenContent {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.move(-1) }) {
                AppIcon(R.drawable.ic_back, description = stringResource(R.string.week_previous))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(dateRange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            IconButton({ model.move(1) }) {
                AppIcon(R.drawable.ic_next, description = stringResource(R.string.week_next))
            }
        }
        val days = state.days
        when {
            state.failed -> Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.week_load_error))
                    TextButton(model::retry) { Text(stringResource(R.string.retry)) }
                }
            }
            days == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            days.all { it.plannedMinutes == 0L && it.actualMillis == 0L } -> Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.week_empty), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.week_empty_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> WeekReport(state.monday, days)
        }
    }
}

@Composable
private fun WeekReport(monday: LocalDate, days: List<DayTotals>) {
    var selected by rememberSaveable(monday.toEpochDay()) { mutableIntStateOf(0) }
    val selectedDay = days[selected]
    val planColor = MaterialTheme.colorScheme.primary
    val actualColor = MaterialTheme.colorScheme.tertiary
    val weekMaximum = days.maxOf {
        maxOf(it.plannedMinutes * 60_000L, it.actualMillis)
    }.coerceAtLeast(60_000L)
    val weekdays = stringArrayResource(R.array.calendar_weekdays)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TotalCard(R.string.week_planned, days.sumOf { it.plannedMinutes }, planColor, Modifier.weight(1f))
        TotalCard(R.string.calendar_actual, days.sumOf { it.actualMillis } / 60_000L, actualColor, Modifier.weight(1f))
    }

    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(R.string.week_plan, planColor)
                Legend(R.string.week_actual_short, actualColor)
            }
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for ((index, day) in days.withIndex()) {
                    val description = stringResource(
                        R.string.week_day_description,
                        calendarDateLabel(day.date),
                        elapsedLabel(day.plannedMinutes),
                        elapsedLabel(day.actualMillis / 60_000L),
                    )
                    Column(
                        Modifier.weight(1f)
                            .background(
                                if (index == selected) MaterialTheme.colorScheme.surfaceVariant
                                else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .selectable(index == selected, onClick = { selected = index }, role = Role.Button)
                            .semantics { contentDescription = description }
                            .padding(horizontal = 2.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.height(96.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                        ) {
                            ChartBar(day.plannedMinutes * 60_000L, weekMaximum, planColor, Modifier.weight(1f))
                            ChartBar(day.actualMillis, weekMaximum, actualColor, Modifier.weight(1f))
                        }
                        Text(weekdays[index], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(calendarDateLabel(selectedDay.date), fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    R.string.week_day_detail,
                    elapsedLabel(selectedDay.plannedMinutes),
                    elapsedLabel(selectedDay.actualMillis / 60_000L),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChartBar(value: Long, maximum: Long, color: Color, modifier: Modifier) {
    val chartHeight = (96f * value / maximum).dp
    Box(modifier.height(chartHeight).background(color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
}

@Composable
private fun Legend(title: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).aspectRatio(1f).background(color, CircleShape))
        Text(stringResource(title), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TotalCard(title: Int, minutes: Long, color: Color, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
            Text(elapsedLabel(minutes), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun elapsedLabel(minutes: Long): String = when {
    minutes < 60 -> stringResource(R.string.calendar_duration_minutes, minutes)
    minutes % 60L == 0L -> stringResource(R.string.week_hours, minutes / 60L)
    else -> stringResource(R.string.week_hours_minutes, minutes / 60L, minutes % 60L)
}
