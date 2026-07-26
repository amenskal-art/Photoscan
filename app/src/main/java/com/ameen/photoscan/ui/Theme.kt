package com.ameen.photoscan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Instrument palette. The app is a measuring device pointed at the real world, so the chrome stays
 * near-black and every saturated colour means something: jade = live and tracking, amber = about to
 * fire or needs attention, coral = lost tracking.
 */
object Ink {
    val Base = Color(0xFF080A0F)
    val Surface = Color(0xFF121722)
    val SurfaceHi = Color(0xFF1B2230)
    val Line = Color(0xFF2A3342)
    val Text = Color(0xFFE8EDF5)
    val TextDim = Color(0xFF8A97AC)
    val Signal = Color(0xFF4CE0B3)
    val SignalDim = Color(0xFF1E7F66)
    val Amber = Color(0xFFFFB454)
    val Coral = Color(0xFFFF6B6B)
    val Violet = Color(0xFF8B7CF6)
}

private val scheme = darkColorScheme(
    primary = Ink.Signal,
    onPrimary = Ink.Base,
    secondary = Ink.Amber,
    onSecondary = Ink.Base,
    background = Ink.Base,
    onBackground = Ink.Text,
    surface = Ink.Surface,
    onSurface = Ink.Text,
    surfaceVariant = Ink.SurfaceHi,
    onSurfaceVariant = Ink.TextDim,
    error = Ink.Coral,
    outline = Ink.Line
)

private val mono = FontFamily.Monospace

private val type = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 21.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = Ink.TextDim),
    // Anything that reports a measurement uses the mono face, so numbers never reflow while they tick.
    labelLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.4.sp)
)

@Composable
fun PhotoScanTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme() // the instrument is always dark
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
