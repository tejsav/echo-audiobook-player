package com.echo.player.ui.deck

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echo.player.data.BackupBook
import com.echo.player.data.Book
import com.echo.player.data.Bookmark
import com.echo.player.data.Chapter
import com.echo.player.ui.theme.EchoType
import com.echo.player.ui.theme.Paper
import com.echo.player.util.formatClock
import java.text.DateFormat
import java.util.Date

/** A bookmark this fresh opens with its note field ready. */
private const val JUST_ADDED_MS = 5_000L

/** Behind the + button: add a series, app-wide playback switches, backup and restore. */
@Composable
internal fun LibrarySheet(
    levelVolume: Boolean,
    unlinked: List<BackupBook>,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onLevelVolume: (Boolean) -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onRelink: (BackupBook) -> Unit,
    onSkipRelink: (BackupBook) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
    ) {
        if (unlinked.isNotEmpty()) {
            Text("WAITING FOR FOLDERS", style = EchoType.Label, color = Paper.Accent)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pick each series' folder to bring back its place, marks and bookmarks.",
                style = EchoType.Body,
                color = Paper.InkSoft
            )
            Spacer(Modifier.height(12.dp))
            unlinked.forEach { saved ->
                SheetRow(
                    title = saved.book.title,
                    note = saved.book.chapterCount.toString() + " chapters · tap to pick its folder",
                    onClick = { onRelink(saved) }
                )
                Spacer(Modifier.height(6.dp))
                PillButton(text = "Skip", onClick = { onSkipRelink(saved) })
                Spacer(Modifier.height(14.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

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

        Spacer(Modifier.height(26.dp))
        Text("PLAYBACK", style = EchoType.Label, color = Paper.InkSoft)
        Spacer(Modifier.height(12.dp))
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ToggleRow(
            title = "Even out volume",
            note = if (supported) {
                "Brings quiet and loud passages closer together, for talks recorded unevenly."
            } else {
                "Needs Android 9 or newer."
            },
            on = levelVolume && supported,
            enabled = supported,
            onToggle = { onLevelVolume(!levelVolume) }
        )

        Spacer(Modifier.height(26.dp))
        Text("BACKUP", style = EchoType.Label, color = Paper.InkSoft)
        Spacer(Modifier.height(12.dp))
        SheetRow(
            title = "Back up",
            note = "Places, done marks, names, covers, bookmarks and your listening log, to a file you choose.",
            onClick = onBackUp
        )
        Spacer(Modifier.height(12.dp))
        SheetRow(
            title = "Restore",
            note = "From a backup file. Series already here update straight away; others ask for their folder.",
            onClick = onRestore
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    note: String,
    on: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Paper.Bg)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = EchoType.Title, color = if (enabled) Paper.Ink else Paper.InkFaint)
            Spacer(Modifier.height(4.dp))
            Text(note, style = EchoType.Body, color = Paper.InkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (on) "ON" else "OFF",
            style = EchoType.Label,
            color = if (on) Paper.Accent else Paper.InkFaint
        )
    }
}

/** Saved moments in one series, newest first, each with an optional note. */
@Composable
internal fun BookmarksSheet(
    book: Book,
    bookmarks: List<Bookmark>,
    chapters: List<Chapter>,
    here: String,
    onAdd: () -> Unit,
    onPlay: (Int, Long) -> Unit,
    onSaveNote: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)
    ) {
        item {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                Text("BOOKMARKS", style = EchoType.Label, color = Paper.InkSoft)
                Spacer(Modifier.height(14.dp))
                SheetRow(
                    title = "Bookmark " + here,
                    note = "Saves this moment, ready for a note.",
                    onClick = onAdd
                )
                if (bookmarks.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No bookmarks in this series yet.",
                        style = EchoType.Body,
                        color = Paper.InkFaint
                    )
                }
            }
        }

        items(bookmarks, key = { it.id }) { mark ->
            val index = chapters.indexOfFirst { it.uri == mark.chapterUri }
            val chapter = chapters.getOrNull(index)
            BookmarkRow(
                mark = mark,
                chapterNumber = if (index >= 0) index + 1 else null,
                chapterTitle = chapter?.let { book.displayTitle(it.title) },
                onPlay = if (index >= 0) ({ onPlay(index, mark.positionMs) }) else null,
                onSaveNote = { note -> onSaveNote(mark.id, note) },
                onDelete = { onDelete(mark.id) }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BookmarkRow(
    mark: Bookmark,
    chapterNumber: Int?,
    chapterTitle: String?,
    onPlay: (() -> Unit)?,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit
) {
    var open by remember(mark.id) {
        mutableStateOf(System.currentTimeMillis() - mark.createdAt < JUST_ADDED_MS)
    }
    var note by remember(mark.id, mark.note) { mutableStateOf(mark.note) }
    var confirmDelete by remember(mark.id) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (open) Paper.Bg else Paper.Disc)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = (chapterNumber?.let { "CH " + it.toString().padStart(2, '0') } ?: "MISSING") +
                    "  ·  " + formatClock(mark.positionMs),
                style = EchoType.Mono,
                color = Paper.Accent
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(mark.createdAt)),
                style = EchoType.LabelTiny,
                color = Paper.InkFaint
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = chapterTitle ?: "That file is no longer in the folder",
            style = EchoType.TitleSmall,
            color = Paper.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!open && mark.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = mark.note,
                style = EchoType.Body,
                color = Paper.InkSoft,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note", style = EchoType.Label) },
                shape = RoundedCornerShape(14.dp),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onPlay != null) {
                    PillButton(text = "Play from here", selected = true, onClick = onPlay)
                }
                PillButton(
                    text = "Save note",
                    onClick = {
                        onSaveNote(note)
                        open = false
                    }
                )
                PillButton(
                    text = if (confirmDelete) "Tap again to delete" else "Delete",
                    danger = true,
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true }
                )
            }
        }
    }
}
