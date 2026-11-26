package com.yaroslav.habittracker.timer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.MainActivity
import com.yaroslav.habittracker.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val OPEN_TIMER = "com.yaroslav.habittracker.OPEN_TIMER"
internal const val OPEN_TIMER_FINISH = "com.yaroslav.habittracker.OPEN_TIMER_FINISH"
private const val ACTION_PAUSE = "com.yaroslav.habittracker.timer.PAUSE"
private const val ACTION_RESUME = "com.yaroslav.habittracker.timer.RESUME"
private const val ACTION_EXPIRE = "com.yaroslav.habittracker.timer.EXPIRE"
private const val ACTIVE_CHANNEL = "active_timer"
private const val FINISHED_CHANNEL = "finished_timer"
private const val ACTIVE_ID = 20
private const val FINISHED_ID = 21

internal object TimerRuntime {
    fun monitor(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        } catch (_: RuntimeException) {
            // A restricted background start does not discard the persisted timer.
        }
    }

    fun synchronizeAlarm(context: Context, timer: ActiveTimer, now: TimerMoment) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = command(context, ACTION_EXPIRE, timer.id)
        val remaining = timer.remainingAt(now)
        if (!timer.running || remaining == null) {
            alarms.cancel(pending)
            return
        }

        val at = now.elapsedMillis + remaining
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
                // The permission may change between the capability check and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
    }

    fun cancelAlarm(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(command(context, ACTION_EXPIRE, id))
    }

    suspend fun finish(
        app: HabitTrackerApplication,
        id: String,
        expiredOnly: Boolean,
    ): FocusSession? = withContext(NonCancellable) {
        val saved = app.timers.finish(id, expiredOnly)
        if (saved != null) {
            cancelAlarm(app, id)
            if (expiredOnly) TimerNotifications.finished(app, saved.durationMillis)
        }
        saved
    }

    fun command(context: Context, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TimerActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.Builder().scheme("habittracker").authority("timer").appendPath(id).build()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openTimer(context: Context, confirmFinish: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context,
            if (confirmFinish) 1 else 0,
            Intent(context, MainActivity::class.java)
                .setAction(if (confirmFinish) OPEN_TIMER_FINISH else OPEN_TIMER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_PAUSE && action != ACTION_RESUME && action != ACTION_EXPIRE) return
        val uri = intent.data
        if (uri?.scheme != "habittracker" || uri.authority != "timer") return
        val id = uri.pathSegments.singleOrNull() ?: return
        val app = context.applicationContext as HabitTrackerApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.database.timer().active()?.id != id) return@launch
                if (action == ACTION_EXPIRE) {
                    if (TimerRuntime.finish(app, id, expiredOnly = true) == null) {
                        val updated = app.database.timer().active()?.takeIf { it.id == id }
                        if (updated != null) TimerRuntime.synchronizeAlarm(app, updated, app.timers.now())
                    }
                } else {
                    app.timers.togglePause(id, pause = action == ACTION_PAUSE)
                    val updated = app.database.timer().active() ?: return@launch
                    TimerRuntime.synchronizeAlarm(app, updated, app.timers.now())
                    TimerRuntime.monitor(app)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the database state so the app can reconcile on its next launch.
            } finally {
                pending.finish()
            }
        }
    }
}

class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HabitTrackerApplication
        if (observing) return START_STICKY
        TimerNotifications.createChannels(this)
        try {
            val notification = TimerNotifications.active(this, null, null, emptyList())
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ACTIVE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ACTIVE_ID, notification)
            }
        } catch (_: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        observing = true
        scope.launch {
            try {
                app.timers.active.collectLatest { timer ->
                    if (timer == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    val now = app.timers.now()
                    TimerRuntime.synchronizeAlarm(app, timer, now)
                    val names = timer.habitIds.mapNotNull { app.database.habits().find(it)?.name }
                    val notification = TimerNotifications.active(this@TimerService, timer, now, names)
                    getSystemService(NotificationManager::class.java).notify(ACTIVE_ID, notification)

                    if (timer.running) {
                        val remaining = timer.remainingAt(now)
                        if (remaining != null) {
                            delay(remaining)
                            TimerRuntime.finish(app, timer.id, expiredOnly = true)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private object TimerNotifications {
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = NotificationChannel(ACTIVE_CHANNEL, context.getString(R.string.timer_channel_active),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val finished = NotificationChannel(FINISHED_CHANNEL, context.getString(R.string.timer_channel_finished),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannels(listOf(active, finished))
    }

    fun active(
        context: Context,
        timer: ActiveTimer?,
        now: TimerMoment?,
        names: List<String>,
    ): Notification {
        val title = context.getString(
            if (timer?.running == false) R.string.timer_paused else R.string.timer_notification_title,
        )
        val builder = Notification.Builder(context, ACTIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_timer_status)
            .setContentTitle(title)
            .setContentIntent(TimerRuntime.openTimer(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (timer == null || now == null) return builder.build()

        val publicBuilder = Notification.Builder(context, ACTIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_timer_status)
            .setContentTitle(title)
            .setContentIntent(TimerRuntime.openTimer(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (timer.running) {
            val isCountdown = timer.limitMillis != null
            val whenMillis = if (isCountdown) now.wallMillis + requireNotNull(timer.remainingAt(now))
                else now.wallMillis - timer.elapsedAt(now)
            for (entry in listOf(builder, publicBuilder)) {
                entry.setWhen(whenMillis).setShowWhen(true).setUsesChronometer(true)
                    .setChronometerCountDown(isCountdown)
            }
            if (Build.VERSION.SDK_INT >= 36) {
                builder.addExtras(Bundle().apply {
                    // The documented extra is available before the SDK 36.1 constant.
                    putBoolean("android.requestPromotedOngoing", true)
                })
            }
        } else {
            val position = timer.remainingAt(now) ?: timer.elapsedAt(now)
            val time = clockText(position, countdown = timer.limitMillis != null)
            builder.setContentText(time)
            publicBuilder.setContentText(time)
        }
        if (timer.running && names.isNotEmpty()) builder.setContentText(names.joinToString(", "))
        val toggle = if (timer.running) ACTION_PAUSE else ACTION_RESUME
        val toggleLabel = if (timer.running) R.string.timer_pause else R.string.timer_resume
        val toggleIcon = if (timer.running) R.drawable.ic_timer_pause else R.drawable.ic_timer_play
        val actions = listOf(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, toggleIcon),
                context.getString(toggleLabel),
                TimerRuntime.command(context, toggle, timer.id),
            ).build(),
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_timer_status),
                context.getString(R.string.timer_finish),
                TimerRuntime.openTimer(context, confirmFinish = true),
            ).build(),
        )
        for (action in actions) {
            builder.addAction(action)
            publicBuilder.addAction(action)
        }
        builder.setPublicVersion(publicBuilder.build())
        return builder.build()
    }

    fun finished(context: Context, duration: Long) {
        createChannels(context)
        val notification = Notification.Builder(context, FINISHED_CHANNEL)
            .setSmallIcon(R.drawable.ic_timer_status)
            .setContentTitle(context.getString(R.string.timer_expired))
            .setContentText(context.getString(R.string.timer_saved_time, clockText(duration)))
            .setContentIntent(TimerRuntime.openTimer(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(FINISHED_ID, notification)
        } catch (_: SecurityException) {
            // History stays available even when Android blocks notifications.
        }
    }
}
