package com.echo.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Three voices: a heavy display face for the wordmark, a small tracked-out label for anything
 * printed on the interface, and a monospace for numbers so the clock does not jitter the layout
 * as it counts.
 */
object EchoType {

    val Display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 52.sp,
        lineHeight = 50.sp,
        letterSpacing = (-2.4).sp
    )

    val Label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.5.sp
    )

    val LabelTiny = Label.copy(fontSize = 9.sp, letterSpacing = 1.2.sp)

    /** Chapter names and series names on the disc. */
    val Title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.3).sp
    )

    val TitleSmall = Title.copy(fontSize = 14.sp, lineHeight = 17.sp)

    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp
    )

    val MonoLarge = Mono.copy(
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    )

    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
}

internal val EchoTypography = Typography().let { base ->
    base.copy(
        headlineLarge = EchoType.Display,
        titleLarge = EchoType.Title.copy(fontSize = 19.sp),
        titleMedium = EchoType.Title,
        titleSmall = EchoType.TitleSmall,
        bodyMedium = EchoType.Body,
        bodySmall = EchoType.Body.copy(fontSize = 12.sp),
        labelLarge = EchoType.Label.copy(fontSize = 12.sp),
        labelMedium = EchoType.Label,
        labelSmall = EchoType.LabelTiny
    )
}
