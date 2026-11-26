package com.yaroslav.habittracker.timer

import java.util.Locale

internal fun clockText(millis: Long, countdown: Boolean = false): String {
    val seconds = if (countdown) (millis.coerceAtLeast(0L) + 999) / 1000
        else millis.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours == 0L) {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
    }
}
