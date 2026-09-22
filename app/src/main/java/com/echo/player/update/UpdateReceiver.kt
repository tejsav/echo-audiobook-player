package com.echo.player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.echo.player.echoApp

/** Android's answer to an update: done, needs a tap, or failed. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val updater = context.echoApp.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let(updater::onNeedsConfirmation)
            PackageInstaller.STATUS_SUCCESS -> updater.onInstalled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onFailed("The update was cancelled.")
            else -> updater.onFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
