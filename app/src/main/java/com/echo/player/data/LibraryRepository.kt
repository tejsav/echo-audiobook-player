package com.echo.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.echo.player.drive.DRIVE_SOURCE_PREFIX
import com.echo.player.drive.downloadDirFor
import com.echo.player.util.sharedPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Single source of truth for the library and, crucially, for the resume position of every book.
 */
class LibraryRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).libraryDao()

    val books: Flow<List<Book>> = dao.observeBooks()

    /** The book to offer under "Continue listening", or null if nothing has been played yet. */
    val lastPlayed: Flow<Book?> = dao.observeLastPlayed()

    fun book(bookId: String): Flow<Book?> = dao.observeBook(bookId)

    val sessions: Flow<List<ListeningSession>> = dao.observeSessions()
    val completionTimes: Flow<List<Long>> = dao.observeCompletionTimes()
    val doneCounts: Flow<List<DoneCount>> = dao.observeDoneCounts()

    fun bookmarks(bookId: String): Flow<List<Bookmark>> = dao.observeBookmarks(bookId)

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

    /** The series to pick up from outside the app: a widget, a headset, the system media card. */
    suspend fun lastPlayed(): BookWithChapters? {
        val book = dao.lastPlayedOnce() ?: return null
        return BookWithChapters(book, dao.getChapters(book.id))
    }

    suspend fun saveSession(session: ListeningSession): Long = dao.saveSession(session)

    suspend fun addBookmark(bookId: String, chapterUri: String, positionMs: Long): Long =
        dao.insertBookmark(
            Bookmark(
                bookId = bookId,
                chapterUri = chapterUri,
                positionMs = positionMs.coerceAtLeast(0L),
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun setBookmarkNote(id: Long, note: String) = dao.updateBookmarkNote(id, note.trim())

    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    suspend fun setTidyNames(bookId: String, tidy: Boolean) = dao.updateTidyNames(bookId, tidy)

    /** Series imported before tidy names existed have no shared prefix worked out yet. */
    suspend fun fillNamePrefixes() {
        dao.allBooks().filter { it.namePrefix == null }.forEach { book ->
            dao.updateNamePrefix(book.id, sharedPrefix(dao.getChapters(book.id).map { it.title }))
        }
    }

    /**
     * Brings in files added to a series folder since it was imported. Only ever adds: a folder
     * that seems to be missing files is left alone, so a flaky read can never drop chapters. The
     * series that is loaded in the player is skipped, because its queue is numbered by chapter.
     * Returns how many series gained chapters.
     */
    suspend fun pickUpNewFiles(isLoaded: (String) -> Boolean): Int {
        var updated = 0
        for (book in dao.allBooks()) {
            if (!book.sourceUri.startsWith("content://") || isLoaded(book.id)) continue
            val tree = Uri.parse(book.sourceUri)
            val found = runCatching { BookImporter.scanTree(context, tree) }.getOrNull() ?: continue
            val known = dao.getChapters(book.id).mapTo(HashSet()) { it.uri }
            if (found.all { it in known } || known.any { it !in found }) continue
            val imported = runCatching { BookImporter.importTree(context, tree) { } }
                .onFailure { Log.w(TAG, "Could not refresh " + book.title, it) }
                .getOrNull() ?: continue
            if (isLoaded(book.id)) continue
            commit(imported)
            updated++
        }
        return updated
    }

    suspend fun saveSpeed(bookId: String, speed: Float) = dao.updateSpeed(bookId, speed)

    suspend fun recordListened(bookId: String, chapterIndex: Int, positionMs: Long) =
        dao.recordListened(bookId, chapterIndex, positionMs.coerceAtLeast(0L))

    suspend fun markCompleted(bookId: String, chapterIndex: Int) =
        dao.markCompleted(bookId, chapterIndex, System.currentTimeMillis())

    suspend fun setCompleted(bookId: String, chapterIndex: Int, completed: Boolean) =
        dao.setCompleted(bookId, chapterIndex, completed)

    suspend fun completeBefore(bookId: String, chapterIndex: Int) =
        dao.completeBefore(bookId, chapterIndex)

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
        val old = if (existing != null) dao.getChapters(existing.id) else emptyList()
        val prefix = sharedPrefix(imported.chapters.map { it.title })
        val book = if (existing != null) {
            // Follow the chapter you were in by its file, so a file added earlier in the folder
            // does not move your place to a different chapter.
            val currentUri = old.getOrNull(existing.currentChapterIndex)?.uri
            val index = imported.chapters.indexOfFirst { it.uri == currentUri }
                .takeIf { it >= 0 }
                ?: existing.currentChapterIndex
                    .coerceAtMost((imported.chapters.size - 1).coerceAtLeast(0))
            val current = imported.chapters.getOrNull(index)
            // The importer extracts embedded art afresh; the listener's own choice stays.
            if (imported.book.coverPath != existing.coverPath) CoverStore.delete(imported.book.coverPath)
            imported.book.copy(
                title = existing.title,
                author = existing.author,
                coverPath = existing.coverPath,
                currentChapterIndex = index,
                currentPositionMs = existing.currentPositionMs,
                playbackSpeed = existing.playbackSpeed,
                addedAt = existing.addedAt,
                lastPlayedAt = existing.lastPlayedAt,
                elapsedMs = current?.let { it.startOffsetMs + existing.currentPositionMs }
                    ?: existing.elapsedMs,
                currentChapterTitle = current?.title ?: existing.currentChapterTitle,
                currentChapterDurationMs = current?.durationMs ?: existing.currentChapterDurationMs,
                tidyNames = existing.tidyNames,
                namePrefix = prefix
            )
        } else {
            imported.book.copy(namePrefix = prefix)
        }
        // Keep what the listener has heard. Matched by file, so a reordered folder still carries
        // its marks to the right chapters.
        val heard = old.associateBy { it.uri }
        val chapters = imported.chapters.map { chapter ->
            heard[chapter.uri]
                ?.let {
                    chapter.copy(
                        listenedMs = it.listenedMs,
                        completed = it.completed,
                        completedAt = it.completedAt
                    )
                }
                ?: chapter
        }
        dao.replaceBook(book, chapters)
        return book
    }

    // -- backup and restore ---------------------------------------------------------------------

    /** Everything the listener has built up: places, marks, names, covers, bookmarks and the log. */
    suspend fun writeBackup(target: Uri) = withContext(Dispatchers.IO) {
        val bookmarks = dao.allBookmarks().groupBy { it.bookId }
        val backup = Backup(
            exportedAt = System.currentTimeMillis(),
            books = dao.allBooks().map { book ->
                BackupBook(
                    book = book,
                    chapters = dao.getChapters(book.id).map {
                        BackupChapter(it.title, it.uri, it.listenedMs, it.completed, it.completedAt)
                    },
                    bookmarks = bookmarks[book.id].orEmpty().map {
                        BackupBookmark(it.chapterUri, it.positionMs, it.note, it.createdAt)
                    },
                    coverJpeg = book.coverPath?.let { runCatching { File(it).readBytes() }.getOrNull() }
                )
            },
            sessions = dao.allSessions()
        )
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IOException("The backup could not be written there.")
        stream.use { it.write(BackupCodec.encode(backup).toByteArray()) }
    }

    suspend fun readBackup(source: Uri): Backup = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(source)
            ?: throw IOException("That file could not be opened.")
        BackupCodec.decode(stream.use { it.readBytes().decodeToString() })
    }

    /**
     * Restores the listening log, and everything belonging to series already on this device.
     * Returns the series whose folders still have to be picked. Restoring twice adds nothing twice.
     */
    suspend fun restore(backup: Backup): List<BackupBook> {
        val logged = dao.allSessions().mapTo(HashSet()) { it.bookId + "|" + it.startedAt }
        dao.insertSessions(
            backup.sessions
                .filter { (it.bookId + "|" + it.startedAt) !in logged }
                .map { it.copy(id = 0L) }
        )
        val unlinked = mutableListOf<BackupBook>()
        for (saved in backup.books) {
            when {
                dao.getBook(saved.book.id) != null -> applyBackup(saved.book.id, saved)
                // A Drive series comes back by downloading it again; its history waits for it.
                saved.book.sourceUri.startsWith(DRIVE_SOURCE_PREFIX) -> savePendingRestore(saved)
                else -> unlinked += saved
            }
        }
        return unlinked
    }

    /** Imports the folder picked for a series from a backup, then puts its history back on it. */
    suspend fun relink(
        saved: BackupBook,
        treeUri: Uri,
        onProgress: suspend (BookImporter.Progress) -> Unit
    ): Book {
        val book = importFolder(treeUri, onProgress)
        if (book.id != saved.book.id) dao.moveSessions(saved.book.id, book.id)
        applyBackup(book.id, saved)
        return dao.getBook(book.id) ?: book
    }

    private suspend fun applyBackup(bookId: String, saved: BackupBook) {
        val chapters = dao.getChapters(bookId)
        val byUri = chapters.associateBy { it.uri }
        val byPosition = matchChapters(saved.chapters.map { it.title }, chapters.map { it.title })

        // Same device: the files are the same, so match on them. Elsewhere: by name and order.
        fun targetOf(oldIndex: Int): Chapter? {
            val old = saved.chapters.getOrNull(oldIndex) ?: return null
            return byUri[old.uri] ?: byPosition[oldIndex]?.let { chapters.getOrNull(it) }
        }

        saved.chapters.forEachIndexed { oldIndex, old ->
            val target = targetOf(oldIndex) ?: return@forEachIndexed
            dao.mergeChapterMarks(bookId, target.index, old.listenedMs, old.completed, old.completedAt)
        }

        val b = saved.book
        dao.restoreDetails(bookId, b.title, b.author, b.playbackSpeed, b.tidyNames)

        // The newer place wins, so restoring an old backup never throws away recent listening.
        val current = dao.getBook(bookId)
        val resumeAt = targetOf(b.currentChapterIndex)
        if (resumeAt != null && b.lastPlayedAt > (current?.lastPlayedAt ?: 0L)) {
            dao.updateProgress(bookId, resumeAt.index, b.currentPositionMs, b.lastPlayedAt)
        }

        saved.coverJpeg?.let { bytes ->
            CoverStore.saveBytes(context, bookId, bytes)?.let { path ->
                dao.updateCover(bookId, path)
                CoverStore.deleteOthers(context, bookId, keep = path)
            }
        }

        val oldIndexByUri = saved.chapters.withIndex().associate { it.value.uri to it.index }
        val already = dao.allBookmarks().filter { it.bookId == bookId }.mapTo(HashSet()) { it.createdAt }
        dao.insertBookmarks(
            saved.bookmarks.mapNotNull { mark ->
                if (mark.createdAt in already) return@mapNotNull null
                val target = oldIndexByUri[mark.chapterUri]?.let(::targetOf) ?: return@mapNotNull null
                Bookmark(
                    bookId = bookId,
                    chapterUri = target.uri,
                    positionMs = mark.positionMs,
                    note = mark.note,
                    createdAt = mark.createdAt
                )
            }
        )
    }

    /** Adds (or refreshes) a book whose files have finished downloading into ECHO's storage. */
    suspend fun importDownloaded(dir: File, sourceUri: String, title: String): Book {
        val book = commit(BookImporter.importDirectory(context, dir, sourceUri, title) { })
        takePendingRestore(book.id)?.let { applyBackup(book.id, it) }
        return dao.getBook(book.id) ?: book
    }

    private fun pendingRestoreFile(bookId: String) = File(context.filesDir, "pending-restore/$bookId.json")

    private suspend fun savePendingRestore(saved: BackupBook) = withContext(Dispatchers.IO) {
        val file = pendingRestoreFile(saved.book.id)
        file.parentFile?.mkdirs()
        file.writeText(BackupCodec.encode(Backup(0L, listOf(saved), emptyList())))
    }

    private suspend fun takePendingRestore(bookId: String): BackupBook? = withContext(Dispatchers.IO) {
        val file = pendingRestoreFile(bookId)
        if (!file.exists()) return@withContext null
        runCatching { BackupCodec.decode(file.readText()).books.firstOrNull() }
            .getOrNull()
            .also { file.delete() }
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        dao.deleteBook(book.id)
        // Listening time stays in the log on purpose; bookmarks have nothing left to point at.
        dao.deleteBookmarksFor(book.id)
        CoverStore.deleteOthers(context, book.id, keep = null)
        release(book.sourceUri)
        // A downloaded Drive book's files belong to the series; they go with it.
        downloadDirFor(context, book.sourceUri)?.deleteRecursively()
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
