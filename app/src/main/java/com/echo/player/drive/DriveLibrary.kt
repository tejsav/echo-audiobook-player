package com.echo.player.drive

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.echo.player.BuildConfig
import com.echo.player.data.BookImporter
import com.echo.player.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "DriveLibrary"
private const val LIBRARY_DIR = "Library"
private const val MANIFEST = ".echo-manifest.json"

/**
 * Where a Drive book's files live, or null for any other series. ECHO's own storage: nothing to
 * pick and no permission to ask for, but it is removed along with the app.
 */
internal fun downloadDirFor(context: Context, sourceUri: String): File? {
    if (!sourceUri.startsWith(DRIVE_SOURCE_PREFIX)) return null
    val base = context.getExternalFilesDir(null) ?: return null
    return File(File(base, LIBRARY_DIR), sourceUri.removePrefix(DRIVE_SOURCE_PREFIX))
}

data class Catalog(val link: String, val name: String, val books: List<RemoteBook>)

/**
 * Drive catalogs: reading them, downloading books through Android's download manager, and adding
 * each book to the library once every file is on the phone.
 *
 * Nothing about a download is kept in the database. Its state is read from the files on disk, a
 * small manifest per book, and the download manager, so it survives the app being killed.
 */
class DriveLibrary(private val context: Context, private val repository: LibraryRepository) {

    val hasKey: Boolean = BuildConfig.DRIVE_API_KEY.isNotBlank()

    private val api = DriveApi(context, BuildConfig.DRIVE_API_KEY)
    private val finishing = Mutex()

    private val downloads: DownloadManager?
        get() = ContextCompat.getSystemService(context, DownloadManager::class.java)

    suspend fun readCatalog(link: String): Catalog = withContext(Dispatchers.IO) {
        if (!hasKey) throw DriveException("This build of ECHO has no Google Drive key.")
        val ref = parseFolderLink(link) ?: throw DriveException("That is not a Google Drive folder link.")
        val root = api.readRoot(ref)
        Catalog(link, root.name, booksIn(root))
    }

    /** Address and headers for a book's cover, for the image loader. */
    fun coverRequest(book: RemoteBook): Pair<String, Map<String, String>>? =
        book.cover?.let { api.mediaUrl(it.id) to api.headers(it.id, it.resourceKey) }

    fun bookIdOf(book: RemoteBook): String = BookImporter.idFor(driveSourceUri(book.folderId))

    /** Every download ECHO has asked Android for that is still on record. */
    fun transfers(): List<Transfer> {
        val cursor = runCatching { downloads?.query(DownloadManager.Query()) }.getOrNull() ?: return emptyList()
        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val soFarCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            buildList {
                while (c.moveToNext()) {
                    val fileId = driveFileIdOf(c.getString(uriCol)) ?: continue
                    val status = c.getInt(statusCol)
                    val reason = c.getInt(reasonCol)
                    add(
                        Transfer(
                            downloadId = c.getLong(idCol),
                            fileId = fileId,
                            active = status == DownloadManager.STATUS_PENDING ||
                                status == DownloadManager.STATUS_RUNNING ||
                                status == DownloadManager.STATUS_PAUSED,
                            bytesSoFar = c.getLong(soFarCol).coerceAtLeast(0L),
                            waiting = status == DownloadManager.STATUS_PAUSED &&
                                (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ||
                                    reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK),
                            failedReason = if (status == DownloadManager.STATUS_FAILED) reason else null
                        )
                    )
                }
            }
        }
    }

    fun status(book: RemoteBook, transfers: List<Transfer>, chaptersInLibrary: Int?): BookStatus {
        val dir = downloadDirFor(context, driveSourceUri(book.folderId))
        val sizes = if (dir != null) localSizes(dir, book) else emptyMap()
        return statusOf(book, sizes, transfers, chaptersInLibrary)
    }

    /** Queues every file the phone does not have yet. Files already downloading are left alone. */
    fun download(book: RemoteBook, wifiOnly: Boolean) {
        val manager = downloads ?: throw DriveException("Downloads are not available on this phone.")
        val dir = downloadDirFor(context, driveSourceUri(book.folderId))
            ?: throw DriveException("Phone storage is not available right now.")
        dir.mkdirs()
        writeManifest(dir, book)

        val snapshot = transfers()
        val ids = book.files.mapTo(HashSet()) { it.id }
        val running = snapshot.filter { it.active }.mapTo(HashSet()) { it.fileId }
        // Clear old failures so the retry starts clean.
        snapshot.filter { it.failedReason != null && it.fileId in ids && it.fileId !in running }
            .forEach { manager.remove(it.downloadId) }

        for (file in missingFiles(book, localSizes(dir, book))) {
            if (file.id in running) continue
            val target = File(dir, file.path)
            target.parentFile?.mkdirs()
            // A leftover partial file would make the download manager pick a different name.
            target.delete()
            // The download manager treats this path as already encoded.
            val subPath = (listOf(LIBRARY_DIR, book.folderId) + file.path.split('/'))
                .joinToString("/") { Uri.encode(it) }
            val request = DownloadManager.Request(Uri.parse(api.mediaUrl(file.id)))
                .setDestinationInExternalFilesDir(context, null, subPath)
                .setTitle(book.title)
                .setDescription(file.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(!wifiOnly)
            for ((name, value) in api.headers(file.id, file.resourceKey)) request.addRequestHeader(name, value)
            manager.enqueue(request)
        }
    }

    /** Stops a book's downloads. Android deletes the partial files; finished ones stay. */
    fun cancel(book: RemoteBook) {
        val manager = downloads ?: return
        val ids = book.files.mapTo(HashSet()) { it.id }
        val running = transfers().filter { it.active && it.fileId in ids }.map { it.downloadId }
        if (running.isNotEmpty()) manager.remove(*running.toLongArray())
    }

    /**
     * Adds every book whose files are all on the phone to the library, or refreshes one that has
     * gained chapters. The series loaded in the player waits, because its queue is numbered by
     * chapter. Returns how many were added.
     */
    suspend fun finishDownloads(isLoaded: (String) -> Boolean): Int = finishing.withLock {
        withContext(Dispatchers.IO) {
            val base = context.getExternalFilesDir(null)?.let { File(it, LIBRARY_DIR) }
                ?: return@withContext 0
            val dirs = base.listFiles { file -> file.isDirectory }.orEmpty()
            if (dirs.isEmpty()) return@withContext 0
            val snapshot = transfers()
            var added = 0
            for (dir in dirs) {
                val book = readManifest(dir) ?: continue
                val sourceUri = driveSourceUri(book.folderId)
                val bookId = BookImporter.idFor(sourceUri)
                val inLibrary = repository.getBook(bookId)?.chapterCount
                val state = statusOf(book, localSizes(dir, book), snapshot, inLibrary).state
                if (state != DownloadState.ADDING || isLoaded(bookId)) continue
                runCatching { repository.importDownloaded(dir, sourceUri, book.title) }
                    .onSuccess { added++ }
                    .onFailure { Log.w(TAG, "Could not add " + book.title, it) }
            }
            added
        }
    }

    private fun localSizes(dir: File, book: RemoteBook): Map<String, Long> =
        book.files.mapNotNull { file ->
            File(dir, file.path).takeIf { it.isFile }?.let { file.path to it.length() }
        }.toMap()

    private fun writeManifest(dir: File, book: RemoteBook) {
        val files = JSONArray()
        book.files.forEach { file ->
            files.put(
                JSONObject()
                    .put("id", file.id)
                    .put("name", file.name)
                    .put("path", file.path)
                    .put("size", file.size ?: -1L)
                    .put("resourceKey", file.resourceKey ?: JSONObject.NULL)
                    .put("audio", file.isAudio)
            )
        }
        val manifest = JSONObject()
            .put("folderId", book.folderId)
            .put("title", book.title)
            .put("resourceKey", book.resourceKey ?: JSONObject.NULL)
            .put("files", files)
        File(dir, MANIFEST).writeText(manifest.toString())
    }

    private fun readManifest(dir: File): RemoteBook? = runCatching {
        val root = JSONObject(File(dir, MANIFEST).readText())
        val list = root.getJSONArray("files")
        val files = (0 until list.length()).map { i ->
            val f = list.getJSONObject(i)
            RemoteFile(
                id = f.getString("id"),
                name = f.getString("name"),
                path = f.getString("path"),
                size = f.optLong("size", -1L).takeIf { it >= 0L },
                resourceKey = if (f.isNull("resourceKey")) null else f.optString("resourceKey"),
                isAudio = f.optBoolean("audio")
            )
        }
        RemoteBook(
            folderId = root.getString("folderId"),
            title = root.getString("title"),
            resourceKey = if (root.isNull("resourceKey")) null else root.optString("resourceKey"),
            files = files
        )
    }.getOrNull()
}
