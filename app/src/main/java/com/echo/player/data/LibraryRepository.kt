package com.echo.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the library and, crucially, for the resume position of every book.
 */
class LibraryRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).libraryDao()

    val books: Flow<List<Book>> = dao.observeBooks()

    /** The book to offer under "Continue listening", or null if nothing has been played yet. */
    val lastPlayed: Flow<Book?> = dao.observeLastPlayed()

    fun book(bookId: String): Flow<Book?> = dao.observeBook(bookId)

    fun chapters(bookId: String): Flow<List<Chapter>> = dao.observeChapters(bookId)

    suspend fun getBook(bookId: String): Book? = dao.getBook(bookId)

    suspend fun bookWithChapters(bookId: String): BookWithChapters? {
        val book = dao.getBook(bookId) ?: return null
        return BookWithChapters(book, dao.getChapters(bookId))
    }

    /**
     * Writes the resume point. Called every few seconds while playing, and on every pause,
     * chapter change and service teardown.
     */
    suspend fun saveProgress(bookId: String, chapterIndex: Int, positionMs: Long) {
        dao.updateProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun saveSpeed(bookId: String, speed: Float) = dao.updateSpeed(bookId, speed)

    /** Renames a series and, optionally, its author line. */
    suspend fun rename(bookId: String, title: String, author: String?) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        dao.updateDetails(bookId, cleanTitle, author?.trim()?.ifBlank { null })
    }

    /**
     * Copies a picture chosen from the gallery into app storage and makes it this series' cover.
     * Returns the stored path, or null if the picture could not be read.
     */
    suspend fun setCover(bookId: String, imageUri: Uri): String? {
        val stored = CoverStore.saveFromUri(context, bookId, imageUri) ?: return null
        dao.updateCover(bookId, stored)
        // Drop any previous cover only once the new one is safely in the database.
        CoverStore.deleteOthers(context, bookId, keep = stored)
        return stored
    }

    suspend fun clearCover(bookId: String) {
        dao.updateCover(bookId, null)
        CoverStore.deleteOthers(context, bookId, keep = null)
    }

    suspend fun importFolder(
        treeUri: Uri,
        onProgress: suspend (BookImporter.Progress) -> Unit
    ): Book {
        persist(treeUri)
        val imported = BookImporter.importTree(context, treeUri, onProgress)
        return commit(imported)
    }

    suspend fun importFiles(
        uris: List<Uri>,
        onProgress: suspend (BookImporter.Progress) -> Unit
    ): Book {
        uris.forEach { persist(it) }
        val imported = BookImporter.importFiles(context, uris, onProgress)
        return commit(imported)
    }

    /**
     * Saves the imported book. Re-importing a folder refreshes its chapters but must never reset
     * the things the listener owns: where they left off, and the name and cover they chose.
     */
    private suspend fun commit(imported: BookWithChapters): Book {
        val existing = dao.getBook(imported.book.id)
        val book = if (existing != null) {
            imported.book.copy(
                title = existing.title,
                author = existing.author,
                coverPath = existing.coverPath,
                currentChapterIndex = existing.currentChapterIndex
                    .coerceAtMost((imported.chapters.size - 1).coerceAtLeast(0)),
                currentPositionMs = existing.currentPositionMs,
                playbackSpeed = existing.playbackSpeed,
                addedAt = existing.addedAt,
                lastPlayedAt = existing.lastPlayedAt,
                elapsedMs = existing.elapsedMs,
                currentChapterTitle = imported.chapters
                    .getOrNull(existing.currentChapterIndex)?.title
                    ?: existing.currentChapterTitle,
                currentChapterDurationMs = imported.chapters
                    .getOrNull(existing.currentChapterIndex)?.durationMs
                    ?: existing.currentChapterDurationMs
            )
        } else {
            imported.book
        }
        dao.replaceBook(book, imported.chapters)
        return book
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        dao.deleteBook(book.id)
        CoverStore.deleteOthers(context, book.id, keep = null)
        release(book.sourceUri)
    }

    /** Keeps read access across reboots for a document or tree the user picked. */
    private fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { Log.w(TAG, "Could not persist access to " + uri, it) }
    }

    private fun release(sourceUri: String) {
        if (!sourceUri.startsWith("content://")) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(sourceUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private companion object {
        const val TAG = "LibraryRepository"
    }
}
