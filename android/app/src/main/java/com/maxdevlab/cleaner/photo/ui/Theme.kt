package com.maxdevlab.cleaner.photo.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF007AFF)
val Ink = Color(0xFF1C1C1E)
val Paper = Color(0xFFF2F2F7)
val Soft = Color(0xFFE5E5EA)
val Muted = Color(0xFF8E8E93)
val Destructive = Color(0xFFFF3B30)
val DestructiveSoft = Color(0xFFFFE5E3)
val DarkAccent = Color(0xFF0A84FF)
private val Colors = lightColorScheme(
    primary = Accent, onPrimary = Color.White, secondary = Accent,
    primaryContainer = Soft, onPrimaryContainer = Accent,
    secondaryContainer = Soft, onSecondaryContainer = Accent,
    surfaceContainer = Paper, surfaceContainerHigh = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Paper,
    surfaceContainerHighest = Soft, surfaceBright = Color.White, surfaceDim = Paper,
    surfaceTint = Accent, outline = Muted, inverseSurface = Ink, inverseOnSurface = Color.White,
    inversePrimary = DarkAccent, tertiary = Accent, onTertiary = Color.White,
    background = Paper, surface = Color.White, onBackground = Ink, onSurface = Ink,
    onSurfaceVariant = Muted, surfaceVariant = Soft, error = Destructive,
    errorContainer = DestructiveSoft, outlineVariant = Color(0xFFD1D1D6)
)
@Composable fun CleanerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
