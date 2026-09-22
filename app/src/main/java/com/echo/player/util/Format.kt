package com.echo.player.util

import java.util.Locale
import kotlin.math.roundToLong

/** `1:02:03` for long durations, `2:03` for short ones. */
fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** `4h 12m`, `12m`, `< 1m` — for at-a-glance totals rather than precise timing. */
fun formatDurationShort(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalMinutes = (ms / 60_000.0).roundToLong()
    if (totalMinutes < 1) return "< 1m"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** `1.2 GB`, `340 MB`, `12 KB` — decimal units, as phones show storage. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

fun formatSpeed(speed: Float): String {
    val text = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return text + "x"
}
