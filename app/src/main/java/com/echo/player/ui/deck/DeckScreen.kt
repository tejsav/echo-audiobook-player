package com.echo.player.ui.deck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echo.player.data.Book
import com.echo.player.data.Chapter
import com.echo.player.playback.MediaIds
import com.echo.player.playback.RESUME_REWIND_MS
import com.echo.player.playback.PlaybackUiState
import com.echo.player.playback.SleepMode
import com.echo.player.ui.dial.Dial
import com.echo.player.ui.dial.DialContent
import com.echo.player.ui.dial.PlayButton
import com.echo.player.ui.dial.paperGrid
import com.echo.player.ui.stats.StatsScreen
import com.echo.player.ui.theme.EchoType
import com.echo.player.ui.theme.Paper
import com.echo.player.util.formatClock
import com.echo.player.util.formatDurationShort
import com.echo.player.util.formatSpeed
import com.echo.player.util.sharedPrefix
import com.echo.player.util.tidyChapterTitle
import kotlinx.coroutines.delay
import kotlin.math.abs
import java.time.LocalDate
import kotlin.math.absoluteValue

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_MINUTES = listOf(15, 30, 45, 60)

/** How long the scrub ring stays live after you tap it. */
private const val ARM_TIMEOUT_MS = 4_500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(viewModel: DeckViewModel = viewModel()) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val chapters by viewModel.selectedChapters.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val sleepMode by viewModel.sleepMode.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepRemainingMs.collectAsStateWithLifecycle()
    val justImported by viewModel.justImported.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val lastJump by viewModel.lastJump.collectAsStateWithLifecycle()
    val bookmarks by viewModel.selectedBookmarks.collectAsStateWithLifecycle()
    val levelVolume by viewModel.levelVolume.collectAsStateWithLifecycle()
    val unlinked by viewModel.unlinked.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAddSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }

    // The backup series waiting on the folder picker, by id, so it survives the trip to the picker.
    var relinkId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Live scrub position, held here so the ring follows the finger without a round trip.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    // The ring ignores drags until it is tapped, so a stray thumb cannot lose your place.
    var armedBookId by remember { mutableStateOf<String?>(null) }
    var armNonce by remember { mutableIntStateOf(0) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::importFolder) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(uris) }

    val backupWriter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.writeBackup(uri) }

    val backupReader = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.restoreBackup(uri) }

    val relinkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = unlinked.firstOrNull { it.book.id == relinkId }
        relinkId = null
        if (uri != null && target != null) viewModel.relink(target, uri)
    }

    // A restore that still needs folders brings the list of them straight up.
    LaunchedEffect(unlinked.size) {
        if (unlinked.isNotEmpty()) showAddSheet = true
    }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    // Any chapter jump made in the app can be undone, so a stray tap never costs you your place.
    LaunchedEffect(lastJump) {
        val jump = lastJump
        if (jump != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Moved from " + jump.label,
                actionLabel = "Go back",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoJump()
        }
    }

    val pagerState = rememberPagerState(pageCount = { books.size })
    val currentBook = books.getOrNull(pagerState.currentPage)

    LaunchedEffect(currentBook?.id) {
        viewModel.select(currentBook?.id)
        scrubFraction = null
        armedBookId = null
    }

    // Disarm on its own once you stop touching it. Not while a drag is in flight.
    LaunchedEffect(armedBookId, armNonce, scrubFraction != null) {
        if (armedBookId != null && scrubFraction == null) {
            delay(ARM_TIMEOUT_MS)
            armedBookId = null
        }
    }

    // A folder only becomes a series once it has a name and a cover, so offer that straight away.
    LaunchedEffect(justImported, books) {
        val id = justImported
        if (id != null) {
            val index = books.indexOfFirst { it.id == id }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                showMoreSheet = true
                viewModel.consumeJustImported()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper.Bg)
            .paperGrid(Paper.Grid)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Header(
                title = currentBook?.title ?: "No series",
                subtitle = headerSubtitle(currentBook, books.size),
                onAdd = { showAddSheet = true },
                onStats = { showStats = true }
            )

            if (books.isEmpty()) {
                EmptyDeck(
                    modifier = Modifier.weight(1f),
                    onAdd = { folderPicker.launch(null) }
                )
            } else {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 26.dp),
                    pageSpacing = 8.dp
                ) { page ->
                    val book = books[page]
                    val isCurrentPage = page == pagerState.currentPage
                    val distance = (
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        ).absoluteValue
                    val nearness = (1f - distance).coerceIn(0f, 1f)

                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val scale = 0.72f + 0.28f * nearness
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.18f + 0.82f * nearness
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Dial(
                            content = dialContent(
                                book = book,
                                playback = playback,
                                scrub = if (isCurrentPage) scrubFraction else null
                            ),
                            interactive = isCurrentPage,
                            scrubArmed = isCurrentPage && armedBookId == book.id,
                            onArm = {
                                armedBookId = book.id
                                armNonce++
                            },
                            onScrub = { scrubFraction = it },
                            onScrubFinished = { fraction ->
                                scrubFraction = null
                                armNonce++
                                viewModel.scrubTo(book, fraction)
                            },
                            onSkipBack = { viewModel.skipBack(book) },
                            onSkipForward = { viewModel.skipForward(book) },
                            onMore = { showMoreSheet = true },
                            onTracks = { showTracksSheet = true },
                            modifier = Modifier.fillMaxWidth(0.95f)
                        )
                    }
                }
            }

            // Real numbers for what is playing, or nothing at all. The line keeps its height either
            // way so the disc does not jump when they arrive.
            val shownStats = stats?.takeIf { now ->
                currentBook != null &&
                    playback.bookId == currentBook.id &&
                    now.mediaId == MediaIds.create(currentBook.id, playback.chapterIndex)
            }
            Text(
                text = shownStats?.label().orEmpty(),
                style = EchoType.LabelTiny,
                color = Paper.InkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            )

            val playingThis = currentBook != null &&
                playback.bookId == currentBook.id &&
                playback.isPlaying

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlayButton(
                    isPlaying = playingThis,
                    enabled = currentBook != null,
                    onClick = { currentBook?.let(viewModel::togglePlay) }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        currentBook == null -> "ADD A SERIES TO BEGIN"
                        playingThis -> "TAP TO PAUSE"
                        else -> "TAP TO PLAY  ·  SWIPE FOR ANOTHER"
                    },
                    style = EchoType.LabelTiny,
                    color = Paper.InkFaint
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 140.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(8.dp),
                containerColor = Paper.Ink,
                contentColor = Paper.Bg,
                actionColor = Paper.Accent
            )
        }

        if (showStats) {
            val listening by viewModel.listening.collectAsStateWithLifecycle()
            StatsScreen(stats = listening, onClose = { showStats = false })
        }

        if (importState.running) {
            ImportOverlay(state = importState, onCancel = viewModel::cancelImport)
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
            containerColor = Paper.Disc
        ) {
            LibrarySheet(
                levelVolume = levelVolume,
                unlinked = unlinked,
                onPickFolder = {
                    showAddSheet = false
                    folderPicker.launch(null)
                },
                onPickFiles = {
                    showAddSheet = false
                    filePicker.launch(arrayOf("audio/*"))
                },
                onLevelVolume = viewModel::setLevelVolume,
                onBackUp = {
                    showAddSheet = false
                    backupWriter.launch("echo-backup-" + LocalDate.now() + ".json")
                },
                onRestore = {
                    showAddSheet = false
                    backupReader.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                },
                onRelink = { saved ->
                    showAddSheet = false
                    relinkId = saved.book.id
                    relinkPicker.launch(null)
                },
                onSkipRelink = viewModel::skipRelink
            )
        }
    }

    val sheetBook = currentBook
    if (showMoreSheet && sheetBook != null) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            containerColor = Paper.Disc
        ) {
            SeriesSheet(
                book = sheetBook,
                trackSummary = trackSummary(chapters, currentIndexOf(sheetBook, playback)),
                bookmarkCount = bookmarks.size,
                tidyExample = tidyExample(sheetBook, chapters, currentIndexOf(sheetBook, playback)),
                speed = if (playback.bookId == sheetBook.id) {
                    playback.speed
                } else {
                    sheetBook.playbackSpeed
                },
                sleepMode = sleepMode,
                sleepRemainingMs = sleepRemaining,
                onOpenTracks = {
                    showMoreSheet = false
                    showTracksSheet = true
                },
                onOpenBookmarks = {
                    showMoreSheet = false
                    showBookmarksSheet = true
                },
                onTidyNames = { viewModel.setTidyNames(sheetBook, it) },
                onSpeed = { viewModel.setSpeed(sheetBook, it) },
                onSleepMinutes = viewModel::startSleep,
                onSleepEndOfChapter = viewModel::sleepAtEndOfChapter,
                onSleepOff = viewModel::cancelSleep,
                onRename = { title, author -> viewModel.rename(sheetBook, title, author) },
                onPickCover = { uri -> viewModel.setCover(sheetBook, uri) },
                onClearCover = { viewModel.clearCover(sheetBook) },
                onDelete = {
                    viewModel.delete(sheetBook)
                    showMoreSheet = false
                }
            )
        }
    }

    if (showTracksSheet && sheetBook != null) {
        ModalBottomSheet(
            onDismissRequest = { showTracksSheet = false },
            containerColor = Paper.Disc
        ) {
            TracksSheet(
                chapters = chapters,
                displayTitle = sheetBook::displayTitle,
                currentIndex = currentIndexOf(sheetBook, playback),
                isPlaying = playback.bookId == sheetBook.id && playback.isPlaying,
                jumpBack = lastJump?.takeIf { it.bookId == sheetBook.id },
                onPlay = { index, startMs ->
                    viewModel.playChapter(sheetBook, index, startMs)
                    showTracksSheet = false
                },
                onToggleCompleted = { index, done -> viewModel.setCompleted(sheetBook, index, done) },
                onCompleteBefore = { index -> viewModel.completeBefore(sheetBook, index) },
                onJumpBack = {
                    viewModel.undoJump()
                    showTracksSheet = false
                }
            )
        }
    }

    if (showBookmarksSheet && sheetBook != null) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarksSheet = false },
            containerColor = Paper.Disc
        ) {
            val live = playback.bookId == sheetBook.id && playback.hasItem
            val hereIndex = currentIndexOf(sheetBook, playback)
            val herePosition = if (live) playback.positionMs else sheetBook.currentPositionMs
            BookmarksSheet(
                book = sheetBook,
                bookmarks = bookmarks,
                chapters = chapters,
                here = "Ch " + (hereIndex + 1) + " · " + formatClock(herePosition),
                onAdd = { viewModel.addBookmark(sheetBook) },
                onPlay = { index, positionMs ->
                    viewModel.playChapter(sheetBook, index, positionMs)
                    showBookmarksSheet = false
                },
                onSaveNote = viewModel::setBookmarkNote,
                onDelete = viewModel::deleteBookmark
            )
        }
    }
}

// -----------------------------------------------------------------------------------------------

@Composable
private fun Header(title: String, subtitle: String, onAdd: () -> Unit, onStats: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = displayStyleFor(title),
                color = Paper.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = EchoType.Label,
                color = Paper.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, Paper.Hair, CircleShape)
                .clickable(onClick = onStats),
            contentAlignment = Alignment.Center
        ) {
            // Palette entries are composable getters; read before the draw scope.
            val ink = Paper.Ink
            Canvas(Modifier.size(16.dp)) {
                val bar = size.width / 5f
                listOf(0.45f, 1f, 0.7f).forEachIndexed { i, height ->
                    drawRoundRect(
                        color = ink,
                        topLeft = Offset(bar * i * 2, size.height * (1f - height)),
                        size = Size(bar, size.height * height),
                        cornerRadius = CornerRadius(bar / 2f)
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, Paper.Hair, CircleShape)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Text("+", style = EchoType.Title, color = Paper.Ink)
        }
    }
}

@Composable
private fun EmptyDeck(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                // A circle needs a square to live in, or CircleShape gives an ellipse.
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(Paper.Disc)
                .border(1.dp, Paper.Hair, CircleShape)
                .clickable(onClick = onAdd)
                .padding(horizontal = 44.dp)
        ) {
            Text("NO SERIES", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Choose a folder of audio files",
                style = EchoType.TitleSmall,
                color = Paper.Ink,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Every file inside becomes a chapter, in file-name order",
                style = EchoType.Body,
                color = Paper.InkFaint,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ImportOverlay(state: ImportUiState, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Paper.Bg.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(20.dp))
                .background(Paper.Disc)
                .border(1.dp, Paper.Hair, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("READING FOLDER", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.label,
                style = EchoType.TitleSmall,
                color = Paper.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Paper.Accent,
                    trackColor = Paper.TickOff,
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.done.toString() + " / " + state.total,
                    style = EchoType.Mono,
                    color = Paper.InkSoft
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Paper.Accent,
                    trackColor = Paper.TickOff
                )
            }
            Spacer(Modifier.height(20.dp))
            PillButton(text = "Cancel", onClick = onCancel)
        }
    }
}

@Composable
internal fun SheetRow(title: String, note: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Paper.Bg)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Text(title, style = EchoType.Title, color = Paper.Ink)
        Spacer(Modifier.height(4.dp))
        Text(note, style = EchoType.Body, color = Paper.InkSoft)
    }
}

@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) Paper.Ink else Paper.Bg)
            .border(1.dp, if (selected) Paper.Ink else Paper.Hair, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = EchoType.Label,
            color = when {
                selected -> Paper.Disc
                danger -> Paper.Accent
                else -> Paper.Ink
            }
        )
    }
}

// -----------------------------------------------------------------------------------------------

/** Long series names step down in size rather than truncating at the first word. */
private fun displayStyleFor(title: String): TextStyle {
    val size = when {
        title.length <= 10 -> 46.sp
        title.length <= 18 -> 37.sp
        title.length <= 30 -> 29.sp
        else -> 24.sp
    }
    return EchoType.Display.copy(fontSize = size, lineHeight = size * 1.04f)
}

/** The chapter you are in: the live one when a series is loaded, otherwise where you left it. */
private fun currentIndexOf(book: Book, playback: PlaybackUiState): Int =
    if (playback.bookId == book.id && playback.hasItem) {
        playback.chapterIndex
    } else {
        book.currentChapterIndex
    }

private fun trackSummary(chapters: List<Chapter>, currentIndex: Int): String =
    (currentIndex + 1).toString() + " of " + chapters.size + "  ·  " +
        chapters.count { it.completed } + " done"

/** A real chapter name before and after tidying, so the choice is made on what it will look like. */
private fun tidyExample(book: Book, chapters: List<Chapter>, currentIndex: Int): Pair<String, String>? {
    val raw = chapters.getOrNull(currentIndex)?.title ?: chapters.firstOrNull()?.title ?: return null
    val prefix = book.namePrefix ?: sharedPrefix(chapters.map { it.title })
    return raw to tidyChapterTitle(raw, prefix, book.title)
}

private fun headerSubtitle(book: Book?, count: Int): String = when {
    count == 0 -> "NO SERIES YET"
    book == null -> count.toString() + " SERIES"
    book.isFinished -> "FINISHED"
    !book.hasProgress -> formatDurationShort(book.totalDurationMs).uppercase() + " TOTAL"
    else -> formatDurationShort(book.remainingMs).uppercase() + " LEFT"
}

private fun dialContent(
    book: Book,
    playback: PlaybackUiState,
    scrub: Float?
): DialContent {
    val live = playback.bookId == book.id && playback.hasItem
    val duration = if (live && playback.durationMs > 0L) {
        playback.durationMs
    } else {
        book.currentChapterDurationMs
    }
    val chapterIndex = if (live) playback.chapterIndex else book.currentChapterIndex
    val rawPosition = if (live) playback.positionMs else book.currentPositionMs

    val position = if (scrub != null && duration > 0L) {
        (duration * scrub).toLong()
    } else {
        rawPosition
    }
    val fraction = when {
        scrub != null -> scrub
        duration > 0L -> (rawPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    val liveTitle = if (live) playback.chapterTitle else ""
    val title = liveTitle
        .ifBlank { book.currentChapterTitle?.let(book::displayTitle).orEmpty() }
        .ifBlank { "Chapter " + (chapterIndex + 1) }

    return DialContent(
        seriesName = book.title,
        topLabel = book.author,
        chapterTitle = title,
        meta = "TRK " + (chapterIndex + 1).toString().padStart(2, '0') +
            "/" + book.chapterCount.toString().padStart(2, '0'),
        elapsed = formatClock(position),
        remaining = "-" + formatClock((duration - position).coerceAtLeast(0L)),
        coverPath = book.coverPath,
        progress = fraction
    )
}

// -----------------------------------------------------------------------------------------------

@Composable
private fun SeriesSheet(
    book: Book,
    trackSummary: String,
    bookmarkCount: Int,
    tidyExample: Pair<String, String>?,
    speed: Float,
    sleepMode: SleepMode,
    sleepRemainingMs: Long,
    onOpenTracks: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onTidyNames: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSleepEndOfChapter: () -> Unit,
    onSleepOff: () -> Unit,
    onRename: (String, String?) -> Unit,
    onPickCover: (android.net.Uri) -> Unit,
    onClearCover: () -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    var confirmDelete by remember(book.id) { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickCover(uri) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp)
    ) {
        item {
            Text(book.title, style = EchoType.Title, color = Paper.Ink, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(
                text = book.chapterCount.toString() + " CHAPTERS  ·  " +
                    formatDurationShort(book.totalDurationMs).uppercase(),
                style = EchoType.LabelTiny,
                color = Paper.InkFaint
            )
            Spacer(Modifier.height(22.dp))
        }

        item {
            SheetRow(title = "Tracks", note = trackSummary, onClick = onOpenTracks)
            Spacer(Modifier.height(12.dp))
            SheetRow(
                title = "Bookmarks",
                note = if (bookmarkCount == 0) {
                    "None yet · mark a moment and add a note"
                } else {
                    bookmarkCount.toString() + " saved"
                },
                onClick = onOpenBookmarks
            )
            Spacer(Modifier.height(22.dp))
        }

        item {
            Text("CHAPTER NAMES", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    text = "As in files",
                    selected = !book.tidyNames,
                    onClick = { onTidyNames(false) }
                )
                PillButton(text = "Tidy", selected = book.tidyNames, onClick = { onTidyNames(true) })
            }
            if (tidyExample != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (tidyExample.first == tidyExample.second) {
                        "Nothing to tidy in these names"
                    } else {
                        tidyExample.first + "  →  " + tidyExample.second
                    },
                    style = EchoType.Body,
                    color = Paper.InkFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(22.dp))
        }

        item {
            Text("SPEED", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SPEEDS.forEach { option ->
                    PillButton(
                        text = formatSpeed(option),
                        selected = abs(option - speed) < 0.01f,
                        onClick = { onSpeed(option) }
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        item {
            Text("SLEEP TIMER", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillButton(
                    text = if (sleepMode is SleepMode.Countdown) {
                        formatClock(sleepRemainingMs)
                    } else {
                        "OFF"
                    },
                    selected = sleepMode !is SleepMode.Off,
                    onClick = onSleepOff
                )
                SLEEP_MINUTES.forEach { minutes ->
                    PillButton(
                        text = minutes.toString() + "M",
                        selected = sleepMode is SleepMode.Countdown &&
                            sleepMode.totalMinutes == minutes,
                        onClick = { onSleepMinutes(minutes) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            PillButton(
                text = "End of chapter",
                selected = sleepMode is SleepMode.EndOfChapter,
                onClick = onSleepEndOfChapter
            )
            Spacer(Modifier.height(22.dp))
        }

        item {
            Text("COVER & NAME", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    text = if (book.coverPath == null) "Add cover" else "Change cover",
                    onClick = {
                        coverPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                )
                if (book.coverPath != null) {
                    PillButton(text = "Remove", onClick = onClearCover)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Series name", style = EchoType.Label) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("Author / narrator", style = EchoType.Label) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = "Save name",
                onClick = { onRename(title, author) },
                selected = true
            )
        }

        item {
            Spacer(Modifier.height(22.dp))
            PillButton(
                text = if (confirmDelete) "Tap again to remove" else "Remove series",
                danger = true,
                onClick = { if (confirmDelete) onDelete() else confirmDelete = true }
            )
        }
    }
}

// -----------------------------------------------------------------------------------------------

/**
 * Every chapter, with what has actually been heard. Opens on the chapter you are in — even when
 * nothing is loaded — and a tap only arms a row: playing it is a second, deliberate tap, so
 * scrolling a long list can never throw you into a different chapter.
 */
@Composable
private fun TracksSheet(
    chapters: List<Chapter>,
    displayTitle: (String) -> String,
    currentIndex: Int,
    isPlaying: Boolean,
    jumpBack: Jump?,
    onPlay: (Int, Long) -> Unit,
    onToggleCompleted: (Int, Boolean) -> Unit,
    onCompleteBefore: (Int) -> Unit,
    onJumpBack: () -> Unit
) {
    val listState = rememberLazyListState()
    var armedIndex by remember { mutableStateOf<Int?>(null) }
    val unmarkedBefore = chapters.take(currentIndex.coerceAtLeast(0)).count { !it.completed }

    // Land on the current chapter with a few rows above it. Keyed on the list arriving, not on the
    // index, so a chapter change during playback does not yank the list while you are reading it.
    LaunchedEffect(chapters.isNotEmpty()) {
        if (chapters.isNotEmpty()) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)
    ) {
        item {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                Text("TRACKS", style = EchoType.Label, color = Paper.InkSoft)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = trackSummary(chapters, currentIndex).uppercase(),
                    style = EchoType.LabelTiny,
                    color = Paper.InkFaint
                )
                if (jumpBack != null) {
                    Spacer(Modifier.height(14.dp))
                    SheetRow(
                        title = "Go back to " + jumpBack.label,
                        note = "Where you were before the last chapter change",
                        onClick = onJumpBack
                    )
                }
                if (unmarkedBefore > 0) {
                    Spacer(Modifier.height(12.dp))
                    PillButton(
                        text = if (currentIndex == 1) "Mark 1 as done" else "Mark 1–$currentIndex as done",
                        onClick = { onCompleteBefore(currentIndex) }
                    )
                }
            }
        }

        itemsIndexed(chapters, key = { _, chapter -> chapter.index }) { index, chapter ->
            TrackRow(
                chapter = chapter,
                title = displayTitle(chapter.title),
                number = index + 1,
                isCurrent = index == currentIndex,
                isPlaying = isPlaying && index == currentIndex,
                armed = armedIndex == index,
                onArm = {
                    armedIndex = if (armedIndex == index || index == currentIndex) null else index
                },
                onPlay = { startMs -> onPlay(index, startMs) },
                onToggleCompleted = { onToggleCompleted(index, !chapter.completed) }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TrackRow(
    chapter: Chapter,
    title: String,
    number: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    armed: Boolean,
    onArm: () -> Unit,
    onPlay: (Long) -> Unit,
    onToggleCompleted: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent || armed) Paper.Bg else Paper.Disc)
            .clickable(onClick = onArm)
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Its own large target, so fixing a mark never arms or plays the row.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleCompleted),
                contentAlignment = Alignment.Center
            ) {
                ListenMark(chapter = chapter, isCurrent = isCurrent)
            }
            Text(
                text = number.toString().padStart(2, '0'),
                style = EchoType.Mono,
                color = Paper.InkFaint
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = EchoType.TitleSmall,
                    // Finished chapters recede so the ones still ahead are easy to find.
                    color = if (chapter.completed && !isCurrent) Paper.InkSoft else Paper.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val status = when {
                    isCurrent && isPlaying -> "PLAYING"
                    isCurrent -> "YOU ARE HERE"
                    !chapter.completed && chapter.listenedMs > 0L ->
                        (chapter.listenedFraction * 100).toInt().toString() + "% HEARD"
                    else -> null
                }
                if (status != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = status,
                        style = EchoType.LabelTiny,
                        color = if (isCurrent) Paper.Accent else Paper.InkFaint
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatClock(chapter.durationMs),
                style = EchoType.Mono,
                color = Paper.InkSoft
            )
        }
        if (armed) {
            // A chapter left part-way resumes where it was left, 15 seconds back like any pick-up.
            val resumeAt = if (!chapter.completed && chapter.listenedMs > 0L) {
                (chapter.listenedMs - RESUME_REWIND_MS).coerceAtLeast(0L)
            } else {
                null
            }
            Row(
                modifier = Modifier.padding(start = 46.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (resumeAt != null) {
                    PillButton(
                        text = "Resume at " + formatClock(resumeAt),
                        selected = true,
                        onClick = { onPlay(resumeAt) }
                    )
                    PillButton(text = "From start", onClick = { onPlay(0L) })
                } else {
                    PillButton(text = "Play from start", selected = true, onClick = { onPlay(0L) })
                }
            }
        }
    }
}

/** Empty ring: not heard. Filling wedge: partly heard. Solid: finished. */
@Composable
private fun ListenMark(chapter: Chapter, isCurrent: Boolean) {
    // Palette entries are composable getters, so they must be read before the draw scope.
    val track = Paper.TickOff
    val fill = if (isCurrent) Paper.Accent else Paper.Ink
    val heard = if (chapter.completed) 1f else chapter.listenedFraction

    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2f
        drawCircle(color = track, radius = size.minDimension / 2f - inset, style = Stroke(width = stroke))
        when {
            heard >= 1f -> drawCircle(color = fill, radius = size.minDimension / 2f)
            heard > 0f -> drawArc(
                color = fill,
                startAngle = -90f,
                sweepAngle = 360f * heard,
                useCenter = true,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke)
            )
        }
    }
}
