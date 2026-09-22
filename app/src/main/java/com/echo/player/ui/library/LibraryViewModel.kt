package com.echo.player.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echo.player.data.Settings
import com.echo.player.drive.BookStatus
import com.echo.player.drive.DownloadState
import com.echo.player.drive.RemoteBook
import com.echo.player.drive.parseFolderLink
import com.echo.player.echoApp
import com.echo.player.playback.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val POLL_MS = 1_000L

data class CatalogUi(
    val link: String,
    val name: String? = null,
    val books: List<RemoteBook> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val drive = application.echoApp.drive
    private val repository = application.echoApp.repository

    val hasKey: Boolean = drive.hasKey

    val wifiOnly: StateFlow<Boolean> = Settings.driveWifiOnly(application)
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _catalogs = MutableStateFlow<List<CatalogUi>>(emptyList())
    val catalogs: StateFlow<List<CatalogUi>> = _catalogs.asStateFlow()

    /** By Drive folder id. */
    private val _statuses = MutableStateFlow<Map<String, BookStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, BookStatus>> = _statuses.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            Settings.driveCatalogs(application).collect { links ->
                val known = _catalogs.value.associateBy { it.link }
                _catalogs.value = links.sorted().map { known[it] ?: CatalogUi(it) }
                links.filter { it !in known }.forEach(::load)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun refresh(link: String) = load(link)

    /** Returns true when the link was taken, so the field can be cleared. */
    fun addCatalog(text: String): Boolean {
        val ref = parseFolderLink(text)
        if (ref == null) {
            _message.value = "That doesn't look like a Google Drive folder link."
            return false
        }
        if (_catalogs.value.any { parseFolderLink(it.link)?.id == ref.id }) {
            _message.value = "That catalog is already here."
            return false
        }
        viewModelScope.launch { Settings.addDriveCatalog(getApplication(), text.trim()) }
        return true
    }

    fun removeCatalog(link: String) {
        viewModelScope.launch { Settings.removeDriveCatalog(getApplication(), link) }
    }

    fun setWifiOnly(on: Boolean) {
        viewModelScope.launch { Settings.setDriveWifiOnly(getApplication(), on) }
    }

    fun download(book: RemoteBook) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { drive.download(book, wifiOnly.value) }
                refreshStatuses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "That download could not start."
            }
        }
    }

    fun cancel(book: RemoteBook) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { drive.cancel(book) }
            refreshStatuses()
        }
    }

    fun coverRequest(book: RemoteBook): Pair<String, Map<String, String>>? = drive.coverRequest(book)

    fun bookIdOf(book: RemoteBook): String = drive.bookIdOf(book)

    /** Keeps progress fresh while the screen is open, and adds books whose last file has landed. */
    suspend fun watch() {
        var lastActive = -1
        while (true) {
            val active = refreshStatuses()
            val adding = _statuses.value.values.any { it.state == DownloadState.ADDING }
            if (active < lastActive || adding) {
                val added = drive.finishDownloads { NowPlaying.loadedBookId == it }
                if (added > 0) {
                    _message.value = if (added == 1) "Added to your library" else "$added books added to your library"
                    refreshStatuses()
                }
            }
            lastActive = active
            delay(POLL_MS)
        }
    }

    private fun load(link: String) {
        update(link) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val catalog = drive.readCatalog(link)
                update(link) { it.copy(name = catalog.name, books = catalog.books, loading = false) }
                refreshStatuses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(link) { it.copy(loading = false, error = e.message ?: "That catalog could not be read.") }
            }
        }
    }

    private fun update(link: String, change: (CatalogUi) -> CatalogUi) {
        _catalogs.value = _catalogs.value.map { if (it.link == link) change(it) else it }
    }

    /** Returns how many downloads are still going. */
    private suspend fun refreshStatuses(): Int = withContext(Dispatchers.IO) {
        val transfers = drive.transfers()
        val chapters = repository.books.first().associate { it.id to it.chapterCount }
        val statuses = HashMap<String, BookStatus>()
        for (catalog in _catalogs.value) {
            for (book in catalog.books) {
                statuses[book.folderId] = drive.status(book, transfers, chapters[drive.bookIdOf(book)])
            }
        }
        _statuses.value = statuses
        transfers.count { it.active }
    }
}
