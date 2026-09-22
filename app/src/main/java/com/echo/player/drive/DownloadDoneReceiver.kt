package com.echo.player.drive

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echo.player.echoApp
import com.echo.player.playback.NowPlaying
import kotlinx.coroutines.launch

/**
 * Android reports each finished file here, so a book joins the library even with ECHO closed. If
 * the process dies first, the next app start finishes the job: state is read from disk.
 */
class DownloadDoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val app = context.echoApp
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                app.drive.finishDownloads { NowPlaying.loadedBookId == it }
            } catch (e: Exception) {
                Log.w("DownloadDoneReceiver", "Could not add a finished download", e)
            } finally {
                pending.finish()
            }
        }
    }
}
