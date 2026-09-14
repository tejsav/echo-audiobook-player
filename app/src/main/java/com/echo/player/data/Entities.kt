package com.echo.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audiobook. [currentChapterIndex] + [currentPositionMs] are the resume point and are the
 * reason this table exists: they are written continuously while playing so that the app can always
 * pick up exactly where the listener left off, even after a crash or a reboot.
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    /** Tree uri for folder imports, or a synthetic `files:` id for multi-file imports. */
    val sourceUri: String,
    /** Absolute path of the extracted cover art in app storage, if any. */
    val coverPath: String?,
    val chapterCount: Int,
    val totalDurationMs: Long,
    val currentChapterIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0L,
    /**
     * Denormalised "listened so far", kept in step by the same UPDATE that writes the resume
     * point, so the library list can show progress without loading every chapter.
     */
    val elapsedMs: Long = 0L,
    /**
     * The chapter you are in, copied onto the book row. The disc carousel draws every series from
     * a single row; without this it would have to load each book's chapters just to render.
     */
    val currentChapterTitle: String? = null,
    val currentChapterDurationMs: Long = 0L
) {
    val progress: Float
        get() = if (totalDurationMs > 0L) {
            (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val remainingMs: Long get() = (totalDurationMs - elapsedMs).coerceAtLeast(0L)

    /** How far through the current chapter, which is what the dial ring shows. */
    val chapterProgress: Float
        get() = if (currentChapterDurationMs > 0L) {
            (currentPositionMs.toFloat() / currentChapterDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val chapterRemainingMs: Long
        get() = (currentChapterDurationMs - currentPositionMs).coerceAtLeast(0L)

    val hasProgress: Boolean get() = lastPlayedAt > 0L

    val isFinished: Boolean get() = totalDurationMs > 0L && remainingMs < FINISHED_SLACK_MS

    private companion object {
        const val FINISHED_SLACK_MS = 30_000L
    }
}

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "chapter_index"],
    indices = [Index("bookId")],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Chapter(
    val bookId: String,
    @ColumnInfo(name = "chapter_index") val index: Int,
    val title: String,
    val uri: String,
    val durationMs: Long,
    /** Milliseconds of the book that come before this chapter. Prefix sum, fixed at import. */
    val startOffsetMs: Long = 0L,
    /**
     * Furthest point reached by the playback heartbeat. Seeks never write it, so jumping past a
     * chapter leaves it reading as unheard instead of finished.
     */
    @ColumnInfo(defaultValue = "0") val listenedMs: Long = 0L,
    /** Set only when the chapter plays through to its end, or when the listener marks it. */
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false
) {
    val listenedFraction: Float
        get() = if (durationMs > 0L) {
            (listenedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/** A book together with its chapters — what the player needs to build a queue. */
data class BookWithChapters(
    val book: Book,
    val chapters: List<Chapter>
)
