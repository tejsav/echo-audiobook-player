package com.echo.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Book::class, Chapter::class, ListeningSession::class, Bookmark::class],
    version = 4,
    exportSchema = true
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

        /**
         * Adds the listening log, bookmarks, tidy chapter names and finish times. Nothing is
         * backfilled: listening before this version was never recorded, so it is not invented.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN tidyNames INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN namePrefix TEXT")
                db.execSQL("ALTER TABLE chapters ADD COLUMN completedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listening_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, " +
                        "`wallMs` INTEGER NOT NULL, `audioMs` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listening_sessions_bookId` " +
                        "ON `listening_sessions` (`bookId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listening_sessions_startedAt` " +
                        "ON `listening_sessions` (`startedAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`chapterUri` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
