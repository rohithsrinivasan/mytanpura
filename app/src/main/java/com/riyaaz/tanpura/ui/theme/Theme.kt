package com.riyaaz.tanpura.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Warm, dark, wood-and-brass palette. The app is dark-only on purpose: it is
 * usually open in a dim practice room, often for an hour at a time, and the
 * instrument artwork only reads correctly against a dark ground.
 */
object TanpuraColors {
    val Background = Color(0xFF120E0B)
    val Surface = Color(0xFF1C1611)
    val SurfaceHigh = Color(0xFF261E17)
    val SurfaceVariant = Color(0xFF2E241B)
    val Brass = Color(0xFFE8B04B)
    val BrassDim = Color(0xFFB8873A)
    val Wood = Color(0xFF6B4A28)
    val WoodDark = Color(0xFF3A2717)
    val String = Color(0xFFF6E3B8)
    val OnSurface = Color(0xFFEFE5D6)
    val OnSurfaceMuted = Color(0xFFB4A48B)
    val Outline = Color(0xFF4A3B2C)
    val Danger = Color(0xFFE5654B)
    val Accent = Color(0xFF7FC8B0)
}

private val Scheme = darkColorScheme(
    primary = TanpuraColors.Brass,
    onPrimary = Color(0xFF241703),
    primaryContainer = TanpuraColors.WoodDark,
    onPrimaryContainer = TanpuraColors.Brass,
    secondary = TanpuraColors.BrassDim,
    onSecondary = Color(0xFF1C1206),
    secondaryContainer = TanpuraColors.SurfaceVariant,
    onSecondaryContainer = TanpuraColors.OnSurface,
    tertiary = TanpuraColors.Accent,
    onTertiary = Color(0xFF04211A),
    background = TanpuraColors.Background,
    onBackground = TanpuraColors.OnSurface,
    surface = TanpuraColors.Surface,
    onSurface = TanpuraColors.OnSurface,
    surfaceVariant = TanpuraColors.SurfaceVariant,
    onSurfaceVariant = TanpuraColors.OnSurfaceMuted,
    surfaceContainer = TanpuraColors.SurfaceHigh,
    surfaceContainerHigh = TanpuraColors.SurfaceVariant,
    outline = TanpuraColors.Outline,
    outlineVariant = Color(0xFF352A20),
    error = TanpuraColors.Danger,
    onError = Color(0xFF200703),
)

private val TanpuraTypography = Typography(
    displayLarge = TextStyle(fontSize = 62.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Light),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun TanpuraTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme is read so the composable participates in config
    // changes, but the palette stays dark either way.
    @Suppress("UNUSED_VARIABLE")
    val systemDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = Scheme,
        typography = TanpuraTypography,
        content = content,
    )
}
