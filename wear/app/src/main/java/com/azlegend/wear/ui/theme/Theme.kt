package com.azlegend.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

private val Gold = Color(0xFFF3C34F)
private val GoldDim = Color(0xFFC89A2B)
private val GoldDeep = Color(0xFF4A3608)
private val Cream = Color(0xFFFFF0C2)
private val Sand = Color(0xFFE2D2AA)
private val SandDim = Color(0xFFB8A57A)
private val Paper = Color(0xFFF4EDDC)
private val Muted = Color(0xFFB9B09A)

/** Gold-on-black palette matching the AZ Legend web player. */
val AzLegendColorScheme = ColorScheme(
    primary = Gold,
    primaryDim = GoldDim,
    primaryContainer = GoldDeep,
    onPrimary = Color(0xFF261B00),
    onPrimaryContainer = Cream,
    secondary = Sand,
    secondaryDim = SandDim,
    secondaryContainer = Color(0xFF3A3222),
    onSecondary = Color(0xFF221B08),
    onSecondaryContainer = Color(0xFFF1E4C4),
    tertiary = Color(0xFFB9D5C4),
    tertiaryDim = Color(0xFF8FAF9C),
    tertiaryContainer = Color(0xFF243A2E),
    onTertiary = Color(0xFF0B1F14),
    onTertiaryContainer = Color(0xFFD9F0E2),
    surfaceContainerLow = Color(0xFF121110),
    surfaceContainer = Color(0xFF1C1A16),
    surfaceContainerHigh = Color(0xFF29261F),
    onSurface = Paper,
    onSurfaceVariant = Muted,
    outline = Color(0xFF6E6752),
    outlineVariant = Color(0xFF3B3729),
    background = Color.Black,
    onBackground = Paper,
    error = Color(0xFFFF9B8A),
    errorDim = Color(0xFFD86A5A),
    errorContainer = Color(0xFF5A1C12),
    onError = Color(0xFF3A0800),
    onErrorContainer = Color(0xFFFFDAD3),
)

@Composable
fun AzLegendTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AzLegendColorScheme, content = content)
}
