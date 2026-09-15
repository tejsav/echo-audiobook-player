package com.echo.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM books ORDER BY lastPlayedAt DESC, addedAt DESC")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    fun observeLastPlayed(): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: String): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): Book?

    @Query("SELECT * FROM books WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getBookBySource(sourceUri: String): Book?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapter_index ASC")
    suspend fun getChapters(bookId: String): List<Chapter>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapter_index ASC")
    fun observeChapters(bookId: String): Flow<List<Chapter>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<Chapter>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChapters(bookId: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    /**
     * The resume write. Deliberately a narrow UPDATE so it is cheap enough to call every few
     * seconds during playback without touching the rest of the row.
     */
    @Query(
        """
        UPDATE books
        SET currentChapterIndex = :chapterIndex,
            currentPositionMs = :positionMs,
            lastPlayedAt = :timestamp,
            elapsedMs = :positionMs + IFNULL(
                (SELECT startOffsetMs FROM chapters
                 WHERE bookId = :bookId AND chapter_index = :chapterIndex),
                0
            ),
            currentChapterTitle =
                (SELECT title FROM chapters
                 WHERE bookId = :bookId AND chapter_index = :chapterIndex),
            currentChapterDurationMs = IFNULL(
                (SELECT durationMs FROM chapters
                 WHERE bookId = :bookId AND chapter_index = :chapterIndex),
                0
            )
        WHERE id = :bookId
        """
    )
    suspend fun updateProgress(bookId: String, chapterIndex: Int, positionMs: Long, timestamp: Long)

    @Query("UPDATE books SET playbackSpeed = :speed WHERE id = :bookId")
    suspend fun updateSpeed(bookId: String, speed: Float)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :bookId")
    suspend fun updateDetails(bookId: String, title: String, author: String?)

    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateCover(bookId: String, coverPath: String?)

    /** Extends the heard mark; never moves it backwards. */
    @Query(
        "UPDATE chapters SET listenedMs = MAX(listenedMs, :positionMs) " +
            "WHERE bookId = :bookId AND chapter_index = :chapterIndex"
    )
    suspend fun recordListened(bookId: String, chapterIndex: Int, positionMs: Long)

    @Query(
        "UPDATE chapters SET completed = 1, listenedMs = MAX(listenedMs, durationMs), " +
            "completedAt = IFNULL(completedAt, :finishedAt) " +
            "WHERE bookId = :bookId AND chapter_index = :chapterIndex"
    )
    suspend fun markCompleted(bookId: String, chapterIndex: Int, finishedAt: Long)

    /** Clearing a mark also clears what was heard, so the row honestly reads as unplayed again. */
    @Query(
        "UPDATE chapters SET completed = :completed, " +
            "listenedMs = CASE WHEN :completed THEN MAX(listenedMs, durationMs) ELSE 0 END, " +
            "completedAt = CASE WHEN :completed THEN completedAt ELSE NULL END " +
            "WHERE bookId = :bookId AND chapter_index = :chapterIndex"
    )
    suspend fun setCompleted(bookId: String, chapterIndex: Int, completed: Boolean)

    @Query(
        "UPDATE chapters SET completed = 1, listenedMs = MAX(listenedMs, durationMs) " +
            "WHERE bookId = :bookId AND chapter_index < :chapterIndex"
    )
    suspend fun completeBefore(bookId: String, chapterIndex: Int)

    // -- listening log -------------------------------------------------------------------------

    /** Inserts a new session, or rewrites one still in progress (same id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: ListeningSession): Long

    @Insert
    suspend fun insertSessions(sessions: List<ListeningSession>)

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt ASC")
    fun observeSessions(): Flow<List<ListeningSession>>

    @Query("SELECT * FROM listening_sessions")
    suspend fun allSessions(): List<ListeningSession>

    @Query("UPDATE listening_sessions SET bookId = :newId WHERE bookId = :oldId")
    suspend fun moveSessions(oldId: String, newId: String)

    @Query("SELECT completedAt FROM chapters WHERE completedAt IS NOT NULL")
    fun observeCompletionTimes(): Flow<List<Long>>

    @Query("SELECT bookId, COUNT(*) AS done FROM chapters WHERE completed = 1 GROUP BY bookId")
    fun observeDoneCounts(): Flow<List<DoneCount>>

    // -- bookmarks ------------------------------------------------------------------------------

    @Insert
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Insert
    suspend fun insertBookmarks(bookmarks: List<Bookmark>)

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateBookmarkNote(id: Long, note: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksFor(bookId: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeBookmarks(bookId: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks")
    suspend fun allBookmarks(): List<Bookmark>

    // -- names, backup and restore --------------------------------------------------------------

    @Query("UPDATE books SET tidyNames = :tidy WHERE id = :bookId")
    suspend fun updateTidyNames(bookId: String, tidy: Boolean)

    @Query("UPDATE books SET namePrefix = :prefix WHERE id = :bookId")
    suspend fun updateNamePrefix(bookId: String, prefix: String)

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<Book>

    @Query("SELECT * FROM books WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun lastPlayedOnce(): Book?

    @Query(
        "UPDATE books SET title = :title, author = :author, playbackSpeed = :speed, " +
            "tidyNames = :tidy WHERE id = :bookId"
    )
    suspend fun restoreDetails(bookId: String, title: String, author: String?, speed: Float, tidy: Boolean)

    @Query(
        "UPDATE chapters SET listenedMs = MAX(listenedMs, :listenedMs), " +
            "completed = MAX(completed, :completed), " +
            "completedAt = IFNULL(completedAt, :completedAt) " +
            "WHERE bookId = :bookId AND chapter_index = :chapterIndex"
    )
    suspend fun mergeChapterMarks(
        bookId: String,
        chapterIndex: Int,
        listenedMs: Long,
        completed: Boolean,
        completedAt: Long?
    )

    @Transaction
    suspend fun replaceBook(book: Book, chapters: List<Chapter>) {
        upsertBook(book)
        deleteChapters(book.id)
        upsertChapters(chapters)
    }
}
