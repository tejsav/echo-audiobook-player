package com.echo.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.echo.player.util.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Turns a folder (or a hand-picked set of files) reached through the Storage Access Framework
 * into a [Book] plus its ordered [Chapter] list.
 *
 * Everything here runs off the main thread and reports progress, because reading tags from a few
 * hundred files with [MediaMetadataRetriever] is genuinely slow.
 */
object BookImporter {

    private const val TAG = "BookImporter"
    private const val MAX_DEPTH = 5

    data class Progress(val done: Int, val total: Int, val label: String)

    class ImportException(message: String) : Exception(message)

    /** A single audio file discovered in the tree, with the path used to sort it. */
    private data class Candidate(val uri: Uri, val name: String, val sortKey: String)

    suspend fun importTree(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (Progress) -> Unit
    ): BookWithChapters = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw ImportException("That folder could not be opened.")

        onProgress(Progress(0, 0, "Scanning folder…"))

        val candidates = mutableListOf<Candidate>()
        val images = mutableListOf<DocumentFile>()
        collect(root, "", 0, candidates, images)

        if (candidates.isEmpty()) {
            val name = root.name ?: "that folder"
            throw ImportException("No audio files found in \"" + name + "\".")
        }

        candidates.sortWith(compareBy(NaturalOrder) { it.sortKey })

        val fallbackTitle = root.name?.takeIf { it.isNotBlank() } ?: "Audiobook"
        build(
            context = context,
            sourceUri = treeUri.toString(),
            candidates = candidates,
            fallbackTitle = fallbackTitle,
            folderImages = images,
            onProgress = onProgress
        )
    }

    /**
     * The audio files a folder holds right now, without reading any tags. Null when the folder
     * cannot be opened at all.
     */
    suspend fun scanTree(context: Context, treeUri: Uri): Set<String>? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.canRead()) return@withContext null
        val audio = mutableListOf<Candidate>()
        collect(root, "", 0, audio, mutableListOf())
        audio.mapTo(HashSet()) { it.uri.toString() }
    }

    /** The id a series gets from where it came from, so importing it again updates it in place. */
    fun idFor(sourceUri: String): String = UUID.nameUUIDFromBytes(sourceUri.toByteArray()).toString()

    /** A folder in ECHO's own storage, such as a book downloaded from a Drive catalog. */
    suspend fun importDirectory(
        context: Context,
        dir: java.io.File,
        sourceUri: String,
        fallbackTitle: String,
        onProgress: suspend (Progress) -> Unit
    ): BookWithChapters = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Candidate>()
        val images = mutableListOf<DocumentFile>()
        collect(DocumentFile.fromFile(dir), "", 0, candidates, images)
        if (candidates.isEmpty()) throw ImportException("No audio files found in \"" + fallbackTitle + "\".")
        candidates.sortWith(compareBy(NaturalOrder) { it.sortKey })
        build(context, sourceUri, candidates, fallbackTitle, images, onProgress)
    }

    suspend fun importFiles(
        context: Context,
        uris: List<Uri>,
        onProgress: suspend (Progress) -> Unit
    ): BookWithChapters = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw ImportException("No files were selected.")

        val candidates = uris.map { uri ->
            val name = DocumentFile.fromSingleUri(context, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "Track"
            Candidate(uri, name, name)
        }.toMutableList()

        candidates.sortWith(compareBy(NaturalOrder) { it.sortKey })

        val fallbackTitle = candidates.first().name.substringBeforeLast('.')
        // Stable id for the exact same selection, so re-importing updates instead of duplicating.
        val selectionKey = candidates.joinToString("|") { it.uri.toString() }.hashCode()

        build(
            context = context,
            sourceUri = "files:" + selectionKey,
            candidates = candidates,
            fallbackTitle = fallbackTitle,
            folderImages = emptyList(),
            onProgress = onProgress
        )
    }

    private suspend fun collect(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        audio: MutableList<Candidate>,
        images: MutableList<DocumentFile>
    ) {
        if (depth > MAX_DEPTH) return
        coroutineContext.ensureActive()

        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        // Visit sub-folders in natural order too, so nested chapter folders stay in sequence.
        val sorted = children.sortedWith(compareBy(NaturalOrder) { it.name.orEmpty() })

        for (child in sorted) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                collect(child, prefix + name + "/", depth + 1, audio, images)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in AUDIO_EXTENSIONS) {
                audio += Candidate(child.uri, name, prefix + name)
            } else if (ext in IMAGE_EXTENSIONS &&
                COVER_NAMES.any { name.lowercase().startsWith(it) }
            ) {
                images += child
            }
        }
    }

    private suspend fun build(
        context: Context,
        sourceUri: String,
        candidates: List<Candidate>,
        fallbackTitle: String,
        folderImages: List<DocumentFile>,
        onProgress: suspend (Progress) -> Unit
    ): BookWithChapters {
        val bookId = idFor(sourceUri)
        val total = candidates.size

        var album: String? = null
        var artist: String? = null
        var coverBytes: ByteArray? = null
        var totalDuration = 0L

        val chapters = ArrayList<Chapter>(total)

        candidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()
            onProgress(Progress(index, total, candidate.name))

            val tags = readTags(context, candidate.uri)
            if (album == null) album = tags.album
            if (artist == null) artist = tags.artist
            if (coverBytes == null) coverBytes = tags.picture

            chapters += Chapter(
                bookId = bookId,
                index = index,
                title = tags.title?.takeIf { it.isNotBlank() }
                    ?: candidate.name.substringBeforeLast('.'),
                uri = candidate.uri.toString(),
                durationMs = tags.durationMs,
                startOffsetMs = totalDuration
            )
            totalDuration += tags.durationMs
        }

        if (coverBytes == null) {
            coverBytes = folderImages.firstNotNullOfOrNull { image ->
                runCatching {
                    context.contentResolver.openInputStream(image.uri)?.use { it.readBytes() }
                }.getOrNull()
            }
        }

        val coverPath = coverBytes?.let { CoverStore.saveBytes(context, bookId, it) }

        onProgress(Progress(total, total, "Finishing…"))

        val book = Book(
            id = bookId,
            title = album?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = artist?.takeIf { it.isNotBlank() },
            sourceUri = sourceUri,
            coverPath = coverPath,
            chapterCount = chapters.size,
            totalDurationMs = totalDuration,
            currentChapterTitle = chapters.firstOrNull()?.title,
            currentChapterDurationMs = chapters.firstOrNull()?.durationMs ?: 0L
        )
        return BookWithChapters(book, chapters)
    }

    private class Tags(
        val title: String?,
        val album: String?,
        val artist: String?,
        val durationMs: Long,
        val picture: ByteArray?
    )

    private fun readTags(context: Context, uri: Uri): Tags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                picture = retriever.embeddedPicture
            )
        } catch (e: Exception) {
            // A file whose tags we cannot read is still playable; fall back to the file name.
            Log.w(TAG, "Could not read tags for " + uri, e)
            Tags(null, null, null, 0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
