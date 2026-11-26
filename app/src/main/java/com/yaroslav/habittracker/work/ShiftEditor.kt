package com.yaroslav.habittracker.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.CalendarDateDialog
import com.yaroslav.habittracker.calendar.PlanTimeDialog
import com.yaroslav.habittracker.calendar.calendarDateLabel
import com.yaroslav.habittracker.calendar.planClockLabel
import com.yaroslav.habittracker.ui.Panel
import java.math.BigDecimal
import java.time.LocalDate

private val dayPreset = ShiftHours(8 * 60, 16 * 60 + 30, 30)
private val nightPreset = ShiftHours(22 * 60, 6 * 60, 30)
private val rubleInput = Regex("[0-9]{1,7}(?:[.,][0-9]{1,2})?")

internal fun parseHourlyRate(value: String): Long? {
    if (!rubleInput.matches(value)) return null
    return value.replace(',', '.').toBigDecimal().movePointRight(2).toLong().takeIf { it <= MAX_HOURLY_KOPECKS }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ShiftEditorSheet(
    model: WorkViewModel,
    date: LocalDate,
    existing: WorkShift?,
    confirmWorked: Boolean,
    templates: WorkLoad<List<ShiftTemplate>>,
    dismiss: () -> Unit,
    saved: (LocalDate) -> Unit,
) {
    val forms = rememberSaveableStateHolder()
    val currentTemplates = (templates as? WorkLoad.Ready)?.value.orEmpty()
    val day = ShiftTemplate(title = stringResource(R.string.work_day_shift), night = false, hours = dayPreset)
    val night = ShiftTemplate(title = stringResource(R.string.work_night_shift), night = true, hours = nightPreset)
    val custom = ShiftTemplate(title = "", night = false, hours = ShiftHours(9 * 60, 18 * 60, 0))
    var presetId by rememberSaveable { mutableLongStateOf(-1L) }
    var browsingTemplates by rememberSaveable { mutableStateOf(false) }
    var editingTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var shiftDate by rememberSaveable(existing?.id, date.toEpochDay()) {
        mutableLongStateOf(existing?.day ?: date.toEpochDay())
    }
    var chooseDate by rememberSaveable { mutableStateOf(false) }
    var deleteShift by rememberSaveable { mutableStateOf(false) }
    var deleteTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!model.writing) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val editorId = editingTemplateId
            if (editorId != null) {
                val template = currentTemplates.find { it.id == editorId } ?: custom
                EditorTitle(if (editorId == 0L) R.string.work_add_template else R.string.work_edit_template)
                key(editorId) {
                    ShiftFields(
                        initial = template,
                        showIdentity = true,
                        busy = model.writing,
                        failed = model.failed,
                        saveLabel = R.string.work_save_template,
                        cancel = { editingTemplateId = null; model.beginEdit() },
                        save = { title, isNight, hours, rate ->
                            model.save(ShiftTemplate(editorId, title, isNight, hours, rate)) { editingTemplateId = null }
                        },
                    )
                }
            } else if (browsingTemplates) {
                EditorTitle(R.string.work_templates)
                when (templates) {
                    WorkLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    WorkLoad.Failed -> WorkError(model::retry)
                    is WorkLoad.Ready -> {
                        if (currentTemplates.isEmpty()) Text(stringResource(R.string.work_empty_templates))
                        for (template in currentTemplates) {
                            Panel(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(template.title, fontWeight = FontWeight.SemiBold)
                                    Text(shiftTimeLabel(template.hours), style = MaterialTheme.typography.bodySmall)
                                    Row(Modifier.align(Alignment.End)) {
                                        TextButton(onClick = { model.beginEdit(); editingTemplateId = template.id },
                                            enabled = !model.writing) { Text(stringResource(R.string.calendar_edit)) }
                                        TextButton(onClick = { model.beginEdit(); deleteTemplateId = template.id },
                                            enabled = !model.writing) { Text(stringResource(R.string.work_delete)) }
                                    }
                                }
                            }
                        }
                        Button({ model.beginEdit(); editingTemplateId = 0L }, Modifier.fillMaxWidth(),
                            enabled = !model.writing) { Text(stringResource(R.string.work_add_template)) }
                    }
                }
                TextButton(onClick = { browsingTemplates = false; model.beginEdit() }, enabled = !model.writing) {
                    Text(stringResource(R.string.back))
                }
            } else {
                EditorTitle(when {
                    confirmWorked -> R.string.work_actual_hours
                    existing != null -> R.string.work_edit_shift
                    else -> R.string.work_new_shift
                })
                val selectedDate = LocalDate.ofEpochDay(shiftDate)
                if (existing != null && !confirmWorked) {
                    OutlinedButton({ chooseDate = true }, enabled = !model.writing) { Text(calendarDateLabel(selectedDate)) }
                } else {
                    Text(calendarDateLabel(selectedDate), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (existing == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(presetId == -1L, { presetId = -1L }, { Text(day.title) }, enabled = !model.writing)
                        FilterChip(presetId == -2L, { presetId = -2L }, { Text(night.title) }, enabled = !model.writing)
                        FilterChip(presetId == 0L, { presetId = 0L }, { Text(stringResource(R.string.work_custom_shift)) },
                            enabled = !model.writing)
                        for (template in currentTemplates) {
                            FilterChip(presetId == template.id, { presetId = template.id }, { Text(template.title) },
                                enabled = !model.writing)
                        }
                    }
                    TextButton(onClick = { browsingTemplates = true; model.beginEdit() }, enabled = !model.writing) {
                        Text(stringResource(R.string.work_templates))
                    }
                }
                val initial = if (existing != null) {
                    ShiftTemplate(title = existing.title, night = existing.night, hours = existing.hours,
                        hourlyKopecks = existing.hourlyKopecks)
                } else when (presetId) {
                    -1L -> day
                    -2L -> night
                    else -> currentTemplates.find { it.id == presetId } ?: custom
                }
                if (confirmWorked) Text(initial.title, fontWeight = FontWeight.SemiBold)
                forms.SaveableStateProvider("shift:$presetId:${existing?.id}:$confirmWorked") {
                    ShiftFields(
                        initial = initial,
                        showIdentity = !confirmWorked,
                        busy = model.writing,
                        failed = model.failed,
                        saveLabel = if (confirmWorked) R.string.work_confirm_worked else R.string.work_save_shift,
                        cancel = dismiss,
                        delete = if (existing != null && !confirmWorked) ({ deleteShift = true }) else null,
                        save = { title, isNight, hours, rate ->
                            val shift = when {
                                existing == null -> ShiftTemplate(title = title, night = isNight, hours = hours,
                                    hourlyKopecks = rate).shift(shiftDate)
                                confirmWorked -> existing.copy(actual = hours)
                                existing.actual != null -> existing.copy(day = shiftDate, title = title, night = isNight,
                                    hourlyKopecks = rate, actual = hours)
                                else -> existing.copy(day = shiftDate, title = title, night = isNight,
                                    hourlyKopecks = rate, planned = hours)
                            }
                            model.save(shift) { saved(LocalDate.ofEpochDay(shift.day)) }
                        },
                    )
                }
            }
        }
    }
    if (chooseDate) {
        CalendarDateDialog(shiftDate, dismiss = { chooseDate = false }) {
            shiftDate = it
            chooseDate = false
        }
    }
    if (deleteShift && existing != null) {
        ConfirmWorkDelete(
            R.string.work_delete_shift, R.string.work_delete_shift_hint, model,
            close = { deleteShift = false },
            delete = { model.delete(existing) { deleteShift = false; dismiss() } },
        )
    }
    currentTemplates.find { it.id == deleteTemplateId }?.let { template ->
        ConfirmWorkDelete(
            R.string.work_delete_template, R.string.work_delete_template_hint, model,
            close = { deleteTemplateId = null },
            delete = {
                model.delete(template) {
                    deleteTemplateId = null
                    if (presetId == template.id) presetId = 0L
                }
            },
        )
    }
}

@Composable
private fun EditorTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShiftFields(
    initial: ShiftTemplate,
    showIdentity: Boolean,
    busy: Boolean,
    failed: Boolean,
    saveLabel: Int,
    cancel: () -> Unit,
    delete: (() -> Unit)? = null,
    save: (String, Boolean, ShiftHours, Long?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var night by rememberSaveable { mutableStateOf(initial.night) }
    var start by rememberSaveable { mutableIntStateOf(initial.hours.startMinute) }
    var end by rememberSaveable { mutableIntStateOf(initial.hours.endMinute) }
    var pause by rememberSaveable { mutableStateOf(initial.hours.breakMinutes.toString()) }
    var rateText by rememberSaveable {
        mutableStateOf(initial.hourlyKopecks?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty())
    }
    var chooseStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val hours = ShiftHours(start, end, pause.ifBlank { "0" }.toIntOrNull() ?: -1)
    val rate = if (showIdentity) parseHourlyRate(rateText) else initial.hourlyKopecks
    val validRate = !showIdentity || rateText.isBlank() || rate != null
    val validHours = hours.isValid()
    if (showIdentity) {
        OutlinedTextField(title, { if (it.length <= SHIFT_TITLE_LIMIT) title = it }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.habit_name)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(!night, { night = false }, { Text(stringResource(R.string.work_day_shift)) }, enabled = !busy)
            FilterChip(night, { night = true }, { Text(stringResource(R.string.work_night_shift)) }, enabled = !busy)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        OutlinedButton({ chooseStart = true }, Modifier.weight(1f), enabled = !busy) {
            Text(stringResource(R.string.work_start_time, planClockLabel(start)), textAlign = TextAlign.Center)
        }
        OutlinedButton({ chooseStart = false }, Modifier.weight(1f), enabled = !busy) {
            Text(stringResource(R.string.work_end_time, planClockLabel(end)), textAlign = TextAlign.Center)
        }
    }
    if (hours.crossesMidnight) {
        Text(stringResource(R.string.work_finish_next_day), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        pause, { if (it.length <= 4 && it.all { char -> char in '0'..'9' }) pause = it }, Modifier.fillMaxWidth(),
        enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(stringResource(R.string.work_break)) },
    )
    if (showIdentity) {
        OutlinedTextField(
            rateText, { if (it.length <= 11 && it.all { char -> char in '0'..'9' || char == ',' || char == '.' }) rateText = it },
            Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, isError = !validRate,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text(stringResource(R.string.work_rate)) },
            placeholder = { Text(stringResource(R.string.work_no_rate)) },
        )
    }
    if (!validHours || !validRate) {
        Text(stringResource(if (!validHours) R.string.work_invalid_hours else R.string.work_invalid_rate),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            val sample = WorkShift(day = 0, title = title, night = night, planned = hours, hourlyKopecks = rate)
            Text(
                stringResource(R.string.work_card_calculation, workDuration(hours.paidMinutes.toLong()),
                    sample.earnings()?.let { workMoney(it) } ?: stringResource(R.string.work_no_rate)),
                Modifier.fillMaxWidth().padding(10.dp), fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    if (failed) Text(stringResource(R.string.work_write_error), color = MaterialTheme.colorScheme.error)
    delete?.let {
        TextButton(onClick = it, enabled = !busy) {
            Text(stringResource(R.string.work_delete), color = MaterialTheme.colorScheme.error)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(cancel, Modifier.weight(1f), enabled = !busy) { Text(stringResource(R.string.cancel)) }
        Button({ save(title.trim(), night, hours, rate) }, Modifier.weight(1.5f),
            enabled = title.isNotBlank() && validHours && validRate && !busy) {
            Text(stringResource(saveLabel), textAlign = TextAlign.Center)
        }
    }
    chooseStart?.let { selectingStart ->
        PlanTimeDialog(if (selectingStart) start else end, dismiss = { chooseStart = null }) { value ->
            if (selectingStart) start = value else end = value
            chooseStart = null
        }
    }
}

@Composable
private fun ConfirmWorkDelete(title: Int, hint: Int, model: WorkViewModel, close: () -> Unit, delete: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!model.writing) close() },
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(stringResource(hint))
                if (model.failed) Text(stringResource(R.string.work_write_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = delete, enabled = !model.writing) {
                Text(stringResource(R.string.work_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = close, enabled = !model.writing) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun shiftTimeLabel(hours: ShiftHours): String = stringResource(
    R.string.work_time_range,
    planClockLabel(hours.startMinute),
    planClockLabel(hours.endMinute),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShiftCard(
    shift: WorkShift,
    continuation: Boolean,
    goTo: () -> Unit,
    edit: () -> Unit,
    worked: () -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShiftMarker(if (continuation) WorkMark.Carry else if (shift.night) WorkMark.Night else WorkMark.Day)
                Text(shift.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            if (continuation) {
                Text(stringResource(R.string.work_until, planClockLabel(shift.hours.endMinute),
                    calendarDateLabel(LocalDate.ofEpochDay(shift.day))))
                TextButton(onClick = goTo, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.work_go_to_start))
                }
            } else {
                Text(shiftTimeLabel(shift.hours), fontWeight = FontWeight.SemiBold)
                if (shift.hours.crossesMidnight) Text(stringResource(R.string.work_finish_next_day),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.work_card_calculation, workDuration(shift.hours.paidMinutes.toLong()),
                    shift.earnings()?.let { workMoney(it) } ?: stringResource(R.string.work_no_rate)))
                shift.hourlyKopecks?.let { rate ->
                    Text(stringResource(R.string.work_rate_value, workMoney(rate)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(if (shift.actual == null) R.string.work_planned_status else R.string.work_worked_status),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                ) {
                    TextButton(onClick = edit) { Text(stringResource(R.string.calendar_edit)) }
                    if (shift.actual == null) {
                        OutlinedButton(onClick = worked) { Text(stringResource(R.string.work_mark_worked)) }
                    }
                }
            }
        }
    }
}

internal enum class WorkMark { Day, Night, Carry }

@Composable
internal fun ShiftMarker(mark: WorkMark, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = when (mark) {
        WorkMark.Day -> R.string.work_day_short
        WorkMark.Night -> R.string.work_night_short
        WorkMark.Carry -> R.string.work_carry_short
    }
    Surface(
        modifier.size(17.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = when (mark) {
            WorkMark.Day -> colors.primaryContainer
            WorkMark.Night -> colors.primary
            WorkMark.Carry -> colors.surfaceVariant
        },
        contentColor = when (mark) {
            WorkMark.Day -> colors.onPrimaryContainer
            WorkMark.Night -> colors.onPrimary
            WorkMark.Carry -> colors.onSurfaceVariant
        },
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}
