package com.echo.player.stats

import com.echo.player.data.Book
import com.echo.player.data.ListeningSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ListeningStatsTest {

    private val zone = ZoneOffset.UTC

    /** Wednesday 16 September 2026, 20:00. */
    private val now = at(2026, 9, 16, 20)

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(startedAt: Long, minutes: Long, bookId: String = "b") = ListeningSession(
        bookId = bookId,
        startedAt = startedAt,
        endedAt = startedAt + minutes * 60_000L,
        wallMs = minutes * 60_000L,
        audioMs = minutes * 60_000L
    )

    private fun book(remainingMs: Long) = Book(
        id = "b",
        title = "Maha Geeta",
        author = null,
        sourceUri = "x",
        coverPath = null,
        chapterCount = 82,
        totalDurationMs = 100 * 3_600_000L,
        elapsedMs = 100 * 3_600_000L - remainingMs
    )

    @Test
    fun totalsStreaksAndTimeOfDay() {
        val sessions = listOf(
            session(at(2026, 9, 16, 9), 30),  // today, morning
            session(at(2026, 9, 15, 22), 60), // yesterday, night
            session(at(2026, 9, 14, 13), 10), // Monday, afternoon
            session(at(2026, 9, 10, 18), 45), // last week, evening; 11-13 missed
            session(at(2026, 9, 1, 7), 20)    // earlier this month
        )
        val stats = computeStats(sessions, emptyList(), emptyMap(), emptyList(), now, zone)

        assertEquals(30 * 60_000L, stats.todayMs)
        assertEquals(100 * 60_000L, stats.weekMs)
        assertEquals(165 * 60_000L, stats.monthMs)
        assertEquals(165 * 60_000L, stats.allTimeMs)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        assertEquals(30, stats.lastDays.size)
        assertEquals(30 * 60_000L, stats.lastDays.last())
        assertEquals(50 * 60_000L, stats.dayParts.getValue(DayPart.MORNING))
        assertEquals(60 * 60_000L, stats.dayParts.getValue(DayPart.NIGHT))
        assertEquals(60 * 60_000L, stats.longestSessionMs)
        assertEquals(33 * 60_000L, stats.averageSessionMs)
        assertEquals(LocalDate.of(2026, 9, 1), stats.countingSince)
    }

    @Test
    fun todayDoesNotBreakAStreakUntilItIsOver() {
        val sessions = listOf(session(at(2026, 9, 15, 10), 20), session(at(2026, 9, 14, 10), 20))
        assertEquals(2, computeStats(sessions, emptyList(), emptyMap(), emptyList(), now, zone).currentStreak)
    }

    @Test
    fun briefPlaysCountAsTimeButNotAsSessionsOrStreakDays() {
        val stats = computeStats(
            listOf(session(at(2026, 9, 16, 10), 2), session(at(2026, 9, 16, 11), 0)),
            emptyList(), emptyMap(), emptyList(), now, zone
        )
        assertEquals(0, stats.currentStreak)
        assertEquals(2 * 60_000L, stats.todayMs)
        assertEquals(1, stats.sessionCount)
    }

    @Test
    fun finishDateFollowsRecentPaceAndNeedsListeningToExist() {
        // One hour of recording a day for 14 days, 28 hours left: 28 days from today.
        val sessions = (0 until 14).map { session(at(2026, 9, 16, 12) - it * 86_400_000L, 60) }
        val stats = computeStats(
            sessions, emptyList(), mapOf("b" to 19), listOf(book(28 * 3_600_000L)), now, zone
        )
        val series = stats.series.single()
        assertEquals(LocalDate.of(2026, 10, 14), series.finishOn)
        assertEquals(19, series.chaptersDone)
        assertEquals(14, series.daysActive)

        val idle = computeStats(
            emptyList(), emptyList(), emptyMap(), listOf(book(28 * 3_600_000L)), now, zone
        )
        assertNull(idle.series.single().finishOn)
    }

    @Test
    fun countsChaptersFinishedEachWeek() {
        val completions = listOf(at(2026, 9, 16, 8), at(2026, 9, 14, 8), at(2026, 9, 9, 8))
        val stats = computeStats(emptyList(), completions, emptyMap(), emptyList(), now, zone)
        assertEquals(2, stats.chaptersThisWeek)
        assertEquals(listOf(1, 2), stats.lastWeeksChapters.takeLast(2))
    }
}
