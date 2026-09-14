package com.echo.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.echo.player.data.Book
import com.echo.player.data.Chapter
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val bookId: String? = null,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val bookTitle: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f
) {
    val hasItem: Boolean get() = bookId != null
}

/**
 * The UI's handle on [PlaybackService]. Owns a [MediaController] and mirrors it into a state flow
 * that Compose can collect.
 */
class PlayerConnection(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var tickerJob: Job? = null

    /** Runs once the controller finishes connecting, for taps that beat the connection. */
    private var pending: ((MediaController) -> Unit)? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            publish()
            startTicker()
            pending?.let { action ->
                pending = null
                action(connected)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        tickerJob?.cancel()
        tickerJob = null
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _state.value = PlaybackUiState()
    }

    /**
     * Loads [book] into the player, starting from its saved resume point unless an explicit
     * position is given. If the book is already loaded the queue is left alone.
     *
     * Picking a series back up rewinds by [RESUME_REWIND_MS] so the last few seconds are heard
     * again — after a day away you need a moment to find the thread. An explicit position (a
     * chapter tap, a scrub) is honoured exactly.
     */
    fun openBook(
        book: Book,
        chapters: List<Chapter>,
        autoPlay: Boolean,
        startIndex: Int? = null,
        startPositionMs: Long? = null
    ) = withController { controller ->
        if (chapters.isNotEmpty()) {
            val alreadyLoaded = _state.value.bookId == book.id && controller.mediaItemCount > 0
            if (alreadyLoaded && startIndex == null) {
                if (autoPlay) controller.play()
            } else {
                val index = (startIndex ?: book.currentChapterIndex)
                    .coerceIn(0, chapters.lastIndex)
                val resuming = startPositionMs == null
                val rawPosition = startPositionMs ?: book.currentPositionMs
                val position = if (resuming) {
                    (rawPosition - RESUME_REWIND_MS).coerceAtLeast(0L)
                } else {
                    rawPosition.coerceAtLeast(0L)
                }

                controller.setMediaItems(chapters.map { it.toMediaItem(book) }, index, position)
                controller.setPlaybackSpeed(book.playbackSpeed)
                controller.prepare()
                if (autoPlay) controller.play()
            }
        }
    }

    /**
     * Rebuilds the queue in place so a newly chosen cover reaches the notification and lock
     * screen immediately, without losing the current position or pausing playback.
     */
    fun refreshMetadata(book: Book, chapters: List<Chapter>) = withController { controller ->
        if (chapters.isNotEmpty() && _state.value.bookId == book.id) {
            val index = controller.currentMediaItemIndex.coerceIn(0, chapters.lastIndex)
            val position = controller.currentPosition.coerceAtLeast(0L)
            val wasPlaying = controller.isPlaying

            controller.setMediaItems(chapters.map { it.toMediaItem(book) }, index, position)
            controller.prepare()
            if (wasPlaying) controller.play()
        }
    }

    fun playChapter(index: Int) = withController { it.seekTo(index, 0L) }

    /** Puts the listener back exactly where they were before a chapter jump. */
    fun seekToChapter(index: Int, positionMs: Long) =
        withController { it.seekTo(index, positionMs.coerceAtLeast(0L)) }

    fun togglePlayPause() = withController { controller ->
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun play() = withController { it.play() }

    fun pause() = withController { it.pause() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun seekBack() = withController { it.seekBack() }

    fun seekForward() = withController { it.seekForward() }

    fun previousChapter() = withController { controller ->
        // Same convention as most players: restart the chapter unless we are near its start.
        if (controller.currentPosition > RESTART_THRESHOLD_MS ||
            !controller.hasPreviousMediaItem()
        ) {
            controller.seekTo(0L)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    fun nextChapter() = withController { controller ->
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
    }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    private fun withController(action: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) {
            action(connected)
        } else {
            pending = action
            connect()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_POLL_MS)
                val connected = controller ?: continue
                if (!connected.isPlaying) continue
                _state.value = _state.value.copy(
                    positionMs = connected.currentPosition.coerceAtLeast(0L),
                    durationMs = connected.duration.readableDuration()
                )
            }
        }
    }

    private fun publish() {
        val connected = controller
        if (connected == null) {
            _state.value = PlaybackUiState()
            return
        }
        val parsed = MediaIds.parse(connected.currentMediaItem?.mediaId)
        _state.value = PlaybackUiState(
            isConnected = true,
            bookId = parsed?.bookId,
            chapterIndex = parsed?.chapterIndex ?: 0,
            chapterTitle = connected.mediaMetadata.title?.toString().orEmpty(),
            bookTitle = connected.mediaMetadata.albumTitle?.toString().orEmpty(),
            isPlaying = connected.isPlaying,
            isBuffering = connected.playbackState == Player.STATE_BUFFERING,
            positionMs = connected.currentPosition.coerceAtLeast(0L),
            durationMs = connected.duration.readableDuration(),
            speed = connected.playbackParameters.speed
        )
    }

    private fun Long.readableDuration(): Long =
        if (this == C.TIME_UNSET || this < 0L) 0L else this

    private fun Chapter.toMediaItem(book: Book): MediaItem {
        val artwork = book.coverPath?.let { Uri.fromFile(File(it)) }
        return MediaItem.Builder()
            .setMediaId(MediaIds.create(bookId, index))
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(book.author ?: book.title)
                    .setAlbumTitle(book.title)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val RESTART_THRESHOLD_MS = 3_000L

        /** How far to step back when picking a series up again. */
        const val RESUME_REWIND_MS = 15_000L
    }
}
