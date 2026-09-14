package com.echo.player.ui.dial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.echo.player.ui.theme.EchoType
import com.echo.player.ui.theme.Paper
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TICKS = 168

/** Everything one disc needs to draw itself. */
data class DialContent(
    /** Only used to colour the fallback label; the name itself is shown in the header. */
    val seriesName: String,
    /** Author or narrator, printed above the chapter. Omitted when there is none. */
    val topLabel: String?,
    val chapterTitle: String,
    val meta: String,
    val elapsed: String,
    val remaining: String,
    val coverPath: String?,
    val progress: Float
)

/**
 * A series as a disc.
 *
 * The tick ring around the edge is both the progress read-out and the scrubber, but it will not
 * move until you tap it: an audiobook you have to hunt back through because your thumb brushed the
 * edge is worse than one extra tap. Tapping arms the ring and shows the handle; it disarms itself
 * shortly after.
 *
 * The middle of the disc is deliberately left free of gestures so a vertical swipe there still
 * pages to the next series.
 */
@Composable
fun Dial(
    content: DialContent,
    interactive: Boolean,
    scrubArmed: Boolean,
    onArm: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onMore: () -> Unit,
    onTracks: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        val diameter = maxWidth
        val faceInset = diameter * 0.075f
        val contentInset = diameter * 0.16f
        val labelSize = diameter * 0.23f

        // The white face, sunk inside the ring of ticks.
        Box(
            Modifier
                .fillMaxSize()
                .padding(faceInset)
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = Paper.DiscShadow,
                    spotColor = Paper.DiscShadow
                )
                .background(Paper.Disc, CircleShape)
                .border(1.dp, Paper.DiscEdge, CircleShape)
        )

        TickRing(
            progress = content.progress,
            showHandle = scrubArmed,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (interactive) {
                        Modifier.scrubRing(
                            armed = scrubArmed,
                            onArm = onArm,
                            onScrub = onScrub,
                            onScrubFinished = onScrubFinished
                        )
                    } else {
                        Modifier
                    }
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val topLabel = content.topLabel
            if (!topLabel.isNullOrBlank()) {
                Text(
                    text = topLabel.uppercase(),
                    style = EchoType.Label,
                    color = Paper.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(7.dp))
            }
            Text(
                text = content.chapterTitle,
                style = EchoType.Title,
                color = Paper.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(13.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                SkipButton(seconds = "10", back = true, enabled = interactive, onClick = onSkipBack)
                Spacer(Modifier.width(13.dp))
                RecordLabel(
                    coverPath = content.coverPath,
                    seriesName = content.seriesName,
                    modifier = Modifier.size(labelSize)
                )
                Spacer(Modifier.width(13.dp))
                SkipButton(
                    seconds = "15",
                    back = false,
                    enabled = interactive,
                    onClick = onSkipForward
                )
            }

            Spacer(Modifier.height(13.dp))

            Text(
                text = content.elapsed,
                style = EchoType.MonoLarge,
                color = Paper.Ink,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (scrubArmed) {
                    "DRAG THE RING"
                } else {
                    content.meta + "   " + content.remaining
                },
                style = EchoType.LabelTiny,
                color = if (scrubArmed) Paper.Accent else Paper.InkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // The track read-out is also the way into the track list. Padding is applied either
                // way so arming the ring does not shift the layout.
                modifier = if (interactive && !scrubArmed) {
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onTracks)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                } else {
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                }
            )

            if (interactive) {
                Spacer(Modifier.height(9.dp))
                MoreButton(onClick = onMore)
            }
        }
    }
}

/**
 * The scrub ring. Only the outer band takes the gesture, and it consumes the events it takes so
 * the pager underneath does not also scroll.
 */
private fun Modifier.scrubRing(
    armed: Boolean,
    onArm: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit
): Modifier = this.pointerInput(armed) {
    awaitEachGesture {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f

        val down = awaitFirstDown(requireUnconsumed = false)
        val distance = (down.position - centre).getDistance()
        if (distance < radius * 0.68f || distance > radius * 1.10f) {
            return@awaitEachGesture
        }

        if (!armed) {
            // Nothing is consumed here, so a swipe that happens to start on the ring still pages
            // the carousel. Only a clean tap — no travel — arms the ring.
            val up = waitForUpOrCancellation()
            if (up != null &&
                (up.position - down.position).getDistance() < viewConfiguration.touchSlop
            ) {
                up.consume()
                onArm()
            }
            return@awaitEachGesture
        }

        down.consume()
        var fraction = fractionFor(down.position, centre)
        onScrub(fraction)

        drag(down.id) { change ->
            change.consume()
            fraction = fractionFor(change.position, centre)
            onScrub(fraction)
        }

        onScrubFinished(fraction)
    }
}

/** Angle around the dial, measured clockwise from twelve o'clock. */
private fun fractionFor(position: Offset, centre: Offset): Float {
    val dx = position.x - centre.x
    val dy = position.y - centre.y
    var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    if (degrees < 0f) degrees += 360f
    return (degrees / 360f).coerceIn(0f, 1f)
}

@Composable
private fun TickRing(
    progress: Float,
    showHandle: Boolean,
    modifier: Modifier = Modifier
) {
    // Palette entries are composable getters, so they must be read before the draw scope.
    val playedColor = Paper.Ink
    val remainingColor = Paper.TickOff
    val handleColor = Paper.Accent
    val handleRing = Paper.Disc

    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val outer = radius - 2.dp.toPx()
        val minorLength = 7.dp.toPx()
        val majorLength = 13.dp.toPx()
        val clamped = progress.coerceIn(0f, 1f)
        val middle = Offset(size.width / 2f, size.height / 2f)

        repeat(TICKS) { index ->
            val fraction = index / TICKS.toFloat()
            val major = index % 21 == 0
            val inner = outer - if (major) majorLength else minorLength
            val radians = Math.toRadians((fraction * 360f - 90f).toDouble())
            val cosA = cos(radians).toFloat()
            val sinA = sin(radians).toFloat()

            drawLine(
                color = if (fraction <= clamped) playedColor else remainingColor,
                start = Offset(middle.x + cosA * inner, middle.y + sinA * inner),
                end = Offset(middle.x + cosA * outer, middle.y + sinA * outer),
                strokeWidth = if (major) 2.dp.toPx() else 1.1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        if (showHandle) {
            val headRadians = Math.toRadians((clamped * 360f - 90f).toDouble())
            val headRadius = outer - majorLength / 2f
            val head = Offset(
                middle.x + cos(headRadians).toFloat() * headRadius,
                middle.y + sin(headRadians).toFloat() * headRadius
            )
            drawCircle(handleColor.copy(alpha = 0.18f), 13.dp.toPx(), head)
            drawCircle(handleRing, 7.5.dp.toPx(), head)
            drawCircle(handleColor, 5.5.dp.toPx(), head)
        }
    }
}

/**
 * The cover art, sitting where a record label would. It deliberately does not spin: a turning
 * picture is tiring to look at, and the ring already says whether anything is playing.
 */
@Composable
private fun RecordLabel(
    coverPath: String?,
    seriesName: String,
    modifier: Modifier = Modifier
) {
    val fallback = remember(seriesName) { fallbackColour(seriesName) }

    Box(
        modifier
            .clip(CircleShape)
            .background(fallback),
        contentAlignment = Alignment.Center
    ) {
        if (coverPath != null) {
            AsyncImage(
                model = File(coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Spindle hole, so it reads as a record rather than a sticker.
        Box(
            Modifier
                .size(9.dp)
                .background(Paper.Disc, CircleShape)
                .border(1.dp, Paper.DiscEdge, CircleShape)
        )
    }
}

@Composable
private fun SkipButton(
    seconds: String,
    back: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    CircleButton(diameter = 40.dp, enabled = enabled, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (back) "↺" else "↻",
                style = EchoType.LabelTiny,
                color = Paper.InkSoft
            )
            Text(text = seconds, style = EchoType.LabelTiny, color = Paper.Ink)
        }
    }
}

@Composable
private fun MoreButton(onClick: () -> Unit) {
    CircleButton(diameter = 34.dp, enabled = true, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(3.5.dp)
                        .background(Paper.InkSoft, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun CircleButton(
    diameter: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .border(1.dp, Paper.Hair, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun fallbackColour(seed: String): Color {
    val hue = (seed.hashCode().absoluteValue % 360).toFloat()
    return Color.hsl(hue, 0.32f, 0.52f)
}
