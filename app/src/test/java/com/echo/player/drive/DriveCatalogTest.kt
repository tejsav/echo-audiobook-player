package com.echo.player.drive

import com.echo.player.util.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveCatalogTest {

    private val id = "1AbCdEfGhIjK_-9"

    @Test
    fun readsTheLinksDriveHandsOut() {
        assertEquals(FolderRef(id), parseFolderLink("https://drive.google.com/drive/folders/$id?usp=sharing"))
        assertEquals(FolderRef(id), parseFolderLink("  https://drive.google.com/drive/u/1/folders/$id "))
        assertEquals(FolderRef(id), parseFolderLink("https://drive.google.com/open?id=$id"))
        assertEquals(
            FolderRef(id, "0-xyz"),
            parseFolderLink("https://drive.google.com/drive/folders/$id?resourcekey=0-xyz")
        )
        assertEquals(FolderRef(id), parseFolderLink(id))
        assertNull(parseFolderLink("https://example.com/folders/$id"))
        assertNull(parseFolderLink("not a link"))
    }

    private fun audio(id: String, name: String, size: Long = 100L) = DriveItem(id, name, "audio/mpeg", size)

    @Test
    fun eachFolderInACatalogIsABook() {
        val root = DriveFolder(
            id = "root",
            name = "Catalog",
            folders = listOf(
                DriveFolder("b10", "Book 10", files = listOf(audio("a1", "01.mp3"))),
                DriveFolder(
                    id = "b2",
                    name = "Book 2",
                    files = listOf(DriveItem("c", "cover.jpg", "image/jpeg", 50L)),
                    folders = listOf(
                        DriveFolder("cd1", "CD 1", files = listOf(audio("a2", "01.mp3"), audio("a3", "01.mp3", 200L)))
                    )
                ),
                DriveFolder("n", "Notes", files = listOf(DriveItem("d", "notes.pdf", "application/pdf", 5L)))
            )
        )
        val books = booksIn(root)
        assertEquals(listOf("Book 2", "Book 10"), books.map { it.title })
        val book = books.first()
        assertEquals(2, book.chapterCount)
        assertEquals(350L, book.totalBytes)
        assertEquals("c", book.cover?.id)
        assertEquals(listOf("cover.jpg", "CD 1/01.mp3", "CD 1/01-a3.mp3"), book.files.map { it.path })
    }

    @Test
    fun aFolderOfAudioIsASingleBookWithSafeFileNames() {
        val root = DriveFolder("root", "Talks", files = listOf(audio("a", "1.mp3"), audio("b", "a/b:c?.mp3")))
        val books = booksIn(root)
        assertEquals(1, books.size)
        assertEquals("root", books[0].folderId)
        assertEquals("a_b_c_.mp3", books[0].files[1].path)
    }

    private val book = RemoteBook(
        folderId = "f",
        title = "Book",
        resourceKey = null,
        files = listOf(
            RemoteFile("x", "1.mp3", "1.mp3", 100L, null, true),
            RemoteFile("y", "2.mp3", "2.mp3", 100L, null, true)
        )
    )

    @Test
    fun statusFollowsTheFilesAndTheDownloads() {
        assertEquals(DownloadState.NOT_DOWNLOADED, statusOf(book, emptyMap(), emptyList(), null).state)

        val running = statusOf(
            book,
            mapOf("1.mp3" to 100L, "2.mp3" to 40L),
            listOf(Transfer(1, "y", active = true, bytesSoFar = 40L, waiting = false, failedReason = null)),
            null
        )
        assertEquals(DownloadState.DOWNLOADING, running.state)
        assertEquals(1, running.filesDone)
        assertEquals(140L, running.bytesDone)

        val failed = statusOf(book, mapOf("1.mp3" to 100L), listOf(Transfer(2, "y", false, 0L, false, 403)), null)
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(403, failed.failureReason)

        val all = mapOf("1.mp3" to 100L, "2.mp3" to 100L)
        assertEquals(DownloadState.ADDING, statusOf(book, all, emptyList(), null).state)
        assertEquals(DownloadState.ADDING, statusOf(book, all, emptyList(), 1).state)
        assertEquals(DownloadState.IN_LIBRARY, statusOf(book, all, emptyList(), 2).state)

        val grown = statusOf(book, mapOf("1.mp3" to 100L), emptyList(), 1)
        assertEquals(DownloadState.NEW_CHAPTERS, grown.state)
        assertEquals(1, grown.newChapters)
    }

    @Test
    fun findsTheDriveFileInADownloadAddress() {
        assertEquals("abc_123", driveFileIdOf("https://www.googleapis.com/drive/v3/files/abc_123?alt=media&key=k"))
        assertNull(driveFileIdOf("https://example.com/x"))
        assertNull(driveFileIdOf(null))
    }

    @Test
    fun formatsSizesLikeThePhoneDoes() {
        assertEquals("1.2 GB", formatBytes(1_234_000_000L))
        assertEquals("340 MB", formatBytes(340_400_000L))
        assertEquals("12 KB", formatBytes(12_000L))
    }
}
