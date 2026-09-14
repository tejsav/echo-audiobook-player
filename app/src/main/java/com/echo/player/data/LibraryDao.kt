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
        "UPDATE chapters SET completed = 1, listenedMs = MAX(listenedMs, durationMs) " +
            "WHERE bookId = :bookId AND chapter_index = :chapterIndex"
    )
    suspend fun markCompleted(bookId: String, chapterIndex: Int)

    /** Clearing a mark also clears what was heard, so the row honestly reads as unplayed again. */
    @Query(
        "UPDATE chapters SET completed = :completed, " +
            "listenedMs = CASE WHEN :completed THEN MAX(listenedMs, durationMs) ELSE 0 END " +
            "WHERE bookId = :bookId AND chapter_index = :chapterIndex"
    )
    suspend fun setCompleted(bookId: String, chapterIndex: Int, completed: Boolean)

    @Query(
        "UPDATE chapters SET completed = 1, listenedMs = MAX(listenedMs, durationMs) " +
            "WHERE bookId = :bookId AND chapter_index < :chapterIndex"
    )
    suspend fun completeBefore(bookId: String, chapterIndex: Int)

    @Transaction
    suspend fun replaceBook(book: Book, chapters: List<Chapter>) {
        upsertBook(book)
        deleteChapters(book.id)
        upsertChapters(chapters)
    }
}
