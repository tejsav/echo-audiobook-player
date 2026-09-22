package com.echo.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.echo.player.ui.deck.DeckScreen
import com.echo.player.ui.theme.EchoTheme

/**
 * One screen. Browsing series and playing them are the same act here, so there is nothing to
 * navigate between.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationPermissionRequest()
                    DeckScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        echoApp.playerConnection.connect()
        echoApp.updater.onForeground()
    }

    override fun onStop() {
        // An update may install now, but never over something playing.
        echoApp.updater.onBackground(isPlaying = echoApp.playerConnection.state.value.isPlaying)
        // Playback keeps running in the service; only the UI's controller goes away.
        echoApp.playerConnection.release()
        super.onStop()
    }
}

/** Android 13+ needs explicit consent before the playback notification can appear. */
@Composable
private fun NotificationPermissionRequest() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
