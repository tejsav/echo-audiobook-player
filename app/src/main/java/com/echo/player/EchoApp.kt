package com.echo.player

import android.app.Application
import android.content.Context
import com.echo.player.data.LibraryRepository
import com.echo.player.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny manual dependency graph. The app has three long-lived pieces and no need for a framework.
 */
class EchoApp : Application() {

    /**
     * Outlives the playback service on purpose: resume writes started just before teardown must
     * still complete.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val repository: LibraryRepository by lazy { LibraryRepository(this) }

    val playerConnection: PlayerConnection by lazy { PlayerConnection(this) }
}

val Context.echoApp: EchoApp
    get() = applicationContext as EchoApp
