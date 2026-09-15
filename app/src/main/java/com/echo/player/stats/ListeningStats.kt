package com.echo.player.stats

import com.echo.player.data.Book
import com.echo.player.data.ListeningSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

/** A day counts towards a streak once this much listening happens on it. */
const val STREAK_DAY_MS = 5 * 60_000L

/** Shorter plays (checking where you are, a stray tap) count towards time but not as sessions. */
const val MIN_SESSION_MS = 60_000L

enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

data class SeriesStats(
    val bookId: String,
    val title: String,
    val startedOn: LocalDate?,
    val daysActive: Int,
    val listenedMs: Long,
    val chaptersDone: Int,
    val chapterCount: Int,
    /** Null when there has not been enough recent listening to say. */
    val finishOn: LocalDate?
)

data class ListeningStats(
    val todayMs: Long,
    val weekMs: Long,
    val monthMs: Long,
    val allTimeMs: Long,
    val currentStreak: Int,
    val longestStreak: Int,
    /** Oldest first; the last entry is today. */
    val lastDays: List<Long>,
    val dayParts: Map<DayPart, Long>,
    val sessionCount: Int,
    val averageSessionMs: Long,
    val longestSessionMs: Long,
    val chaptersThisWeek: Int,
    /** Oldest first; the last entry is this week. */
    val lastWeeksChapters: List<Int>,
    val series: List<SeriesStats>,
    /** The first day anything was recorded. Listening before that was never logged. */
    val countingSince: LocalDate?
)

/**
 * Everything on the stats screen, worked out from what was actually recorded.
 *
 * ponytail: a session is counted on the day it started, so listening across midnight all lands on
 * the first day. Split sessions at midnight if that ever looks wrong.
 */
fun computeStats(
    sessions: List<ListeningSession>,
    completionTimes: List<Long>,
    doneByBook: Map<String, Int>,
    books: List<Book>,
    now: Long,
    zone: ZoneId,
    days: Int = 30,
    weeks: Int = 8,
    paceWindowDays: Int = 14
): ListeningStats {
    val today = dateOf(now, zone)
    val weekStart = mondayOf(today)
    val monthStart = today.withDayOfMonth(1)

    val byDay = HashMap<LocalDate, Long>()
    val parts = DayPart.values().associateWith { 0L }.toMutableMap()
    for (session in sessions) {
        val day = dateOf(session.startedAt, zone)
        byDay[day] = (byDay[day] ?: 0L) + session.wallMs
        val part = partOf(Instant.ofEpochMilli(session.startedAt).atZone(zone).hour)
        parts[part] = parts.getValue(part) + session.wallMs
    }

    fun totalSince(start: LocalDate): Long =
        byDay.filterKeys { !it.isBefore(start) && !it.isAfter(today) }.values.sum()

    val active = byDay.filterValues { it >= STREAK_DAY_MS }.keys
    // Today does not break a streak until it is over.
    var current = 0
    var cursor = if (today in active) today else today.minusDays(1)
    while (cursor in active) {
        current++
        cursor = cursor.minusDays(1)
    }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in active.sorted()) {
        run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
        longest = maxOf(longest, run)
        previous = day
    }

    val finishedByWeek = completionTimes.groupingBy { mondayOf(dateOf(it, zone)) }.eachCount()
    val lastWeeks = (weeks - 1 downTo 0).map { finishedByWeek[weekStart.minusWeeks(it.toLong())] ?: 0 }

    val paceStart = today.minusDays((paceWindowDays - 1).toLong())
    val series = books.map { book ->
        val mine = sessions.filter { it.bookId == book.id }
        val recentAudioMs = mine
            .filter { !dateOf(it.startedAt, zone).isBefore(paceStart) }
            .sumOf { it.audioMs }
        val perDay = recentAudioMs.toDouble() / paceWindowDays
        SeriesStats(
            bookId = book.id,
            title = book.title,
            startedOn = mine.minOfOrNull { it.startedAt }?.let { dateOf(it, zone) },
            daysActive = mine.map { dateOf(it.startedAt, zone) }.distinct().size,
            listenedMs = mine.sumOf { it.wallMs },
            chaptersDone = doneByBook[book.id] ?: 0,
            chapterCount = book.chapterCount,
            finishOn = if (perDay > 0.0 && book.remainingMs > 0L) {
                today.plusDays(ceil(book.remainingMs / perDay).toLong())
            } else {
                null
            }
        )
    }

    val real = sessions.filter { it.wallMs >= MIN_SESSION_MS }
    return ListeningStats(
        todayMs = byDay[today] ?: 0L,
        weekMs = totalSince(weekStart),
        monthMs = totalSince(monthStart),
        allTimeMs = sessions.sumOf { it.wallMs },
        currentStreak = current,
        longestStreak = longest,
        lastDays = (days - 1 downTo 0).map { byDay[today.minusDays(it.toLong())] ?: 0L },
        dayParts = parts,
        sessionCount = real.size,
        averageSessionMs = if (real.isEmpty()) 0L else real.sumOf { it.wallMs } / real.size,
        longestSessionMs = real.maxOfOrNull { it.wallMs } ?: 0L,
        chaptersThisWeek = lastWeeks.last(),
        lastWeeksChapters = lastWeeks,
        series = series,
        countingSince = sessions.minOfOrNull { it.startedAt }?.let { dateOf(it, zone) }
    )
}

private fun dateOf(epochMs: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

private fun mondayOf(day: LocalDate): LocalDate =
    day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun partOf(hour: Int): DayPart = when (hour) {
    in 5..11 -> DayPart.MORNING
    in 12..16 -> DayPart.AFTERNOON
    in 17..20 -> DayPart.EVENING
    else -> DayPart.NIGHT
}
