package com.echo.player.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.player.stats.DayPart
import com.echo.player.stats.ListeningStats
import com.echo.player.stats.SeriesStats
import com.echo.player.ui.dial.paperGrid
import com.echo.player.ui.theme.EchoType
import com.echo.player.ui.theme.Paper
import com.echo.player.util.formatDurationShort
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private val BigNumber = EchoType.Display.copy(fontSize = 28.sp, lineHeight = 30.sp)

/**
 * Everything the listening log knows, and nothing it does not. Listening from before the log
 * existed was never recorded, so the screen says when counting began instead of guessing.
 */
@Composable
fun StatsScreen(stats: ListeningStats?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatsHeader(stats, onClose) }

            if (stats != null && stats.allTimeMs == 0L) {
                item {
                    Card {
                        Text("NOTHING RECORDED YET", style = EchoType.Label, color = Paper.InkSoft)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Listening is logged from this version on. Play something and it shows up here.",
                            style = EchoType.Body,
                            color = Paper.InkSoft
                        )
                    }
                }
            }

            if (stats != null && stats.allTimeMs > 0L) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tile("TODAY", formatDurationShort(stats.todayMs), Modifier.weight(1f))
                        Tile("THIS WEEK", formatDurationShort(stats.weekMs), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tile("THIS MONTH", formatDurationShort(stats.monthMs), Modifier.weight(1f))
                        Tile("ALL TIME", formatDurationShort(stats.allTimeMs), Modifier.weight(1f))
                    }
                }

                item {
                    Card("LAST 30 DAYS") {
                        Bars(stats.lastDays.map { it.toFloat() }, Modifier.fillMaxWidth().height(96.dp))
                        Spacer(Modifier.height(8.dp))
                        EndLabels("30 DAYS AGO", "TODAY")
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Listened on " + stats.lastDays.count { it > 0L } + " of 30 days  ·  best day " +
                                formatDurationShort(stats.lastDays.max()),
                            style = EchoType.Body,
                            color = Paper.InkSoft
                        )
                    }
                }

                item {
                    Card("STREAK") {
                        Row {
                            Big(days(stats.currentStreak), "CURRENT", Modifier.weight(1f))
                            Big(days(stats.longestStreak), "LONGEST", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Note("A day counts once you listen for 5 minutes.")
                    }
                }

                item {
                    Card("TIME OF DAY") {
                        val top = stats.dayParts.values.max().coerceAtLeast(1L)
                        DayPart.values().forEach { part ->
                            val ms = stats.dayParts.getValue(part)
                            PartRow(
                                part = part,
                                ms = ms,
                                fraction = ms.toFloat() / top.toFloat(),
                                highlight = ms == top
                            )
                        }
                    }
                }

                item {
                    Card("SESSIONS") {
                        Row {
                            Big(stats.sessionCount.toString(), "SESSIONS", Modifier.weight(1f))
                            Big(formatDurationShort(stats.averageSessionMs), "AVERAGE", Modifier.weight(1f))
                            Big(formatDurationShort(stats.longestSessionMs), "LONGEST", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Note("A pause of more than 2 minutes starts a new session. Plays under a minute count as time, not as sessions.")
                    }
                }

                item {
                    Card("CHAPTERS FINISHED") {
                        Row {
                            Big(stats.chaptersThisWeek.toString(), "THIS WEEK", Modifier.weight(1f))
                            Big(stats.lastWeeksChapters.sum().toString(), "LAST 8 WEEKS", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                        Bars(stats.lastWeeksChapters.map { it.toFloat() }, Modifier.fillMaxWidth().height(64.dp))
                        Spacer(Modifier.height(8.dp))
                        EndLabels("8 WEEKS AGO", "THIS WEEK")
                        Spacer(Modifier.height(10.dp))
                        Note("Only chapters that played through to the end. Chapters marked by hand are not counted.")
                    }
                }

                val series = stats.series.sortedByDescending { it.listenedMs }
                if (series.isNotEmpty()) {
                    item {
                        Text(
                            text = "SERIES",
                            style = EchoType.Label,
                            color = Paper.InkSoft,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                        )
                    }
                    items(series, key = { it.bookId }) { SeriesCard(it) }
                }
            }
        }
    }
}

@Composable
private fun StatsHeader(stats: ListeningStats?, onClose: () -> Unit) {
    Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Listening",
                style = EchoType.Display.copy(fontSize = 37.sp, lineHeight = 38.sp),
                color = Paper.Ink
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stats?.countingSince
                    ?.let { "COUNTING SINCE " + it.format(DATE).uppercase() }
                    ?: "COUNTING FROM YOUR NEXT LISTEN",
                style = EchoType.Label,
                color = Paper.InkSoft
            )
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
private fun Card(label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Paper.Disc)
            .border(1.dp, Paper.Hair, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        if (label != null) {
            Text(label, style = EchoType.Label, color = Paper.InkSoft)
            Spacer(Modifier.height(14.dp))
        }
        content()
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Paper.Disc)
            .border(1.dp, Paper.Hair, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(label, style = EchoType.LabelTiny, color = Paper.InkFaint)
        Spacer(Modifier.height(6.dp))
        Text(value, style = BigNumber, color = Paper.Ink, maxLines = 1)
    }
}

@Composable
private fun Big(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = BigNumber, color = Paper.Ink, maxLines = 1)
        Text(label, style = EchoType.LabelTiny, color = Paper.InkFaint)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = EchoType.Body, color = Paper.InkFaint)
}

@Composable
private fun EndLabels(start: String, end: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(start, style = EchoType.LabelTiny, color = Paper.InkFaint)
        Spacer(Modifier.weight(1f))
        Text(end, style = EchoType.LabelTiny, color = Paper.InkFaint)
    }
}

/** A bar per value, the last one (today, this week) in the accent. Empty slots show as a dot. */
@Composable
private fun Bars(values: List<Float>, modifier: Modifier) {
    // Palette entries are composable getters; read before the draw scope.
    val ink = Paper.Ink
    val accent = Paper.Accent
    val empty = Paper.TickOff
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val peak = values.max().coerceAtLeast(1f)
        val slot = size.width / values.size
        val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(barWidth / 2f)
        values.forEachIndexed { i, value ->
            val left = i * slot + (slot - barWidth) / 2f
            val height = if (value > 0f) (size.height * value / peak).coerceAtLeast(barWidth) else barWidth
            drawRoundRect(
                color = when {
                    value <= 0f -> empty
                    i == values.lastIndex -> accent
                    else -> ink
                },
                topLeft = Offset(left, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius
            )
        }
    }
}

@Composable
private fun PartRow(part: DayPart, ms: Long, fraction: Float, highlight: Boolean) {
    val (name, hours) = when (part) {
        DayPart.MORNING -> "Morning" to "5AM–NOON"
        DayPart.AFTERNOON -> "Afternoon" to "NOON–5PM"
        DayPart.EVENING -> "Evening" to "5–9PM"
        DayPart.NIGHT -> "Night" to "9PM–5AM"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(96.dp)) {
            Text(name, style = EchoType.TitleSmall, color = Paper.Ink)
            Text(hours, style = EchoType.LabelTiny, color = Paper.InkFaint)
        }
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(CircleShape)
                .background(Paper.TickOff)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (highlight && ms > 0L) Paper.Accent else Paper.Ink)
            )
        }
        Text(
            text = formatDurationShort(ms),
            style = EchoType.Mono,
            color = Paper.InkSoft,
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp)
        )
    }
}

@Composable
private fun SeriesCard(series: SeriesStats) {
    Card {
        Text(series.title, style = EchoType.Title, color = Paper.Ink, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Fact("First logged", series.startedOn?.format(DATE) ?: "Not yet")
        Fact("Days listened", series.daysActive.toString())
        Fact("Time in", formatDurationShort(series.listenedMs))
        Fact("Chapters done", series.chaptersDone.toString() + " of " + series.chapterCount)
        val finish = series.finishOn
        Fact(
            "Finish around",
            when {
                series.chapterCount > 0 && series.chaptersDone >= series.chapterCount -> "Finished"
                finish != null -> finish.format(DATE)
                else -> "Not enough recent listening"
            }
        )
        if (finish != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AT YOUR PACE OVER THE LAST 2 WEEKS",
                style = EchoType.LabelTiny,
                color = Paper.InkFaint
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = EchoType.Body, color = Paper.InkSoft, modifier = Modifier.weight(1f))
        Text(value, style = EchoType.TitleSmall, color = Paper.Ink)
    }
}

private fun days(count: Int): String = if (count == 1) "1 day" else "$count days"
