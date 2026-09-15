package com.echo.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import com.echo.player.MainActivity
import com.echo.player.R
import com.echo.player.echoApp
import com.echo.player.util.formatDurationShort
import kotlinx.coroutines.launch

/**
 * Home-screen widget: the last series you listened to, and one button to carry on. The button
 * sends a media key, so playback starts in the background without opening the app.
 */
class ResumeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        context.echoApp.applicationScope.launch {
            try {
                render(context.applicationContext, lastKnownPlaying)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /** Process-wide: if the process is gone, nothing is playing, which is also what this says. */
        @Volatile
        private var lastKnownPlaying = false

        private const val COVER_EDGE_PX = 256

        fun update(context: Context, isPlaying: Boolean) {
            lastKnownPlaying = isPlaying
            val app = context.applicationContext
            val ids = AppWidgetManager.getInstance(app)
                .getAppWidgetIds(ComponentName(app, ResumeWidget::class.java))
            if (ids.isEmpty()) return
            app.echoApp.applicationScope.launch { runCatching { render(app, isPlaying) } }
        }

        @OptIn(UnstableApi::class)
        private suspend fun render(context: Context, isPlaying: Boolean) {
            val last = context.echoApp.repository.lastPlayed()
            val views = RemoteViews(context.packageName, R.layout.widget_resume)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)

            if (last == null || last.chapters.isEmpty()) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_empty))
                views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                views.setImageViewResource(R.id.widget_play, R.drawable.ic_widget_play)
                // Nothing to resume yet, so the button just opens the app.
                views.setOnClickPendingIntent(R.id.widget_play, openApp)
            } else {
                val book = last.book
                val chapter = last.chapters.getOrNull(book.currentChapterIndex)
                views.setTextViewText(R.id.widget_title, book.title)
                views.setTextViewText(
                    R.id.widget_subtitle,
                    listOfNotNull(
                        chapter?.let { book.displayTitle(it.title) },
                        formatDurationShort(book.remainingMs) + " left"
                    ).joinToString("  ·  ")
                )
                val cover = book.coverPath?.let(::decodeCover)
                if (cover != null) {
                    views.setImageViewBitmap(R.id.widget_cover, cover)
                } else {
                    views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                }
                views.setImageViewResource(
                    R.id.widget_play,
                    if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                )
                views.setContentDescription(R.id.widget_play, if (isPlaying) "Pause" else "Play")
                val key = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                    .putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    )
                views.setOnClickPendingIntent(
                    R.id.widget_play,
                    PendingIntent.getBroadcast(context, 1, key, flags)
                )
            }

            AppWidgetManager.getInstance(context)
                .updateAppWidget(ComponentName(context, ResumeWidget::class.java), views)
        }

        /** Widgets pass bitmaps across processes, so keep the cover small. */
        private fun decodeCover(path: String): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= COVER_EDGE_PX &&
                bounds.outHeight / (sample * 2) >= COVER_EDGE_PX
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
