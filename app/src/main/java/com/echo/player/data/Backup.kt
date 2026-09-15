package com.echo.player.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class BackupChapter(
    val title: String,
    val uri: String,
    val listenedMs: Long,
    val completed: Boolean,
    val completedAt: Long?
)

data class BackupBookmark(
    val chapterUri: String,
    val positionMs: Long,
    val note: String,
    val createdAt: Long
)

class BackupBook(
    val book: Book,
    val chapters: List<BackupChapter>,
    val bookmarks: List<BackupBookmark>,
    val coverJpeg: ByteArray?
)

class Backup(
    val exportedAt: Long,
    val books: List<BackupBook>,
    val sessions: List<ListeningSession>
)

/**
 * The backup file: plain JSON, so it can be read, kept anywhere, and still opened years later.
 * Folder addresses are included but only work on the device that made them; elsewhere each series
 * is linked to its folder again.
 */
object BackupCodec {

    private const val FORMAT = 1

    fun encode(backup: Backup): String = JSONObject()
        .put("app", "ECHO")
        .put("format", FORMAT)
        .put("exportedAt", backup.exportedAt)
        .put("books", JSONArray().apply { backup.books.forEach { put(encodeBook(it)) } })
        .put("sessions", JSONArray().apply {
            backup.sessions.forEach { s ->
                put(
                    JSONObject()
                        .put("bookId", s.bookId)
                        .put("startedAt", s.startedAt)
                        .put("endedAt", s.endedAt)
                        .put("wallMs", s.wallMs)
                        .put("audioMs", s.audioMs)
                )
            }
        })
        .toString()

    private fun encodeBook(saved: BackupBook): JSONObject {
        val b = saved.book
        return JSONObject()
            .put("id", b.id)
            .put("title", b.title)
            .put("author", b.author ?: JSONObject.NULL)
            .put("sourceUri", b.sourceUri)
            .put("chapterCount", b.chapterCount)
            .put("totalDurationMs", b.totalDurationMs)
            .put("currentChapterIndex", b.currentChapterIndex)
            .put("currentPositionMs", b.currentPositionMs)
            .put("playbackSpeed", b.playbackSpeed.toDouble())
            .put("addedAt", b.addedAt)
            .put("lastPlayedAt", b.lastPlayedAt)
            .put("tidyNames", b.tidyNames)
            .put("cover", saved.coverJpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
            .put("chapters", JSONArray().apply {
                saved.chapters.forEach { c ->
                    put(
                        JSONObject()
                            .put("title", c.title)
                            .put("uri", c.uri)
                            .put("listenedMs", c.listenedMs)
                            .put("completed", c.completed)
                            .put("completedAt", c.completedAt ?: JSONObject.NULL)
                    )
                }
            })
            .put("bookmarks", JSONArray().apply {
                saved.bookmarks.forEach { m ->
                    put(
                        JSONObject()
                            .put("chapterUri", m.chapterUri)
                            .put("positionMs", m.positionMs)
                            .put("note", m.note)
                            .put("createdAt", m.createdAt)
                    )
                }
            })
    }

    /** Throws [IllegalArgumentException] for anything that is not an ECHO backup. */
    fun decode(text: String): Backup {
        val root = runCatching { JSONObject(text) }.getOrNull()
        require(root != null && root.optString("app") == "ECHO") { "That file is not an ECHO backup." }
        require(root.optInt("format") <= FORMAT) { "That backup comes from a newer version of ECHO." }
        return Backup(
            exportedAt = root.optLong("exportedAt"),
            books = root.optJSONArray("books").objects().map(::decodeBook),
            sessions = root.optJSONArray("sessions").objects().map { s ->
                ListeningSession(
                    bookId = s.getString("bookId"),
                    startedAt = s.getLong("startedAt"),
                    endedAt = s.getLong("endedAt"),
                    wallMs = s.getLong("wallMs"),
                    audioMs = s.getLong("audioMs")
                )
            }
        )
    }

    private fun decodeBook(o: JSONObject): BackupBook = BackupBook(
        book = Book(
            id = o.getString("id"),
            title = o.getString("title"),
            author = o.optStringOrNull("author"),
            sourceUri = o.getString("sourceUri"),
            coverPath = null,
            chapterCount = o.optInt("chapterCount"),
            totalDurationMs = o.optLong("totalDurationMs"),
            currentChapterIndex = o.optInt("currentChapterIndex"),
            currentPositionMs = o.optLong("currentPositionMs"),
            playbackSpeed = o.optDouble("playbackSpeed", 1.0).toFloat(),
            addedAt = o.optLong("addedAt"),
            lastPlayedAt = o.optLong("lastPlayedAt"),
            tidyNames = o.optBoolean("tidyNames")
        ),
        chapters = o.optJSONArray("chapters").objects().map { c ->
            BackupChapter(
                title = c.getString("title"),
                uri = c.getString("uri"),
                listenedMs = c.optLong("listenedMs"),
                completed = c.optBoolean("completed"),
                completedAt = if (c.isNull("completedAt")) null else c.optLong("completedAt")
            )
        },
        bookmarks = o.optJSONArray("bookmarks").objects().map { m ->
            BackupBookmark(
                chapterUri = m.getString("chapterUri"),
                positionMs = m.optLong("positionMs"),
                note = m.optString("note"),
                createdAt = m.optLong("createdAt")
            )
        },
        coverJpeg = o.optStringOrNull("cover")?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
}
