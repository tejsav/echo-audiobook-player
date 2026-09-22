package com.echo.player.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.echo.player.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val apkUrl: String, val size: Long)

data class UpdateUi(
    val current: String,
    val available: UpdateInfo? = null,
    val installing: Boolean = false,
    /** Android wants a tap to finish installing. */
    val needsConfirm: Boolean = false,
    /** "Install unknown apps" is switched on for ECHO. */
    val allowed: Boolean = false,
    val checkedAt: Long = 0L,
    val error: String? = null
)

/**
 * Keeps ECHO up to date from its own GitHub releases, with no store and no other app.
 *
 * On Android 12 and newer, once "Install unknown apps" is allowed for ECHO, an update installs by
 * itself the next time the app goes to the background with nothing playing. Anywhere Android
 * still wants a say, the update waits for one tap. Android only accepts an update signed with the
 * same key as the installed app, so nothing else can be slipped in this way.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {

    /** Debug builds have their own package and would only ever find release builds. */
    val enabled: Boolean = !BuildConfig.DEBUG && BuildConfig.UPDATE_REPO.isNotBlank()

    /** Android 12 and newer can let an app update itself without asking each time. */
    val silent: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val _state = MutableStateFlow(UpdateUi(current = BuildConfig.VERSION_NAME))
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    @Volatile
    private var inForeground = false

    @Volatile
    private var confirmation: Intent? = null

    private val lock = Mutex()

    /** The one-time switch: Settings → Install unknown apps → ECHO. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun onForeground() {
        inForeground = true
        _state.update { it.copy(allowed = allowed()) }
        if (enabled && System.currentTimeMillis() - _state.value.checkedAt > CHECK_EVERY_MS) {
            scope.launch { check() }
        }
    }

    /** Leaving the app with nothing playing is the moment an update can land unnoticed. */
    fun onBackground(isPlaying: Boolean) {
        inForeground = false
        val now = _state.value
        if (enabled && silent && now.allowed && now.available != null &&
            !now.installing && !now.needsConfirm && !isPlaying
        ) {
            scope.launch { install(fromBackground = true) }
        }
    }

    fun checkNow() {
        if (enabled) scope.launch { check() }
    }

    fun installNow() {
        if (enabled) scope.launch { install(fromBackground = false) }
    }

    fun confirm() {
        val intent = confirmation ?: return
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not show the install screen", it) }
    }

    internal fun onNeedsConfirmation(intent: Intent) {
        confirmation = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        _state.update { it.copy(installing = false, needsConfirm = true) }
        if (inForeground) confirm()
    }

    internal fun onInstalled() {
        confirmation = null
        _state.update { it.copy(installing = false, needsConfirm = false, available = null) }
    }

    internal fun onFailed(message: String?) {
        confirmation = null
        _state.update {
            it.copy(installing = false, needsConfirm = false, error = message ?: "The update could not be installed.")
        }
    }

    private fun allowed(): Boolean = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private suspend fun check() = lock.withLock {
        val found = try {
            withContext(Dispatchers.IO) { latestRelease() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            _state.update { it.copy(checkedAt = System.currentTimeMillis(), error = "Could not reach GitHub.") }
            return@withLock
        }
        _state.update {
            it.copy(
                checkedAt = System.currentTimeMillis(),
                error = null,
                available = found?.takeIf { release -> isNewer(release.version, BuildConfig.VERSION_NAME) }
            )
        }
    }

    /** The newest published release and its APK, or null when there is none. */
    private fun latestRelease(): UpdateInfo? {
        val url = URL("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "ECHO/" + BuildConfig.VERSION_NAME)
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("GitHub answered $code")
            val release = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
            val version = release.optString("tag_name").removePrefix("v")
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return UpdateInfo(version, asset.getString("browser_download_url"), asset.optLong("size"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun install(fromBackground: Boolean) = lock.withLock {
        val info = _state.value.available ?: return@withLock
        if (_state.value.installing) return@withLock
        _state.update { it.copy(installing = true, error = null) }
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            withContext(Dispatchers.IO) {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    if (info.size > 0L) setSize(info.size)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("echo.apk", 0L, if (info.size > 0L) info.size else -1L).use { out ->
                        download(info.apkUrl) { input -> input.copyTo(out) }
                        session.fsync(out)
                    }
                    if (fromBackground && inForeground) {
                        // The listener came back while it downloaded. Installing now would close
                        // ECHO in front of them, so wait for the next time they leave.
                        session.abandon()
                        sessionId = -1
                        _state.update { it.copy(installing = false) }
                        return@withContext
                    }
                    session.commit(statusReceiver(sessionId))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update install failed", e)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            _state.update { it.copy(installing = false, error = "The update could not be downloaded.") }
        }
    }

    private fun download(url: String, write: (InputStream) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "ECHO/" + BuildConfig.VERSION_NAME)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Download failed ($code)")
            connection.inputStream.use(write)
        } finally {
            connection.disconnect()
        }
    }

    private fun statusReceiver(sessionId: Int): IntentSender {
        // Mutable because Android fills in the result.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(context, UpdateReceiver::class.java)
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private companion object {
        const val TAG = "Updater"
        const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    }
}
