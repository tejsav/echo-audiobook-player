package com.echo.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns every cover image the app holds.
 *
 * Pictures the user picks from their gallery are *copied* into app storage rather than referenced
 * by uri: the gallery item can be deleted or moved, and we need the artwork to still be there on
 * the lock screen a month later.
 */
object CoverStore {

    private const val TAG = "CoverStore"
    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 88

    private fun dir(context: Context): File =
        File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * File names carry a timestamp so a replaced cover is a genuinely new path. Overwriting one
     * path would leave the image cache serving the previous picture.
     */
    private fun newFile(context: Context, bookId: String): File =
        File(dir(context), bookId + "_" + System.currentTimeMillis() + ".jpg")

    /** Stores raw bytes (embedded artwork found during import). */
    fun saveBytes(context: Context, bookId: String, bytes: ByteArray): String? = try {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded != null) {
            writeScaled(context, bookId, decoded)
        } else {
            newFile(context, bookId).apply { writeBytes(bytes) }.absolutePath
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not store embedded artwork", e)
        null
    }

    /** Copies a gallery picture into app storage, downscaled to something sane for a cover. */
    suspend fun saveFromUri(context: Context, bookId: String, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                }
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@withContext null

                writeScaled(context, bookId, bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the chosen picture", e)
                null
            }
        }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /** Removes every stored cover for a book except [keep]. */
    fun deleteOthers(context: Context, bookId: String, keep: String?) {
        runCatching {
            dir(context).listFiles()
                ?.filter { it.name.startsWith(bookId) && it.absolutePath != keep }
                ?.forEach { it.delete() }
        }
    }

    private fun writeScaled(context: Context, bookId: String, source: Bitmap): String? = try {
        val scaled = scaleDown(source)
        val file = newFile(context, bookId)
        file.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (scaled !== source) scaled.recycle()
        source.recycle()
        file.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "Could not write cover", e)
        null
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }
}
