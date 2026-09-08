package com.echo.player.ui.dial

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.echo.player.ui.theme.Paper

/**
 * The one control. Everything else on the screen is information or a small adjustment; this is the
 * only thing you reach for without looking, so it gets the accent colour and the largest target.
 */
@Composable
fun PlayButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 84.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "play-press")

    Box(
        modifier = modifier.size(diameter + 22.dp),
        contentAlignment = Alignment.Center
    ) {
        // The cradle the button sits in.
        Box(
            Modifier
                .size(diameter + 22.dp)
                .clip(CircleShape)
                .background(Paper.Disc)
                .border(1.dp, Paper.Hair, CircleShape)
        )

        Box(
            Modifier
                .size(diameter)
                .scale(scale)
                .shadow(if (enabled) 10.dp else 0.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> Paper.TickOff
                        pressed -> Paper.AccentPressed
                        else -> Paper.Accent
                    }
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            TransportGlyph(isPlaying = isPlaying, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun TransportGlyph(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (isPlaying) {
            val barWidth = size.width * 0.26f
            drawRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.16f, 0f),
                size = Size(barWidth, size.height)
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.58f, 0f),
                size = Size(barWidth, size.height)
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.20f, 0f)
                lineTo(size.width * 0.94f, size.height / 2f)
                lineTo(size.width * 0.20f, size.height)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

/**
 * Faint engineering-paper ruling behind everything. Takes its colour as an argument because a
 * Modifier factory is not composable and cannot read the palette itself.
 */
fun Modifier.paperGrid(color: Color, step: Dp = 28.dp): Modifier = this.drawWithCache {
    val spacing = step.toPx()
    onDrawBehind {
        var x = spacing
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
            x += spacing
        }
        var y = spacing
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
            y += spacing
        }
    }
}
