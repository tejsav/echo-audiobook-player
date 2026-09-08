package com.echo.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.echo.player.EchoApp
import com.echo.player.MainActivity
import com.echo.player.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps playback alive independently of the UI and is the only place that writes resume positions.
 *
 * Positions are saved on a 5 second heartbeat while playing, plus on every pause, chapter change,
 * end of book, task removal and teardown — so "where you left off" survives backgrounding, swiping
 * the app away, and the process being killed.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var repository: LibraryRepository
    private var mediaSession: MediaSession? = null

    /** Service-lifetime scope for the ticker. DB writes deliberately use the application scope. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var appScope: CoroutineScope

    /** Saves are ignored until this moment; see [Player.Listener.onTimelineChanged]. */
    private var suppressSavesUntil = 0L

    private val listener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveProgress()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                // Loading a queue reports its start position through the very callbacks a real
                // move does. Without this, opening a series (which rewinds 15s) would save the
                // rewound point and walk the book backwards a little further every time.
                suppressSavesUntil = SystemClock.elapsedRealtime() + LOAD_SETTLE_MS
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The position now reads as "start of the next chapter", which is exactly the
            // resume point we want once a chapter has been listened through.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saveProgress()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                SleepTimer.onChapterFinished()
            ) {
                player.pause()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                saveProgress()
                SleepTimer.cancel()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // A scrub or a skip is a deliberate move; record it straight away rather than
            // waiting for the next heartbeat.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) saveProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: " + error.errorCodeName, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as EchoApp
        repository = app.repository
        appScope = app.applicationScope

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()

        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()

        startHeartbeat()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgressBlocking()
        // Nothing is playing and the user just swiped the app away: don't linger.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveProgressBlocking()
        serviceScope.cancel()
        SleepTimer.cancel()
        mediaSession?.run {
            player.removeListener(listener)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun startHeartbeat() {
        serviceScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            var ticks = 0
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTick
                lastTick = now

                if (!player.isPlaying) continue

                if (SleepTimer.tick(elapsed)) {
                    player.pause()
                    continue
                }

                ticks++
                if (ticks % SAVE_EVERY_TICKS == 0) saveProgress()
            }
        }
    }

    /** Fire-and-forget save on the application scope, so it outlives this service. */
    private fun saveProgress() {
        val point = currentPoint() ?: return
        appScope.launch {
            repository.saveProgress(point.bookId, point.chapterIndex, point.positionMs)
        }
    }

    /**
     * A save that must not be lost. Used on teardown paths where the process may be about to die;
     * it is a single narrow UPDATE, so blocking briefly is the right trade.
     */
    private fun saveProgressBlocking() {
        val point = currentPoint() ?: return
        runCatching {
            runBlocking {
                repository.saveProgress(point.bookId, point.chapterIndex, point.positionMs)
            }
        }.onFailure { Log.w(TAG, "Could not save resume position", it) }
    }

    private data class ResumePoint(
        val bookId: String,
        val chapterIndex: Int,
        val positionMs: Long
    )

    private fun currentPoint(): ResumePoint? {
        if (!this::player.isInitialized) return null
        if (SystemClock.elapsedRealtime() < suppressSavesUntil) return null
        val mediaItem = player.currentMediaItem ?: return null
        val parsed = MediaIds.parse(mediaItem.mediaId) ?: return null
        return ResumePoint(
            bookId = parsed.bookId,
            chapterIndex = parsed.chapterIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L)
        )
    }

    companion object {
        private const val TAG = "PlaybackService"

        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 15_000L

        private const val TICK_MS = 1_000L
        private const val SAVE_EVERY_TICKS = 5
        private const val LOAD_SETTLE_MS = 1_000L
    }
}
