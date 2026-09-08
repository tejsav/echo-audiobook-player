package com.echo.player.playback

/**
 * Media items carry `bookId|chapterIndex` as their media id so the playback service can always
 * work out what to save, without holding any extra state that could go stale.
 */
object MediaIds {

    private const val SEPARATOR = '|'

    data class Parsed(val bookId: String, val chapterIndex: Int)

    fun create(bookId: String, chapterIndex: Int): String =
        bookId + SEPARATOR + chapterIndex

    fun parse(mediaId: String?): Parsed? {
        if (mediaId.isNullOrEmpty()) return null
        val separator = mediaId.lastIndexOf(SEPARATOR)
        if (separator <= 0 || separator == mediaId.length - 1) return null
        val index = mediaId.substring(separator + 1).toIntOrNull() ?: return null
        return Parsed(mediaId.substring(0, separator), index)
    }
}
