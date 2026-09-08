package com.echo.player.ui.deck

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echo.player.data.Book
import com.echo.player.data.BookImporter
import com.echo.player.data.Chapter
import com.echo.player.echoApp
import com.echo.player.playback.PlaybackService
import com.echo.player.playback.PlaybackUiState
import com.echo.player.playback.SleepMode
import com.echo.player.playback.SleepTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private typealias ProgressReporter = suspend (BookImporter.Progress) -> Unit

data class ImportUiState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val label: String = ""
) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total.toFloat() else 0f
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.echoApp.repository
    private val connection = application.echoApp.playerConnection

    val books: StateFlow<List<Book>> = repository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackUiState> = connection.state

    val sleepMode: StateFlow<SleepMode> = SleepTimer.mode
    val sleepRemainingMs: StateFlow<Long> = SleepTimer.remainingMs

    /** The disc currently centred in the carousel. */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** Chapters for the centred disc only — the track list never needs more than one series. */
    val selectedChapters: StateFlow<List<Chapter>> = _selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.chapters(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set after an import so the screen can bring the new disc forward and offer to name it. */
    private val _justImported = MutableStateFlow<String?>(null)
    val justImported: StateFlow<String?> = _justImported.asStateFlow()

    fun consumeJustImported() {
        _justImported.value = null
    }

    private var importJob: Job? = null

    /**
     * A position the listener set deliberately by scrubbing a series that is not loaded. It is
     * handed to the player as an exact start so the 15 second pick-up rewind does not undo it.
     */
    private var exactStart: Pair<String, Long>? = null

    fun select(bookId: String?) {
        _selectedId.value = bookId
    }

    fun consumeMessage() {
        _message.value = null
    }

    // -- transport ------------------------------------------------------------------------------

    private fun isLoaded(book: Book): Boolean =
        playback.value.bookId == book.id && playback.value.hasItem

    private fun chapterDurationOf(book: Book): Long =
        if (isLoaded(book) && playback.value.durationMs > 0L) {
            playback.value.durationMs
        } else {
            book.currentChapterDurationMs
        }

    fun togglePlay(book: Book) {
        if (isLoaded(book)) {
            connection.togglePlayPause()
            return
        }
        viewModelScope.launch {
            val loaded = repository.bookWithChapters(book.id) ?: return@launch
            val exact = exactStart?.takeIf { it.first == book.id }?.second
            exactStart = null
            connection.openBook(
                book = loaded.book,
                chapters = loaded.chapters,
                autoPlay = true,
                startPositionMs = exact
            )
        }
    }

    /** Scrubbing the ring. [fraction] is 0..1 around the dial. */
    fun scrubTo(book: Book, fraction: Float) {
        val duration = chapterDurationOf(book)
        if (duration > 0L) {
            seek(book, (duration * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    fun skipBack(book: Book) = nudge(book, -PlaybackService.SEEK_BACK_MS)

    fun skipForward(book: Book) = nudge(book, PlaybackService.SEEK_FORWARD_MS)

    private fun nudge(book: Book, deltaMs: Long) {
        if (isLoaded(book)) {
            if (deltaMs < 0L) connection.seekBack() else connection.seekForward()
            return
        }
        val duration = chapterDurationOf(book)
        val ceiling = if (duration > 0L) duration else Long.MAX_VALUE
        seek(book, (book.currentPositionMs + deltaMs).coerceIn(0L, ceiling))
    }

    private fun seek(book: Book, positionMs: Long) {
        if (isLoaded(book)) {
            connection.seekTo(positionMs)
            return
        }
        // Not loaded: write the new point straight to storage so it survives even if the listener
        // never presses play, and remember it as an exact start for when they do.
        exactStart = book.id to positionMs
        viewModelScope.launch {
            repository.saveProgress(book.id, book.currentChapterIndex, positionMs)
        }
    }

    fun playChapter(book: Book, index: Int) {
        if (isLoaded(book)) {
            connection.playChapter(index)
            return
        }
        viewModelScope.launch {
            val loaded = repository.bookWithChapters(book.id) ?: return@launch
            exactStart = null
            connection.openBook(
                book = loaded.book,
                chapters = loaded.chapters,
                autoPlay = true,
                startIndex = index,
                startPositionMs = 0L
            )
        }
    }

    fun setSpeed(book: Book, speed: Float) {
        if (isLoaded(book)) connection.setSpeed(speed)
        viewModelScope.launch { repository.saveSpeed(book.id, speed) }
    }

    // -- sleep timer ----------------------------------------------------------------------------

    fun startSleep(minutes: Int) = SleepTimer.startCountdown(minutes)

    fun sleepAtEndOfChapter() = SleepTimer.stopAtEndOfChapter()

    fun cancelSleep() = SleepTimer.cancel()

    // -- library --------------------------------------------------------------------------------

    fun importFolder(treeUri: Uri) =
        startImport { report -> repository.importFolder(treeUri, report) }

    fun importFiles(uris: List<Uri>) =
        startImport { report -> repository.importFiles(uris, report) }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun rename(book: Book, title: String, author: String?) {
        viewModelScope.launch { repository.rename(book.id, title, author) }
    }

    /**
     * Stores a gallery picture as this series' cover. Every chapter takes its artwork from the
     * series, so this also refreshes the notification and lock screen for anything playing.
     */
    fun setCover(book: Book, imageUri: Uri) {
        viewModelScope.launch {
            val stored = repository.setCover(book.id, imageUri)
            if (stored == null) {
                _message.value = "That picture could not be read"
                return@launch
            }
            repository.bookWithChapters(book.id)?.let { loaded ->
                connection.refreshMetadata(loaded.book, loaded.chapters)
            }
        }
    }

    fun clearCover(book: Book) {
        viewModelScope.launch {
            repository.clearCover(book.id)
            repository.bookWithChapters(book.id)?.let { loaded ->
                connection.refreshMetadata(loaded.book, loaded.chapters)
            }
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book)
            _message.value = "Removed " + book.title
        }
    }

    private fun startImport(work: suspend (ProgressReporter) -> Book) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState(running = true, label = "Preparing")
            try {
                val book = work { progress ->
                    _importState.value = ImportUiState(
                        running = true,
                        done = progress.done,
                        total = progress.total,
                        label = progress.label
                    )
                }
                _selectedId.value = book.id
                _justImported.value = book.id
                _message.value = "Added " + book.title
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not import that"
            } finally {
                _importState.value = ImportUiState()
            }
        }
    }
}
