package com.echo.player.drive

import com.echo.player.data.isAudioName
import com.echo.player.data.isCoverName
import com.echo.player.util.NaturalOrder

const val FOLDER_MIME = "application/vnd.google-apps.folder"
const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

/** Where a downloaded Drive book came from, e.g. `drive:1AbC…`. Also what its id is made from. */
const val DRIVE_SOURCE_PREFIX = "drive:"

fun driveSourceUri(folderId: String): String = DRIVE_SOURCE_PREFIX + folderId

/** A shared folder. Folders shared before 2021 may also need their resource key. */
data class FolderRef(val id: String, val resourceKey: String? = null)

private const val DRIVE_ID = "[A-Za-z0-9_-]{10,}"
private val FOLDER_PATH = Regex("/folders/($DRIVE_ID)")
private val ID_PARAM = Regex("[?&]id=($DRIVE_ID)")
private val RESOURCE_KEY = Regex("[?&]resourcekey=([A-Za-z0-9_-]+)")
private val BARE_ID = Regex("^$DRIVE_ID$")
private val FILE_ID = Regex("/drive/v3/files/([^/?&]+)")
private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/** Reads the folder links Drive hands out, or a bare folder id. Anything else is null. */
fun parseFolderLink(text: String): FolderRef? {
    val link = text.trim()
    if (BARE_ID.matches(link)) return FolderRef(link)
    if (!link.contains("drive.google.com")) return null
    val id = (FOLDER_PATH.find(link) ?: ID_PARAM.find(link))?.groupValues?.get(1) ?: return null
    return FolderRef(id, RESOURCE_KEY.find(link)?.groupValues?.get(1))
}

/** The Drive file a download is for, read back from its address. */
fun driveFileIdOf(url: String?): String? = url?.let { FILE_ID.find(it)?.groupValues?.get(1) }

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val resourceKey: String? = null
)

data class DriveFolder(
    val id: String,
    val name: String,
    val resourceKey: String? = null,
    val files: List<DriveItem> = emptyList(),
    val folders: List<DriveFolder> = emptyList()
)

/** One file of a book, and where it goes under the book's folder on the phone. */
data class RemoteFile(
    val id: String,
    val name: String,
    val path: String,
    val size: Long?,
    val resourceKey: String?,
    val isAudio: Boolean
)

data class RemoteBook(
    val folderId: String,
    val title: String,
    val resourceKey: String?,
    val files: List<RemoteFile>
) {
    val chapterCount: Int get() = files.count { it.isAudio }
    val totalBytes: Long get() = files.sumOf { it.size ?: 0L }

    /** The picture shown in the list: one in the book's own folder first. */
    val cover: RemoteFile?
        get() = files.firstOrNull { !it.isAudio && '/' !in it.path } ?: files.firstOrNull { !it.isAudio }
}

/**
 * The books in a catalog. Each folder inside it is a book, with any sub-folders (CD 1, CD 2) as
 * part of it. A catalog link that holds audio files directly is a single book.
 */
fun booksIn(root: DriveFolder): List<RemoteBook> {
    if (root.files.any { isAudioName(it.name) }) return listOfNotNull(bookOf(root))
    return root.folders.mapNotNull(::bookOf).sortedWith(compareBy(NaturalOrder) { it.title })
}

private fun bookOf(folder: DriveFolder): RemoteBook? {
    val files = filesIn(folder, "")
    if (files.none { it.isAudio }) return null
    return RemoteBook(folder.id, folder.name, folder.resourceKey, files)
}

private fun filesIn(folder: DriveFolder, prefix: String): List<RemoteFile> {
    // Drive allows two files with the same name; a phone folder does not.
    val used = HashSet<String>()
    val own = folder.files
        .filter { isAudioName(it.name) || isCoverName(it.name) }
        .map { item ->
            RemoteFile(
                id = item.id,
                name = item.name,
                path = prefix + uniqueName(safeName(item.name), item.id, used),
                size = item.size,
                resourceKey = item.resourceKey,
                isAudio = isAudioName(item.name)
            )
        }
    val nested = folder.folders.flatMap { sub ->
        filesIn(sub, prefix + uniqueName(safeName(sub.name), sub.id, used) + "/")
    }
    return own + nested
}

internal fun safeName(name: String): String {
    val clean = name.replace(UNSAFE, "_").trim()
    return if (clean.isEmpty() || clean == "." || clean == "..") "_" else clean
}

private fun uniqueName(name: String, id: String, used: MutableSet<String>): String {
    // Phone storage usually ignores case, so "01.mp3" and "01.MP3" would collide.
    if (used.add(name.lowercase())) return name
    val dot = name.lastIndexOf('.')
    val tag = "-" + id.takeLast(6)
    val renamed = if (dot > 0) name.substring(0, dot) + tag + name.substring(dot) else name + tag
    used.add(renamed.lowercase())
    return renamed
}

/** One download Android is handling, or has handled, for ECHO. */
data class Transfer(
    val downloadId: Long,
    val fileId: String,
    val active: Boolean,
    val bytesSoFar: Long,
    /** Paused until Wi-Fi or any connection comes back. */
    val waiting: Boolean,
    /** Set when it failed: an HTTP status or one of DownloadManager's error codes. */
    val failedReason: Int?
)

enum class DownloadState { NOT_DOWNLOADED, DOWNLOADING, FAILED, ADDING, IN_LIBRARY, NEW_CHAPTERS }

data class BookStatus(
    val state: DownloadState,
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val newChapters: Int = 0,
    val waiting: Boolean = false,
    val failureReason: Int? = null
)

/** Files not yet on the phone in full. A file part-way through downloading counts as missing. */
fun missingFiles(book: RemoteBook, localSizes: Map<String, Long>): List<RemoteFile> =
    book.files.filter { file ->
        val have = localSizes[file.path]
        if (file.size != null) have != file.size else (have ?: 0L) <= 0L
    }

/**
 * Where a book stands, from the files on the phone, Android's downloads, and how many of its
 * chapters the library already has (null when it is not in the library at all).
 */
fun statusOf(
    book: RemoteBook,
    localSizes: Map<String, Long>,
    transfers: List<Transfer>,
    chaptersInLibrary: Int?
): BookStatus {
    val missing = missingFiles(book, localSizes)
    val byFile = transfers.groupBy { it.fileId }
    val active = missing.mapNotNull { file -> byFile[file.id]?.firstOrNull { it.active } }
    val failed = missing.mapNotNull { file ->
        val mine = byFile[file.id].orEmpty()
        if (mine.any { it.active }) null else mine.firstOrNull { it.failedReason != null }
    }
    val missingIds = missing.mapTo(HashSet()) { it.id }
    val base = BookStatus(
        state = DownloadState.NOT_DOWNLOADED,
        filesDone = book.files.size - missing.size,
        filesTotal = book.files.size,
        bytesDone = book.files.filter { it.id !in missingIds }.sumOf { it.size ?: 0L } +
            active.sumOf { it.bytesSoFar },
        bytesTotal = book.totalBytes
    )
    val complete = chaptersInLibrary != null && chaptersInLibrary >= book.chapterCount
    return when {
        active.isNotEmpty() -> base.copy(state = DownloadState.DOWNLOADING, waiting = active.all { it.waiting })
        failed.isNotEmpty() -> base.copy(state = DownloadState.FAILED, failureReason = failed.first().failedReason)
        missing.isEmpty() -> base.copy(state = if (complete) DownloadState.IN_LIBRARY else DownloadState.ADDING)
        complete && missing.none { it.isAudio } -> base.copy(state = DownloadState.IN_LIBRARY)
        chaptersInLibrary != null ->
            base.copy(state = DownloadState.NEW_CHAPTERS, newChapters = missing.count { it.isAudio })
        else -> base
    }
}
