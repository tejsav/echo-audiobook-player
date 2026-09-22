package com.echo.player.ui.library

import android.app.DownloadManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.echo.player.drive.BookStatus
import com.echo.player.drive.DownloadState
import com.echo.player.drive.RemoteBook
import com.echo.player.ui.deck.PillButton
import com.echo.player.ui.deck.ToggleRow
import com.echo.player.ui.dial.paperGrid
import com.echo.player.ui.theme.EchoType
import com.echo.player.ui.theme.Paper
import com.echo.player.util.formatBytes

/**
 * Books from the Drive catalogs this person has added. ECHO ships with none: a catalog is a link
 * someone chose to share with them.
 */
@Composable
fun LibraryScreen(
    onClose: () -> Unit,
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    BackHandler(onBack = onClose)
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.watch() }
    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper.Bg)
            .paperGrid(Paper.Grid)
            // Nothing underneath reacts while this is open.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Header(onClose) }

            if (!viewModel.hasKey) {
                item {
                    Panel {
                        Text("DRIVE IS NOT SET UP IN THIS BUILD", style = EchoType.Label, color = Paper.Accent)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "It was built without a Google Drive API key, so catalogs cannot be read. " +
                                "The README explains how to add one.",
                            style = EchoType.Body,
                            color = Paper.InkSoft
                        )
                    }
                }
            }

            item {
                Panel {
                    Text("ADD A CATALOG", style = EchoType.Label, color = Paper.InkSoft)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("Google Drive folder link", style = EchoType.Label) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    PillButton(
                        text = "Add catalog",
                        selected = true,
                        onClick = { if (viewModel.addCatalog(link)) link = "" }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "A folder shared as \"Anyone with the link\". Each folder inside it is a book.",
                        style = EchoType.Body,
                        color = Paper.InkFaint
                    )
                }
            }

            item {
                ToggleRow(
                    title = "Download on Wi-Fi only",
                    note = "Books are large. Downloads wait for Wi-Fi and carry on by themselves.",
                    on = wifiOnly,
                    enabled = true,
                    onToggle = { viewModel.setWifiOnly(!wifiOnly) }
                )
            }

            catalogs.forEach { catalog ->
                item(key = "catalog:" + catalog.link) {
                    CatalogHeader(
                        catalog = catalog,
                        onRefresh = { viewModel.refresh(catalog.link) },
                        onRemove = { viewModel.removeCatalog(catalog.link) }
                    )
                }
                items(catalog.books, key = { catalog.link + "/" + it.folderId }) { book ->
                    BookRow(
                        book = book,
                        status = statuses[book.folderId],
                        wifiOnly = wifiOnly,
                        cover = viewModel.coverRequest(book),
                        onDownload = { viewModel.download(book) },
                        onCancel = { viewModel.cancel(book) },
                        onOpen = { onOpenBook(viewModel.bookIdOf(book)) }
                    )
                }
            }

            item {
                Text(
                    text = "Downloaded books are kept in ECHO's own storage. Removing a series, or " +
                        "uninstalling ECHO, deletes its files. Make a backup to keep your place.",
                    style = EchoType.Body,
                    color = Paper.InkFaint,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp)
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(8.dp),
                containerColor = Paper.Ink,
                contentColor = Paper.Bg,
                actionColor = Paper.Accent
            )
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
    Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Drive library",
                style = EchoType.Display.copy(fontSize = 37.sp, lineHeight = 38.sp),
                color = Paper.Ink
            )
            Spacer(Modifier.height(2.dp))
            Text("BOOKS SHARED WITH YOU", style = EchoType.Label, color = Paper.InkSoft)
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, Paper.Hair, CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Text("×", style = EchoType.Title, color = Paper.Ink)
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Paper.Disc)
            .border(1.dp, Paper.Hair, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        content()
    }
}

@Composable
private fun CatalogHeader(catalog: CatalogUi, onRefresh: () -> Unit, onRemove: () -> Unit) {
    var confirmRemove by remember(catalog.link) { mutableStateOf(false) }
    Column(Modifier.padding(start = 4.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = (catalog.name ?: "Catalog").uppercase(),
                style = EchoType.Label,
                color = Paper.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            PillButton(text = "Refresh", onClick = onRefresh)
            Spacer(Modifier.width(6.dp))
            PillButton(
                text = if (confirmRemove) "Tap again" else "Remove",
                danger = true,
                onClick = { if (confirmRemove) onRemove() else confirmRemove = true }
            )
        }
        val note = when {
            catalog.loading -> "Reading catalog…"
            catalog.error != null -> catalog.error
            catalog.books.isEmpty() -> "No books in this folder yet. Each folder inside it becomes a book."
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = note,
                style = EchoType.Body,
                color = if (catalog.error != null) Paper.Accent else Paper.InkFaint
            )
        }
    }
}

@Composable
private fun BookRow(
    book: RemoteBook,
    status: BookStatus?,
    wifiOnly: Boolean,
    cover: Pair<String, Map<String, String>>?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Paper.Disc)
            .border(1.dp, Paper.Hair, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Paper.Ink)
        ) {
            if (cover != null) {
                val request = remember(cover.first) {
                    ImageRequest.Builder(context)
                        .data(cover.first)
                        .apply { cover.second.forEach { (name, value) -> setHeader(name, value) } }
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = EchoType.TitleSmall,
                color = Paper.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = book.chapterCount.toString() + " CHAPTERS  ·  " + formatBytes(book.totalBytes),
                style = EchoType.LabelTiny,
                color = Paper.InkFaint
            )
            val line = statusLine(status, wifiOnly)
            if (line != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = line,
                    style = EchoType.Body,
                    color = if (status?.state == DownloadState.FAILED) Paper.Accent else Paper.InkSoft
                )
            }
            if (status?.state == DownloadState.DOWNLOADING && status.bytesTotal > 0L) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (status.bytesDone.toFloat() / status.bytesTotal.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Paper.Accent,
                    trackColor = Paper.TickOff,
                    drawStopIndicator = {}
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (status?.state) {
            DownloadState.NOT_DOWNLOADED -> PillButton(
                text = if (status.filesDone > 0) "Resume" else "Download",
                selected = true,
                onClick = onDownload
            )
            DownloadState.DOWNLOADING -> PillButton(text = "Cancel", onClick = onCancel)
            DownloadState.FAILED -> PillButton(text = "Retry", selected = true, onClick = onDownload)
            DownloadState.IN_LIBRARY -> PillButton(text = "Open", onClick = onOpen)
            DownloadState.NEW_CHAPTERS -> PillButton(text = "Get new", selected = true, onClick = onDownload)
            DownloadState.ADDING, null -> Unit
        }
    }
}

private fun statusLine(status: BookStatus?, wifiOnly: Boolean): String? = when (status?.state) {
    null -> "Checking…"
    DownloadState.NOT_DOWNLOADED -> if (status.filesDone > 0) "Partly downloaded" else null
    DownloadState.DOWNLOADING -> if (status.waiting) {
        if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection"
    } else {
        status.filesDone.toString() + " of " + status.filesTotal + " files  ·  " +
            formatBytes(status.bytesDone) + " of " + formatBytes(status.bytesTotal)
    }
    DownloadState.FAILED -> when (status.failureReason) {
        403, 429 -> "Google refused. Shared files that are downloaded a lot hit a daily limit; try later."
        404 -> "A file is no longer in the Drive folder. Refresh the catalog."
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough space on the phone."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME ->
            "The connection dropped. Retry to carry on."
        else -> "Some files did not download."
    }
    DownloadState.ADDING -> "Adding to your library…"
    DownloadState.IN_LIBRARY -> "In your library"
    DownloadState.NEW_CHAPTERS ->
        if (status.newChapters == 1) "1 new chapter" else status.newChapters.toString() + " new chapters"
}
