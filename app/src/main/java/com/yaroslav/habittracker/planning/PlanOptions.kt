package com.yaroslav.habittracker.planning

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.R

private const val ALL_DAYS = 127

@Composable
internal fun repeatLabel(mask: Int): String = stringResource(when (mask) {
    0 -> R.string.plan_repeat_never
    ALL_DAYS -> R.string.plan_repeat_daily
    WORKDAYS -> R.string.plan_repeat_workdays
    else -> R.string.plan_repeat_custom
})

@Composable
internal fun reminderLabel(offset: Int?): String = when (offset) {
    null -> stringResource(R.string.plan_reminder_off)
    0 -> stringResource(R.string.plan_reminder_now)
    else -> stringResource(R.string.plan_reminder_before, offset)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepeatPicker(mask: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    var custom by rememberSaveable { mutableStateOf(mask != 0 && mask != ALL_DAYS && mask != WORKDAYS) }
    val names = stringArrayResource(R.array.calendar_weekdays)
    OptionPicker(
        title = stringResource(R.string.plan_repeat),
        selection = if (custom) stringResource(R.string.plan_repeat_custom) else repeatLabel(mask),
        choices = listOf(0, ALL_DAYS, WORKDAYS).map { value ->
            repeatLabel(value) to {
                custom = false
                onChange(value)
            }
        } + (stringResource(R.string.plan_repeat_custom) to {
            custom = true
            if (mask == 0) onChange(WORKDAYS)
        }),
        enabled = enabled,
    )
    if (custom) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEachIndexed { index, name ->
                val bit = 1 shl index
                val selected = mask and bit != 0
                FilterChip(
                    selected,
                    { onChange(mask xor bit) },
                    { Text(name) },
                    enabled = enabled && (!selected || mask != bit),
                )
            }
        }
    }
}

@Composable
internal fun ReminderPicker(offset: Int?, onChange: (Int?) -> Unit, enabled: Boolean) {
    OptionPicker(
        title = stringResource(R.string.plan_reminder),
        selection = reminderLabel(offset),
        choices = (listOf(null) + REMINDER_OFFSETS).map { value ->
            reminderLabel(value) to { onChange(value) }
        },
        enabled = enabled,
    )
    if (offset != null) ReminderPermission()
}

@Composable
private fun OptionPicker(title: String, selection: String, choices: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text(selection)
        }
        DropdownMenu(expanded, { expanded = false }) {
            for ((label, select) in choices) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        select()
                    },
                )
            }
        }
    }
}

@Composable
private fun ReminderPermission() {
    val context = LocalContext.current
    val app = context.applicationContext as HabitTrackerApplication
    val activity = remember(context) { context.activity() }
    var refresh by remember { mutableIntStateOf(0) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var rationale by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) app.refreshReminders()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val allowed = remember(refresh, context) { remindersEnabled(context) }
    if (allowed) return

    val canExplain = Build.VERSION.SDK_INT >= 33 &&
        activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
        } == true
    val openSettings = Build.VERSION.SDK_INT < 33 || reminderPermissionGranted(context) || (attempted && !canExplain)
    val action = if (openSettings) R.string.plan_reminder_settings else R.string.plan_reminder_allow

    fun request() {
        attempted = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Text(stringResource(R.string.plan_reminder_disabled), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        when {
            openSettings -> try {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            } catch (_: ActivityNotFoundException) {
                // Some system variants do not expose a separate notification settings screen.
            }
            canExplain -> rationale = true
            else -> request()
        }
    }) { Text(stringResource(action)) }

    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(R.string.plan_reminder_allow)) },
            text = { Text(stringResource(R.string.plan_reminder_permission_description)) },
            dismissButton = { TextButton({ rationale = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton({
                    rationale = false
                    request()
                }) { Text(stringResource(R.string.plan_reminder_allow)) }
            },
        )
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
