package com.echo.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Book::class, Chapter::class],
    version = 2,
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

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "echo.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
