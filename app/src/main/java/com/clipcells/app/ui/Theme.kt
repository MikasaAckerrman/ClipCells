package com.clipcells.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

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
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(190)) }
    MaterialTheme(colorScheme = MonoScheme) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = entrance.value },
        ) {
            content()
        }
    }
}
