package com.echo.player.util

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * How far back to start when picking up again. The longer you have been away, the more you need
 * to hear again to find the thread.
 */
fun rewindAfter(awayMs: Long): Long = when {
    awayMs < 5 * MINUTE -> 5_000L
    awayMs < 2 * HOUR -> 15_000L
    awayMs < 2 * DAY -> 30_000L
    else -> 45_000L
}
