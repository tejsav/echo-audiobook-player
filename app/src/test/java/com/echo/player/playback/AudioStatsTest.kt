package com.echo.player.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stats read-out must never invent a number: unknown values drop out, and lossy files never
 * grow a bit depth.
 */
class AudioStatsTest {

    private val undeclared = listOf(-1, -1, -1)

    @Test
    fun mp3FallsBackToMeasuredBitrateAndHasNoBitDepth() {
        val stats = buildStats("b|0", "audio/mpeg", null, 44_100, 1, -1, undeclared, 128_000, 16)!!
        assertEquals("MP3  ·  44.1 kHz  ·  128 kbps  ·  MONO", stats.label())
        assertNull(stats.bitDepth)
    }

    @Test
    fun declaredBitrateWinsOverMeasured() {
        val stats = buildStats(
            "b|0", "audio/mp4a-latm", "mp4a.40.2", 48_000, 2, -1,
            listOf(96_000, 96_000, -1), 128_000, null
        )!!
        assertEquals("AAC  ·  48 kHz  ·  96 kbps  ·  STEREO", stats.label())
    }

    @Test
    fun losslessShowsRealBitDepth() {
        val wav = buildStats(
            "b|0", "audio/raw", null, 96_000, 2, C.ENCODING_PCM_24BIT,
            undeclared, 4_608_000, null
        )!!
        assertEquals("PCM  ·  96 kHz  ·  24-bit  ·  4608 kbps  ·  STEREO", wav.label())

        val flac = buildStats("b|0", "audio/flac", null, 44_100, 2, -1, undeclared, null, 24)!!
        assertEquals(24, flac.bitDepth)
    }

    @Test
    fun unknownValuesAreLeftOutRatherThanGuessed() {
        val opus = buildStats("b|0", "audio/opus", null, -1, -1, -1, undeclared, null, null)!!
        assertEquals("OPUS", opus.label())
        assertNull(buildStats("b|0", null, null, 44_100, 2, -1, undeclared, null, null))
    }

    @Test
    fun namesCodecsAndFormatsValues() {
        assertEquals("HE-AAC", codecName("audio/mp4a-latm", "mp4a.40.5"))
        assertEquals("HE-AACv2", codecName("audio/mp4a-latm", "mp4a.40.29"))
        assertEquals("X-WAV", codecName("audio/x-wav", null))
        assertEquals("22.05 kHz", formatSampleRate(22_050))
        assertEquals("6 CH", channelsLabel(6))
    }

    @Test
    fun onlyReopensTheFileWhenSomethingIsMissing() {
        assertFalse(needsMeasuring("audio/mpeg", -1, listOf(128_000, -1, -1)))
        assertTrue(needsMeasuring("audio/mpeg", -1, undeclared))
        assertTrue(needsMeasuring("audio/flac", -1, listOf(900_000, -1, -1)))
    }
}
