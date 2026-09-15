package com.echo.player

import com.echo.player.data.matchChapters
import com.echo.player.util.rewindAfter
import com.echo.player.util.sharedPrefix
import com.echo.player.util.tidyChapterTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLogicTest {

    @Test
    fun tidiesTheSharedFileNamePrefix() {
        val titles = (1..82).map { "OSHO-Maha_Geeta_" + it.toString().padStart(2, '0') }
        val prefix = sharedPrefix(titles)
        assertEquals("OSHO-Maha_Geeta_", prefix)
        assertEquals("Maha Geeta · 20", tidyChapterTitle("OSHO-Maha_Geeta_20", prefix, "Maha Geeta"))
    }

    @Test
    fun leavesNamesAloneWhenNothingIsShared() {
        val titles = listOf("01 - Arjuna Vishada Yoga", "02 - Sankhya Yoga")
        val prefix = sharedPrefix(titles)
        assertEquals("", prefix)
        assertEquals("01 - Arjuna Vishada Yoga", tidyChapterTitle(titles[0], prefix, "Gita"))
    }

    @Test
    fun neverSplitsAWordAndKeepsMeaningfulNames() {
        val titles = listOf("Book_Title_Chapter_One", "Book_Title_Chapter_Two")
        val prefix = sharedPrefix(titles)
        assertEquals("Book_Title_Chapter_", prefix)
        assertEquals("Two", tidyChapterTitle(titles[1], prefix, "Book"))
        assertEquals("", sharedPrefix(listOf("Solo")))
        assertEquals("My Talk", tidyChapterTitle("My_Talk", "", "Series"))
    }

    @Test
    fun rewindGrowsWithTimeAway() {
        assertEquals(5_000L, rewindAfter(60_000L))
        assertEquals(15_000L, rewindAfter(60 * 60_000L))
        assertEquals(30_000L, rewindAfter(5 * 3_600_000L))
        assertEquals(45_000L, rewindAfter(3 * 86_400_000L))
    }

    @Test
    fun matchesBackupChaptersWithoutAttachingToTheWrongFile() {
        assertEquals(mapOf(0 to 0, 1 to 1), matchChapters(listOf("a", "b"), listOf("a", "b")))
        // A different folder that happens to hold the same number of files is not paired up.
        assertEquals(emptyMap<Int, Int>(), matchChapters(listOf("a", "b"), listOf("x", "y")))
        // A folder that has gained a file is paired by name.
        assertEquals(mapOf(0 to 0, 1 to 2), matchChapters(listOf("a", "c"), listOf("a", "b", "c")))
    }
}
