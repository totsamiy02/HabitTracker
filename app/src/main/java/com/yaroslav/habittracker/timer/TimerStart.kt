package com.yaroslav.habittracker.timer

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yaroslav.habittracker.R

internal data class TimerStartRequest(
    val limitMillis: Long?,
    val habitIds: Set<Long> = emptySet(),
    val planId: Long? = null,
)

private val requestSaver = listSaver<TimerStartRequest?, Any>(
    save = { request ->
        if (request == null) emptyList()
        else listOf(request.limitMillis ?: 0L, request.habitIds.toLongArray(), request.planId ?: 0L)
    },
    restore = { parts ->
        TimerStartRequest(
            (parts[0] as Long).takeIf { it > 0 },
            (parts[1] as LongArray).toSet(),
            (parts[2] as Long).takeIf { it > 0 },
        )
    },
)

@Composable
internal fun rememberTimerStarter(model: TimerViewModel): (TimerStartRequest) -> Unit {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
    }
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val preferences = remember(context) { context.getSharedPreferences("timer_notification", Context.MODE_PRIVATE) }
    var offered by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable(stateSaver = requestSaver) { mutableStateOf<TimerStartRequest?>(null) }

    fun start(request: TimerStartRequest) = model.start(request.limitMillis, request.habitIds, request.planId)
    fun startPending() {
        pending?.let { request ->
            pending = null
            start(request)
        }
    }

    val permissionResult = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startPending()
    }
    val settingsResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        startPending()
    }

    if (explaining && pending != null) {
        val previouslyRequested = preferences.getBoolean("requested", false)
        val needsSettings = activity != null && previouslyRequested &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        AlertDialog(
            onDismissRequest = {
                explaining = false
                pending = null
            },
            title = { Text(stringResource(R.string.timer_permission_title)) },
            text = {
                Text(stringResource(
                    if (needsSettings) R.string.today_timer_permission_settings_body else R.string.timer_permission_body,
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    try {
                        if (needsSettings) {
                            settingsResult.launch(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        } else {
                            preferences.edit().putBoolean("requested", true).apply()
                            permissionResult.launch(permission)
                        }
                    } catch (_: ActivityNotFoundException) {
                        startPending()
                    } catch (_: SecurityException) {
                        startPending()
                    }
                }) {
                    Text(stringResource(
                        if (needsSettings) R.string.today_timer_permission_settings else R.string.timer_permission_allow,
                    ))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    explaining = false
                    startPending()
                }) { Text(stringResource(R.string.timer_permission_later)) }
            },
        )
    }

    return { request ->
        if (pending == null) {
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            if (needsPermission && !offered) {
                offered = true
                pending = request
                explaining = true
            } else {
                start(request)
            }
        }
    }
}
