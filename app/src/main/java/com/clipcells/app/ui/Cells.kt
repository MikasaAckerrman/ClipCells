package com.clipcells.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipcells.app.data.CellWithMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val HOLD_TO_OPEN_MILLIS = 2_700

@Composable
internal fun EmptyState(modifier: Modifier, onCreate: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "Здесь появятся ваши ячейки",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text("Создайте первую ячейку и добавьте сообщения", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreate) { Icon(Icons.Default.Add, null); Text(" Создать") }
    }
}

@Composable
internal fun CellCard(
    cell: CellWithMessages,
    height: androidx.compose.ui.unit.Dp,
    mode: HomeMode,
    selected: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val holdProgress = remember(cell.cell.id) { Animatable(0f) }
    val press = remember(cell.cell.id) { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val gestureScope = rememberCoroutineScope()
    // Новый шейп: современная плитка 20dp (было 32% — «приевшаяся» форма).
    val shape = RoundedCornerShape(20.dp)
    val borderBrush = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary
    val gesture = Modifier.pointerInput(cell.cell.id, mode) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val downTime = System.currentTimeMillis()
            var held = false
            val timer = gestureScope.launch {
                try {
                    press.animateTo(1f, tween(90, easing = FastOutSlowInEasing))
                    holdProgress.animateTo(1f, tween(HOLD_TO_OPEN_MILLIS, easing = FastOutSlowInEasing))
                    held = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold()
                } catch (_: CancellationException) {
                    // жест прерван — диалог не открываем
                }
            }
            val up = waitForUpOrCancellation()
            timer.cancel()
            if (up != null && !held && System.currentTimeMillis() - downTime < 600) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTap()
            }
            gestureScope.launch {
                holdProgress.snapTo(0f)
                press.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                scaleX = 1f - 0.05f * press.value
                scaleY = 1f - 0.05f * press.value
            }
            .then(gesture),
        shape = shape,
        colors = CardDefaults.cardColors(
            // Выбранная карточка — стеклянная подсветка (не заливка):
            // фон чуть глубже + акцентная рамка, текст не инвертируется.
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // волна нажатия и рамка — внутри клипа карточки, поверх фона
                    val pressedNow = press.value
                    if (pressedNow > 0f) {
                        drawRect(color = borderBrush.copy(alpha = pressedNow * 0.14f))
                    }
                    val hold = holdProgress.value
                    val width = (if (selected) 2f else 1f + hold * 3f).dp.toPx()
                    val brush = if (selected) {
                        accent
                    } else {
                        borderBrush.copy(alpha = 0.35f + hold * 0.65f)
                    }
                    val radius = 0.32f * minOf(size.width, size.height)
                    drawRoundRect(
                        color = brush,
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = width),
                    )
                },
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    cell.cell.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${cell.messages.size} сообщ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(selected, Modifier.align(Alignment.TopEnd)) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                }
            }
            if (mode == HomeMode.EDIT) {
                Text(
                    "Изменить",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
