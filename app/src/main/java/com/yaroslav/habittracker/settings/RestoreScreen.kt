package com.yaroslav.habittracker.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.ui.Panel
import com.yaroslav.habittracker.ui.ScreenContent

@Composable
internal fun RestoreScreen(model: RestoreViewModel) {
    val state = model.state
    var confirm by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.read(uri)
    }
    val busy = state == RestoreState.Reading || state == RestoreState.Writing
    ScreenContent {
        Text(
            stringResource(R.string.restore_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                try {
                    picker.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    model.pickerFailed()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.restore_choose)) }

        when (state) {
            RestoreState.Idle -> Unit
            RestoreState.Reading, RestoreState.Writing -> {
                Text(stringResource(if (state == RestoreState.Reading) R.string.restore_reading else R.string.restore_writing))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is RestoreState.Failed -> Text(stringResource(state.message), color = MaterialTheme.colorScheme.error)
            is RestoreState.Saved -> {
                Text(stringResource(R.string.restore_saved), style = MaterialTheme.typography.titleLarge)
                BackupReview(state.counts)
            }
            is RestoreState.Preview -> {
                val file = state.selected
                Text(file.name ?: stringResource(R.string.backup), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.restore_format, file.payload.version))
                BackupReview(file.payload.snapshot.counts(), state.current)
                state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        if (state.current.hasRecords) confirm = true else model.apply(state)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.restore_apply)) }
            }
        }
    }

    if (confirm && state is RestoreState.Preview) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.restore_replace_title)) },
            text = { Text(stringResource(R.string.restore_replace_text)) },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = false
                        model.apply(state)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.restore_replace)) }
            },
        )
    }
}

@Composable
private fun BackupReview(copy: BackupCounts, current: BackupCounts? = null) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("", Modifier.weight(1.5f))
                    Text(stringResource(R.string.restore_current), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.restore_file), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider()
            }
            ReviewRow(R.string.habits, copy.habits, current?.habits)
            ReviewRow(R.string.backup_completions, copy.marks, current?.marks)
            ReviewRow(R.string.backup_sessions, copy.sessions, current?.sessions)
            ReviewRow(R.string.calendar_backup_plans, copy.plans, current?.plans)
            ReviewRow(R.string.task_tab, copy.tasks, current?.tasks)
            Text(stringResource(R.string.backup_mosaic), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReviewRow(label: Int, fromCopy: Int, current: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1.5f))
        if (current != null) Text(current.toString(), Modifier.weight(1f))
        Text(fromCopy.toString(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
    }
}
