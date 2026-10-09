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

/**
 * Стекло-гармония: главный экран говорит на языке оверлея — те же глубины,
 * тот же голубой акцент буфера (#8AB4F8), мягкие рамки вместо резких.
 * Ничего не «режет глаз»: контраст текста мягкий, границы полупрозрачные.
 */
internal val GlassScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF10141C),
    primaryContainer = Color(0xFF8AB4F8),
    onPrimaryContainer = Color(0xFF10141C),
    secondary = Color(0xFF9AA3AD),
    onSecondary = Color(0xFF10141C),
    secondaryContainer = Color(0xFF1D1F26),
    onSecondaryContainer = Color(0xFFE9EBF0),
    background = Color(0xFF0B0C10),
    onBackground = Color(0xFFF5F6FA),
    surface = Color(0xFF14151A),
    onSurface = Color(0xFFF5F6FA),
    surfaceVariant = Color(0xFF1D1F26),
    onSurfaceVariant = Color(0xFF9AA3AD),
    outline = Color(0xFF33363F),
    inverseSurface = Color(0xFFE9EBF0),
    inverseOnSurface = Color(0xFF0B0C10),
)

@Composable
internal fun ClipCellsTheme(content: @Composable () -> Unit) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(190)) }
    MaterialTheme(colorScheme = GlassScheme) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = entrance.value },
        ) {
            content()
        }
    }
}
