package com.echo.player.playback

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What is actually being played. Every value comes from the file's container or the extractor;
 * anything the source does not report stays null and is left off the read-out rather than guessed.
 */
data class AudioStats(
    /** The chapter these describe, so the screen never shows one chapter's numbers on another. */
    val mediaId: String,
    val codec: String,
    val sampleRateHz: Int?,
    val channels: Int?,
    /** Only for lossless audio. A lossy file has no bit depth, whatever the decoder outputs. */
    val bitDepth: Int?,
    val bitrateKbps: Int?
) {
    fun label(): String = listOfNotNull(
        codec,
        sampleRateHz?.let(::formatSampleRate),
        bitDepth?.let { "$it-bit" },
        bitrateKbps?.let { "$it kbps" },
        channels?.let(::channelsLabel)
    ).joinToString("  ·  ")
}

/** Shared by the playback service and the screen, which live in one process — as [SleepTimer] is. */
object NowPlaying {
    private val _stats = MutableStateFlow<AudioStats?>(null)
    val stats: StateFlow<AudioStats?> = _stats.asStateFlow()

    fun publish(stats: AudioStats?) {
        _stats.value = stats
    }
}

private val LOSSLESS = setOf("audio/raw", "audio/flac", "audio/alac")

internal fun buildStats(
    mediaId: String,
    mimeType: String?,
    codecs: String?,
    sampleRate: Int,
    channelCount: Int,
    pcmEncoding: Int,
    declaredBitrates: List<Int>,
    measuredBitrate: Int?,
    reportedBitsPerSample: Int?
): AudioStats? {
    val codec = codecName(mimeType, codecs) ?: return null
    return AudioStats(
        mediaId = mediaId,
        codec = codec,
        sampleRateHz = sampleRate.takeIf { it > 0 },
        channels = channelCount.takeIf { it > 0 },
        bitDepth = bitDepthFor(mimeType, pcmEncoding, reportedBitsPerSample),
        bitrateKbps = bitrateKbps(declaredBitrates, measuredBitrate)
    )
}

/** Opening the file again is only worth it when the container left out something we would show. */
internal fun needsMeasuring(
    mimeType: String?,
    pcmEncoding: Int,
    declaredBitrates: List<Int>
): Boolean = declaredBitrates.none { it > 0 } || (isLossless(mimeType) && pcmEncoding <= 0)

internal fun codecName(mimeType: String?, codecs: String?): String? {
    val mime = mimeType?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
    return when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l1" -> "MP1"
        "audio/mpeg-l2" -> "MP2"
        "audio/mp4a-latm" -> when (codecs?.substringAfterLast('.')) {
            "5" -> "HE-AAC"
            "29" -> "HE-AACv2"
            else -> "AAC"
        }
        "audio/opus" -> "OPUS"
        "audio/vorbis" -> "VORBIS"
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/raw" -> "PCM"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        else -> mime.substringAfter('/').uppercase(Locale.US).takeIf { it.isNotBlank() }
    }
}

/** 44100 → "44.1 kHz", 48000 → "48 kHz", 22050 → "22.05 kHz". */
internal fun formatSampleRate(hz: Int): String =
    String.format(Locale.US, "%.3f", hz / 1000.0).trimEnd('0').trimEnd('.') + " kHz"

internal fun channelsLabel(count: Int): String = when (count) {
    1 -> "MONO"
    2 -> "STEREO"
    else -> "$count CH"
}

internal fun bitDepthFor(mimeType: String?, pcmEncoding: Int, reportedBitsPerSample: Int?): Int? {
    if (!isLossless(mimeType)) return null
    return when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> reportedBitsPerSample?.takeIf { it > 0 }
    }
}

/** Prefer what the container declares; otherwise what the extractor measured from the stream. */
internal fun bitrateKbps(declaredBitrates: List<Int>, measuredBitrate: Int?): Int? {
    val bitsPerSecond = declaredBitrates.firstOrNull { it > 0 }
        ?: measuredBitrate?.takeIf { it > 0 }
        ?: return null
    return (bitsPerSecond / 1000.0).roundToInt()
}

private fun isLossless(mimeType: String?): Boolean = mimeType?.lowercase(Locale.US) in LOSSLESS
