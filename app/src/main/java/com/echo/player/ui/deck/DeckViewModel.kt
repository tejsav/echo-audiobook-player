package com.echo.player.ui.deck

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echo.player.data.BackupBook
import com.echo.player.data.Book
import com.echo.player.data.Bookmark
import com.echo.player.data.BookImporter
import com.echo.player.data.Chapter
import com.echo.player.BuildConfig
import com.echo.player.data.Settings
import com.echo.player.update.UpdateUi
import com.echo.player.echoApp
import com.echo.player.playback.AudioStats
import com.echo.player.playback.NowPlaying
import com.echo.player.playback.PlaybackService
import com.echo.player.playback.PlaybackUiState
import com.echo.player.playback.SleepMode
import com.echo.player.playback.SleepTimer
import com.echo.player.stats.ListeningStats
import com.echo.player.stats.computeStats
import com.echo.player.util.formatClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

private typealias ProgressReporter = suspend (BookImporter.Progress) -> Unit

/** Restarting the chapter you are already near the top of loses nothing worth undoing. */
private const val UNDO_WORTH_MS = 10_000L

data class ImportUiState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val label: String = ""
) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total.toFloat() else 0f
}

/** Where the listener was before a chapter jump made in the app, so the jump can be undone. */
data class Jump(
    val bookId: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val label: String
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.echoApp.repository
    private val connection = application.echoApp.playerConnection

    val books: StateFlow<List<Book>> = repository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackUiState> = connection.state

    val sleepMode: StateFlow<SleepMode> = SleepTimer.mode
    val sleepRemainingMs: StateFlow<Long> = SleepTimer.remainingMs

    /** What is being decoded right now, read from the file rather than assumed. */
    val stats: StateFlow<AudioStats?> = NowPlaying.stats

    private val _lastJump = MutableStateFlow<Jump?>(null)
    val lastJump: StateFlow<Jump?> = _lastJump.asStateFlow()

    /** The disc currently centred in the carousel. */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** Chapters for the centred disc only — the track list never needs more than one series. */
    val selectedChapters: StateFlow<List<Chapter>> = _selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.chapters(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedBookmarks: StateFlow<List<Bookmark>> = _selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.bookmarks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Worked out from the log only while the stats screen is watching. */
    val listening: StateFlow<ListeningStats?> = combine(
        repository.sessions,
        repository.completionTimes,
        repository.doneCounts,
        repository.books
    ) { sessions, completions, done, books ->
        computeStats(
            sessions = sessions,
            completionTimes = completions,
            doneByBook = done.associate { it.bookId to it.done },
            books = books,
            now = System.currentTimeMillis(),
            zone = ZoneId.systemDefault()
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val levelVolume: StateFlow<Boolean> = Settings.levelVolume(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Series from a restored backup that still need their folder picked on this device. */
    private val _unlinked = MutableStateFlow<List<BackupBook>>(emptyList())
    val unlinked: StateFlow<List<BackupBook>> = _unlinked.asStateFlow()

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

    init {
        viewModelScope.launch {
            repository.fillNamePrefixes()
            val grew = repository.pickUpNewFiles { id ->
                NowPlaying.loadedBookId == id || playback.value.bookId == id
            }
            if (grew > 0) {
                _message.value = "New files found in $grew series"
            }
            // Drive books whose downloads finished while ECHO was closed.
            application.echoApp.drive.finishDownloads { id ->
                NowPlaying.loadedBookId == id || playback.value.bookId == id
            }
        }
    }

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

    fun playChapter(book: Book, index: Int, startMs: Long = 0L) {
        rememberJumpFrom(book, toIndex = index)
        if (isLoaded(book)) {
            connection.seekToChapter(index, startMs)
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
                startPositionMs = startMs
            )
        }
    }

    private fun rememberJumpFrom(book: Book, toIndex: Int) {
        val live = isLoaded(book)
        val fromIndex = if (live) playback.value.chapterIndex else book.currentChapterIndex
        val fromPosition = if (live) playback.value.positionMs else book.currentPositionMs
        if (fromIndex == toIndex && fromPosition < UNDO_WORTH_MS) return
        _lastJump.value = Jump(
            bookId = book.id,
            chapterIndex = fromIndex,
            positionMs = fromPosition,
            label = "Ch " + (fromIndex + 1) + " · " + formatClock(fromPosition)
        )
    }

    /** Puts the listener back exactly where they were before the last chapter jump. */
    fun undoJump() {
        val jump = _lastJump.value ?: return
        _lastJump.value = null
        val book = books.value.firstOrNull { it.id == jump.bookId } ?: return
        if (isLoaded(book)) {
            connection.seekToChapter(jump.chapterIndex, jump.positionMs)
            return
        }
        viewModelScope.launch {
            val loaded = repository.bookWithChapters(book.id) ?: return@launch
            exactStart = null
            connection.openBook(
                book = loaded.book,
                chapters = loaded.chapters,
                autoPlay = true,
                startIndex = jump.chapterIndex,
                startPositionMs = jump.positionMs
            )
        }
    }

    /** Correcting the record by hand: marking a chapter heard, or clearing a wrong mark. */
    fun setCompleted(book: Book, index: Int, completed: Boolean) {
        viewModelScope.launch { repository.setCompleted(book.id, index, completed) }
    }

    fun completeBefore(book: Book, index: Int) {
        viewModelScope.launch { repository.completeBefore(book.id, index) }
    }

    fun setSpeed(book: Book, speed: Float) {
        if (isLoaded(book)) connection.setSpeed(speed)
        viewModelScope.launch { repository.saveSpeed(book.id, speed) }
    }

    // -- updates --------------------------------------------------------------------------------

    private val updater = application.echoApp.updater
    val update: StateFlow<UpdateUi> = updater.state
    val updatesEnabled: Boolean = updater.enabled
    val silentUpdates: Boolean = updater.silent

    init {
        // Updates install quietly, so say so the first time the new version opens.
        viewModelScope.launch {
            val current = BuildConfig.VERSION_NAME
            val last = Settings.lastRunVersion(application).first()
            if (last != null && last != current) _message.value = "Updated to ECHO $current"
            if (last != current) Settings.setLastRunVersion(application, current)
        }
    }

    fun checkForUpdate() = updater.checkNow()

    fun installUpdate() = updater.installNow()

    fun confirmUpdate() = updater.confirm()

    fun updatePermissionIntent() = updater.permissionIntent()

    // -- bookmarks and names --------------------------------------------------------------------

    /** Marks where you are right now in [book]: the live position when it is loaded. */
    fun addBookmark(book: Book) {
        val live = isLoaded(book)
        val index = if (live) playback.value.chapterIndex else book.currentChapterIndex
        val position = if (live) playback.value.positionMs else book.currentPositionMs
        viewModelScope.launch {
            val chapter = repository.bookWithChapters(book.id)?.chapters?.getOrNull(index)
                ?: return@launch
            repository.addBookmark(book.id, chapter.uri, position)
        }
    }

    fun setBookmarkNote(id: Long, note: String) {
        viewModelScope.launch { repository.setBookmarkNote(id, note) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    /** Display only. The queue is rebuilt so the notification shows the same names. */
    fun setTidyNames(book: Book, tidy: Boolean) {
        viewModelScope.launch {
            repository.setTidyNames(book.id, tidy)
            repository.bookWithChapters(book.id)?.let { loaded ->
                connection.refreshMetadata(loaded.book, loaded.chapters)
            }
        }
    }

    fun setLevelVolume(on: Boolean) {
        viewModelScope.launch { Settings.setLevelVolume(getApplication(), on) }
    }

    // -- backup and restore ---------------------------------------------------------------------

    fun writeBackup(target: Uri) {
        viewModelScope.launch {
            _message.value = try {
                repository.writeBackup(target)
                "Backup saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "The backup could not be saved"
            }
        }
    }

    fun restoreBackup(source: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val waiting = repository.restore(repository.readBackup(source))
                _unlinked.value = waiting
                if (waiting.isEmpty()) "Backup restored" else "Restored. Pick folders for " + waiting.size + " series"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "That backup could not be restored"
            }
        }
    }

    fun relink(saved: BackupBook, treeUri: Uri) = startImport { report ->
        repository.relink(saved, treeUri, report).also {
            _unlinked.value = _unlinked.value.filter { it !== saved }
        }
    }

    fun skipRelink(saved: BackupBook) {
        _unlinked.value = _unlinked.value.filter { it !== saved }
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
