package com.rhazenz0.f1info.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object F1Colors {
    val Red = Color(0xFFE10600)
    val Background = Color(0xFF0F0F14)
    val Surface = Color(0xFF1A1A22)
    val SurfaceHigh = Color(0xFF24242E)
    val Outline = Color(0xFF34343F)
    val TextPrimary = Color(0xFFF4F4F6)
    val TextSecondary = Color(0xFFA8A8B3)

    val Purple = Color(0xFFB138DD)
    val Green = Color(0xFF2BD15F)
    val Yellow = Color(0xFFF5C518)
    val PitBlue = Color(0xFF3B82F6)
    val SegmentNone = Color(0xFF3A3A44)

    val Soft = Color(0xFFDA291C)
    val Medium = Color(0xFFFFD12E)
    val Hard = Color(0xFFF0F0EC)
    val Intermediate = Color(0xFF43B02A)
    val Wet = Color(0xFF0067AD)

    fun compound(name: String?): Color = when (name?.uppercase()) {
        "SOFT" -> Soft
        "MEDIUM" -> Medium
        "HARD" -> Hard
        "INTERMEDIATE" -> Intermediate
        "WET" -> Wet
        else -> TextSecondary
    }

    /** Parses "RRGGBB" team colours from the feed. */
    fun team(hex: String?): Color {
        val clean = hex?.trim()?.removePrefix("#")
        if (clean.isNullOrEmpty() || clean.length != 6) return TextSecondary
        return runCatching { Color(0xFF000000 or clean.toLong(16)) }.getOrDefault(TextSecondary)
    }

    fun flag(flag: String?): Color = when (flag?.uppercase()) {
        "GREEN", "CLEAR" -> Green
        "YELLOW", "DOUBLE YELLOW" -> Yellow
        "RED" -> Red
        "BLUE" -> PitBlue
        "CHEQUERED" -> TextPrimary
        "BLACK AND WHITE" -> TextSecondary
        else -> TextSecondary
    }

    fun trackStatus(code: String?): Color = when (code) {
        "1" -> Green
        "2" -> Yellow
        "4", "6", "7" -> Color(0xFFFF9800)
        "5" -> Red
        else -> TextSecondary
    }
}

private val scheme = darkColorScheme(
    primary = F1Colors.Red,
    onPrimary = Color.White,
    secondary = Color(0xFFFF6B5E),
    background = F1Colors.Background,
    onBackground = F1Colors.TextPrimary,
    surface = F1Colors.Surface,
    onSurface = F1Colors.TextPrimary,
    surfaceVariant = F1Colors.SurfaceHigh,
    onSurfaceVariant = F1Colors.TextSecondary,
    surfaceContainer = F1Colors.Surface,
    surfaceContainerHigh = F1Colors.SurfaceHigh,
    surfaceContainerLow = Color(0xFF15151C),
    outline = F1Colors.Outline,
    outlineVariant = F1Colors.Outline,
    error = Color(0xFFFF6B6B),
)

private val base = Typography()
private val typography = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Black),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
)

/** Monospace digits for timing columns. */
val TimingText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun F1Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
