package com.yaroslav.habittracker.planning

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.yaroslav.habittracker.HabitTrackerApplication
import com.yaroslav.habittracker.MainActivity
import com.yaroslav.habittracker.R
import com.yaroslav.habittracker.calendar.CALENDAR_YEARS
import com.yaroslav.habittracker.calendar.planClockLabel
import com.yaroslav.habittracker.habits.HabitDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal const val OPEN_PLAN_CALENDAR = "com.yaroslav.habittracker.OPEN_PLAN_CALENDAR"
internal const val PLAN_REMINDER_CHANNEL = "plan_reminders"
private const val FIRE_PLAN_REMINDER = "com.yaroslav.habittracker.FIRE_PLAN_REMINDER"
private const val EXPECTED_AT = "plan_reminder_expected_at"
private const val MAX_LATE_MILLIS = 60 * 60_000L
private const val REMINDER_NOTICE_ID = 23

internal fun Intent.reminderDate(): LocalDate? {
    if (action != OPEN_PLAN_CALENDAR) return null
    val uri = data ?: return null
    if (uri.scheme != "habittracker" || uri.authority != "calendar") return null
    val epochDay = uri.pathSegments.singleOrNull()?.toLongOrNull() ?: return null
    val first = LocalDate.of(CALENDAR_YEARS.first, 1, 1).toEpochDay()
    val last = LocalDate.of(CALENDAR_YEARS.last, 12, 31).toEpochDay()
    return if (epochDay in first..last) LocalDate.ofEpochDay(epochDay) else null
}

internal fun reminderPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

internal fun remindersEnabled(context: Context): Boolean {
    val notifications = context.getSystemService(NotificationManager::class.java)
    return reminderPermissionGranted(context) && notifications.areNotificationsEnabled() &&
        notifications.getNotificationChannel(PLAN_REMINDER_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

internal class ReminderScheduler(private val context: Context, private val database: HabitDatabase) {
    private val lock = Mutex()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private val alarmState = context.getSharedPreferences("plan_alarm", Context.MODE_PRIVATE)
    private val scheduledAt get() = alarmState.getLong(EXPECTED_AT, 0L)

    suspend fun refresh() = lock.withLock {
        val now = ZonedDateTime.now()
        val plans = inventory()
        deliverIfDue(scheduledAt, now, plans)
        schedule(now, plans)
    }

    suspend fun fire(expectedAt: Long) = lock.withLock {
        if (expectedAt != scheduledAt) return@withLock
        val now = ZonedDateTime.now()
        val plans = inventory()
        deliverIfDue(expectedAt, now, plans)
        schedule(now, plans)
    }

    private fun deliverIfDue(expectedAt: Long, now: ZonedDateTime, plans: PlanSchedule) {
        if (expectedAt <= 0 || !remindersEnabled(context)) return
        val age = now.toInstant().toEpochMilli() - expectedAt
        if (age !in 0..MAX_LATE_MILLIS) return
        val expected = Instant.ofEpochMilli(expectedAt - 1).atZone(now.zone)
        for (reminder in plans.nextReminders(expected).filter { it.atMillis == expectedAt }) {
            notify(reminder)
        }
    }

    private suspend fun inventory(): PlanSchedule = database.withTransaction {
        val plans = database.calendar().reminderPlans()
        val ids = plans.mapTo(mutableSetOf()) { it.id }
        PlanSchedule(plans, database.habits().allSkips().filter { it.planId in ids }.toSet())
    }

    private fun schedule(now: ZonedDateTime, plans: PlanSchedule) {
        val upcoming = if (remindersEnabled(context)) {
            plans.nextReminders(now).firstOrNull()
        } else {
            null
        }
        if (upcoming == null) {
            alarmManager.cancel(reminderIntent(0))
            alarmState.edit().remove(EXPECTED_AT).apply()
            return
        }

        val pending = reminderIntent(upcoming.atMillis)
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, upcoming.atMillis, pending)
                alarmState.edit().putLong(EXPECTED_AT, upcoming.atMillis).apply()
                return
            } catch (_: SecurityException) {
                // Alarm permission can be revoked between checking and scheduling.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, upcoming.atMillis, pending)
        alarmState.edit().putLong(EXPECTED_AT, upcoming.atMillis).apply()
    }

    private fun reminderIntent(timestamp: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PlanReminderReceiver::class.java)
            .setAction(FIRE_PLAN_REMINDER)
            .putExtra(EXPECTED_AT, timestamp),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(reminder: PlanReminder) {
        notifications.createNotificationChannel(
            NotificationChannel(
                PLAN_REMINDER_CHANNEL,
                context.getString(R.string.plan_reminder_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        )
        val dayUri = Uri.Builder().scheme("habittracker").authority("calendar")
            .appendPath(reminder.day.toString()).build()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(OPEN_PLAN_CALENDAR)
                .setData(dayUri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicNotice = Notification.Builder(context, PLAN_REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(context.getString(R.string.plan_reminder_generic))
            .setContentIntent(open)
            .build()
        val minutes = requireNotNull(reminder.plan.reminderMinutes)
        val text = if (minutes == 0) {
            context.getString(R.string.plan_reminder_now)
        } else {
            context.getString(R.string.plan_reminder_before, minutes)
        }
        val notice = Notification.Builder(context, PLAN_REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(context.getString(
                R.string.calendar_event_title,
                planClockLabel(reminder.plan.minuteOfDay),
                reminder.plan.title,
            ))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_calendar),
                    context.getString(R.string.plan_reminder_open),
                    open,
                ).build(),
            )
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotice)
            .build()
        try {
            notifications.notify("plan:${reminder.plan.id}:${reminder.day}", REMINDER_NOTICE_ID, notice)
        } catch (_: SecurityException) {
            // Notification permission can change while publishing.
        }
    }
}

class PlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FIRE_PLAN_REMINDER) return
        val expected = intent.getLongExtra(EXPECTED_AT, 0)
        if (expected <= 0) return
        service(context) { fire(expected) }
    }
}

class PlanReminderRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                service(context) { refresh() }
        }
    }
}

private fun BroadcastReceiver.service(context: Context, action: suspend ReminderScheduler.() -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext as HabitTrackerApplication
    CoroutineScope(Dispatchers.IO).launch {
        try {
            withTimeout(8_500) { app.reminders.action() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A later app resume can schedule the next reminder.
        } finally {
            pending.finish()
        }
    }
}
