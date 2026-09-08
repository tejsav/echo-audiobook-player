package com.echo.player.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SleepMode {
    data object Off : SleepMode
    data class Countdown(val totalMinutes: Int) : SleepMode
    data object EndOfChapter : SleepMode
}

/**
 * Sleep timer shared between the UI and [PlaybackService]. Both live in the same process, so a
 * plain object with state flows is enough and avoids inventing custom session commands.
 */
object SleepTimer {

    private val _mode = MutableStateFlow<SleepMode>(SleepMode.Off)
    val mode: StateFlow<SleepMode> = _mode.asStateFlow()

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    fun startCountdown(minutes: Int) {
        _mode.value = SleepMode.Countdown(minutes)
        _remainingMs.value = minutes * 60_000L
    }

    fun stopAtEndOfChapter() {
        _mode.value = SleepMode.EndOfChapter
        _remainingMs.value = 0L
    }

    fun cancel() {
        _mode.value = SleepMode.Off
        _remainingMs.value = 0L
    }

    /** Advances a running countdown. Returns true when playback should pause now. */
    fun tick(deltaMs: Long): Boolean {
        if (_mode.value !is SleepMode.Countdown) return false
        val left = _remainingMs.value - deltaMs
        if (left > 0L) {
            _remainingMs.value = left
            return false
        }
        cancel()
        return true
    }

    /** Called when a chapter ends by itself. Returns true when playback should pause now. */
    fun onChapterFinished(): Boolean {
        if (_mode.value !is SleepMode.EndOfChapter) return false
        cancel()
        return true
    }
}
