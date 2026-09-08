package com.echo.player.ui.deck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.echo.player.playback.PlaybackUiState
import com.echo.player.playback.SleepMode
import com.echo.player.ui.dial.Dial
import com.echo.player.ui.dial.DialContent
import com.echo.player.ui.dial.PlayButton
import com.echo.player.ui.dial.paperGrid
import com.echo.player.ui.theme.EchoType
import com.echo.player.ui.theme.Paper
import com.echo.player.util.formatClock
import com.echo.player.util.formatDurationShort
import com.echo.player.util.formatSpeed
import kotlinx.coroutines.delay
import kotlin.math.abs
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

    val snackbarHostState = remember { SnackbarHostState() }
    var showAddSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
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

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
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
                onAdd = { showAddSheet = true }
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
                            modifier = Modifier.fillMaxWidth(0.95f)
                        )
                    }
                }
            }

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
                containerColor = Paper.Ink,
                contentColor = Paper.Bg,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(data.visuals.message, style = EchoType.Label)
            }
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
            AddSheet(
                onPickFolder = {
                    showAddSheet = false
                    folderPicker.launch(null)
                },
                onPickFiles = {
                    showAddSheet = false
                    filePicker.launch(arrayOf("audio/*"))
                }
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
                chapters = chapters,
                playingIndex = if (playback.bookId == sheetBook.id) playback.chapterIndex else -1,
                speed = if (playback.bookId == sheetBook.id) {
                    playback.speed
                } else {
                    sheetBook.playbackSpeed
                },
                sleepMode = sleepMode,
                sleepRemainingMs = sleepRemaining,
                onSelectChapter = { index ->
                    viewModel.playChapter(sheetBook, index)
                    showMoreSheet = false
                },
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
}

// -----------------------------------------------------------------------------------------------

@Composable
private fun Header(title: String, subtitle: String, onAdd: () -> Unit) {
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
private fun AddSheet(onPickFolder: () -> Unit, onPickFiles: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
    ) {
        Text("ADD A SERIES", style = EchoType.Label, color = Paper.InkSoft)
        Spacer(Modifier.height(16.dp))
        SheetRow(
            title = "Choose a folder",
            note = "Recommended. Every audio file inside becomes a chapter, sorted by name.",
            onClick = onPickFolder
        )
        Spacer(Modifier.height(12.dp))
        SheetRow(
            title = "Choose files",
            note = "Pick individual tracks yourself.",
            onClick = onPickFiles
        )
    }
}

@Composable
private fun SheetRow(title: String, note: String, onClick: () -> Unit) {
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
private fun PillButton(
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
        .ifBlank { book.currentChapterTitle.orEmpty() }
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
    chapters: List<Chapter>,
    playingIndex: Int,
    speed: Float,
    sleepMode: SleepMode,
    sleepRemainingMs: Long,
    onSelectChapter: (Int) -> Unit,
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
            Spacer(Modifier.height(22.dp))
            Text("TRACKS", style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(10.dp))
        }

        itemsIndexed(chapters) { index, chapter ->
            val selected = index == playingIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Paper.Bg else Paper.Disc)
                    .clickable { onSelectChapter(index) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (selected) Paper.Accent else Paper.TickOff)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    style = EchoType.Mono,
                    color = Paper.InkFaint
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = chapter.title,
                    style = EchoType.TitleSmall,
                    color = Paper.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatClock(chapter.durationMs),
                    style = EchoType.Mono,
                    color = Paper.InkSoft
                )
            }
            Spacer(Modifier.height(4.dp))
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
