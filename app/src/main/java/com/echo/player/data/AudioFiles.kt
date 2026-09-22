package com.echo.player.data

/** What counts as a chapter and what counts as a cover, for folders on the phone and on Drive. */
internal val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus",
    "wav", "flac", "wma", "mka", "3gp", "amr"
)

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

internal val COVER_NAMES = listOf("cover", "folder", "front", "art", "artwork", "album")

internal fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

internal fun isAudioName(name: String): Boolean = extensionOf(name) in AUDIO_EXTENSIONS

internal fun isCoverName(name: String): Boolean =
    extensionOf(name) in IMAGE_EXTENSIONS && COVER_NAMES.any { name.lowercase().startsWith(it) }
