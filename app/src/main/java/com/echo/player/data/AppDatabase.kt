package com.echo.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Book::class, Chapter::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {

        /**
         * Adds the denormalised current-chapter columns. Written as a real migration rather than a
         * destructive fallback: resume positions are the one thing this app must never lose.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN currentChapterTitle TEXT")
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN currentChapterDurationMs " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                // Backfill from the chapter each book is currently on.
                db.execSQL(
                    """
                    UPDATE books SET
                        currentChapterTitle = (
                            SELECT title FROM chapters
                            WHERE chapters.bookId = books.id
                              AND chapters.chapter_index = books.currentChapterIndex
                        ),
                        currentChapterDurationMs = IFNULL((
                            SELECT durationMs FROM chapters
                            WHERE chapters.bookId = books.id
                              AND chapters.chapter_index = books.currentChapterIndex
                        ), 0)
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds listening history. Only what is actually known carries over: how far into its
         * current chapter each book is. Earlier chapters are left unmarked rather than guessed as
         * finished, because some of them may have been skipped; the track list offers a one-tap way
         * to mark them.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN listenedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chapters ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE chapters SET listenedMs = IFNULL((
                        SELECT currentPositionMs FROM books WHERE books.id = chapters.bookId
                    ), 0)
                    WHERE chapter_index = (
                        SELECT currentChapterIndex FROM books WHERE books.id = chapters.bookId
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "echo.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
