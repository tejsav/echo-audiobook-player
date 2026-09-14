package com.echo.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
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
import kotlinx.coroutines.withContext

/**
 * Keeps playback alive independently of the UI and is the only place that writes resume positions
 * and listening history.
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

    /** The chapter playing before the latest transition, so a natural finish can be credited. */
    private var currentItemId: String? = null

    /** Tags read from each file once; opening a file is not something to repeat every tick. */
    private val measured = HashMap<String, MeasuredTags>()

    private data class MeasuredTags(val bitrate: Int?, val bitsPerSample: Int?)

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
            // Playing through to the end is the only thing that marks a chapter done by itself.
            // A skip moves on without crediting the chapter it left.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) markFinished(currentItemId)
            currentItemId = mediaItem?.mediaId

            // The position now reads as "start of the next chapter", which is exactly the
            // resume point we want once a chapter has been listened through.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saveProgress()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                SleepTimer.onChapterFinished()
            ) {
                player.pause()
            }
            publishStats()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    markFinished(currentItemId)
                    saveProgress()
                    SleepTimer.cancel()
                }
                Player.STATE_READY -> publishStats()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            publishStats()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            // Leaving one chapter for another: keep the exact spot it was left at, so going back
            // to it resumes there instead of starting over. A scrub within a chapter is not
            // recorded as listening.
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex &&
                SystemClock.elapsedRealtime() >= suppressSavesUntil
            ) {
                MediaIds.parse(oldPosition.mediaItem?.mediaId)?.let { left ->
                    val leftAt = oldPosition.positionMs
                    appScope.launch {
                        repository.recordListened(left.bookId, left.chapterIndex, leftAt)
                    }
                }
            }
            saveProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: " + error.errorCodeName, error)
        }
    }

    private val sessionCallback = object : MediaSession.Callback {

        /**
         * Only this app's own controller may change chapters. The notification, lock screen,
         * watches, cars and Bluetooth devices all connect through here as well, and a next button
         * that drops you into a different chapter of a long talk is a way to lose your place.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val external = session.isMediaNotificationController(controller) ||
                controller.packageName != packageName
            if (!external) return super.onConnect(session, controller)

            val commands = Player.Commands.Builder()
                .addAllCommands()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        /**
         * Headset and Bluetooth buttons play and pause, nothing else. Media3 would otherwise read
         * a double press as "next", and many headsets send next and previous by themselves.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val event = IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java
            ) ?: return false

            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                        if (player.isPlaying) player.pause() else player.play()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_STOP -> player.pause()
                    // Next, previous, fast-forward and rewind are swallowed on purpose.
                }
            }
            return true
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
            .setCallback(sessionCallback)
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
        NowPlaying.publish(null)
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
                if (ticks % SAVE_EVERY_TICKS == 0) {
                    saveProgress()
                    recordListened()
                }
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
     * How far a chapter has been heard. Only the playback heartbeat calls this, never a seek, so
     * scrubbing ahead is not counted as listening.
     *
     * ponytail: a high-water mark, so playing on for a few seconds after a forward scrub counts
     * the skipped stretch as heard. Needs stored heard-ranges if that ever matters.
     */
    private fun recordListened() {
        val point = currentPoint() ?: return
        appScope.launch {
            repository.recordListened(point.bookId, point.chapterIndex, point.positionMs)
        }
    }

    private fun markFinished(mediaId: String?) {
        val finished = MediaIds.parse(mediaId) ?: return
        appScope.launch { repository.markCompleted(finished.bookId, finished.chapterIndex) }
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

    /** Publishes the format actually being decoded. Nothing is shown until it is known. */
    private fun publishStats() {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId
        if (mediaId == null) {
            NowPlaying.publish(null)
            return
        }

        val format = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { group ->
                (0 until group.length)
                    .firstOrNull { group.isTrackSelected(it) }
                    ?.let { group.getTrackFormat(it) }
            }

        if (format == null) {
            // The new chapter's tracks are not read yet: clear rather than show the last chapter's.
            if (NowPlaying.stats.value?.mediaId != mediaId) NowPlaying.publish(null)
            return
        }

        val cached = measured[mediaId]
        NowPlaying.publish(statsFrom(mediaId, format, cached))

        val uri = item.localConfiguration?.uri ?: return
        val declared = listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
        if (cached == null && needsMeasuring(format.sampleMimeType, format.pcmEncoding, declared)) {
            serviceScope.launch {
                val tags = withContext(Dispatchers.IO) { readTags(uri) }
                measured[mediaId] = tags
                if (player.currentMediaItem?.mediaId == mediaId) {
                    NowPlaying.publish(statsFrom(mediaId, format, tags))
                }
            }
        }
    }

    private fun statsFrom(mediaId: String, format: Format, tags: MeasuredTags?): AudioStats? =
        buildStats(
            mediaId = mediaId,
            mimeType = format.sampleMimeType,
            codecs = format.codecs,
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            pcmEncoding = format.pcmEncoding,
            declaredBitrates = listOf(format.averageBitrate, format.bitrate, format.peakBitrate),
            measuredBitrate = tags?.bitrate,
            reportedBitsPerSample = tags?.bitsPerSample
        )

    private fun readTags(uri: Uri): MeasuredTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            MeasuredTags(
                bitrate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull(),
                bitsPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()
                } else {
                    null
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read stream details for " + uri, e)
            MeasuredTags(null, null)
        } finally {
            runCatching { retriever.release() }
        }
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
