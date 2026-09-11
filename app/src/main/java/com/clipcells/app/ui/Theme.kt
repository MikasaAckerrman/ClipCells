package com.clipcells.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val MonoScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1C1C1C),
    onSecondaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF050505),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF3A3A3A),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF050505),
)

@Composable
internal fun ClipCellsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MonoScheme, content = content)
}
