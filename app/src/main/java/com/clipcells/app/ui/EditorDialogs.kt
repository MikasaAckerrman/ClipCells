package com.clipcells.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellWithMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

internal fun copiedText(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "Скопировано $n сообщение"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "Скопировано $n сообщения"
    else -> "Скопировано $n сообщений"
}

private data class MessageDraft(val id: Long, val text: String)

/** Пороги отклика для 3-секундного удержания «Удалить» (рост вибрации). */
private const val DELETE_HOLD_MS = 3_000L

/**
 * Редактор ячейки v0.23 (ТЗ пользователя):
 *
 *  - Сообщения ПОНАЧАЛУ только читаются: тап по тексту ничего не меняет;
 *    карандаш справа включает правку (курсор+клавиатура), галочка завершает.
 *  - Крестика удаления сообщения НЕТ: пустая ячейка (всё стёрто) удаляется
 *    сама при сохранении.
 *  - Удаление ячейки — жест: 3 секунды удержания заголовка с прогрессом
 *    и нарастающей вибрацией → подтверждение «Удалить».
 *  - Подписей «Сообщение N» нет — только приглушённый номер строки.
 *  - Перестановка — только за ручку (не конфликтует с тапом по тексту).
 *  - «Сохранить» — справа ВВЕРХУ (доступно при открытой клавиатуре).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CellEditorDialog(
    source: CellWithMessages?,
    onDismiss: () -> Unit,
    onSave: (CellDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val initialName = remember(source?.cell?.id) { source?.cell?.name.orEmpty().trim() }
    val initialMessages = remember(source?.cell?.id) { source?.orderedMessages?.map { it.text } ?: listOf("") }

    var nextId by remember { mutableLongStateOf(-1L) }
    var name by remember(source?.cell?.id) { mutableStateOf(initialName) }
    val messages = remember(source?.cell?.id) {
        mutableStateListOf<MessageDraft>().apply {
            addAll(
                if (source == null) listOf(MessageDraft(nextId--, ""))
                else source.orderedMessages.map { MessageDraft(it.id, it.text) },
            )
        }
    }
    /** id сообщений, находящихся в режиме правки (карандаш активен). */
    val editingIds = remember(source?.cell?.id) { mutableStateListOf<Long>() }
    var discardConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var lastAddedId by remember { mutableLongStateOf(Long.MIN_VALUE) }

    val dirty = name != initialName || messages.map { it.text.trim() } != initialMessages
    fun requestClose() = if (dirty) { discardConfirm = true } else onDismiss()

    BackHandler { requestClose() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // drag-reorder state
    val draggedIndex = remember { mutableIntStateOf(-1) }
    val dragAccum = remember { mutableFloatStateOf(0f) }
    val stridePx = with(LocalDensity.current) { 96.dp.toPx() }

    // 3-секундное удержание заголовка -> «Удалить»
    val holdProgress = remember { mutableFloatStateOf(0f) }
    val holdJobActive = remember { mutableStateOf(false) }

    fun startDeleteHold() {
        if (source == null || holdJobActive.value) return
        holdJobActive.value = true
        holdProgress.floatValue = 0f
        scope.launch {
            val start = System.currentTimeMillis()
            var lastTick = 0L
            while (isActive && holdJobActive.value) {
                val elapsed = System.currentTimeMillis() - start
                holdProgress.floatValue = (elapsed.toFloat() / DELETE_HOLD_MS).coerceIn(0f, 1f)
                // Нарастающий отклик: 0.5с лёгкий, 1.5с средний, 3с тяжёлый.
                when {
                    elapsed >= DELETE_HOLD_MS -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        holdJobActive.value = false
                        deleteConfirm = true
                        holdProgress.floatValue = 0f
                        return@launch
                    }
                    elapsed - lastTick >= 500L && elapsed >= 500L -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastTick = elapsed
                    }
                }
                delay(50)
            }
        }
    }

    fun stopDeleteHold() {
        holdJobActive.value = false
        holdProgress.floatValue = 0f
    }

    fun performSave() {
        val nameTrim = name.trim()
        val texts = messages.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) {
            // Всё содержимое стёрто — ячейка удаляется сама (ТЗ: 0 символов).
            if (source != null) onDelete() else onDismiss()
            return
        }
        onSave(
            CellDraft(
                source?.cell?.id,
                nameTrim.ifEmpty { "Ячейка" },
                texts,
                source?.cell?.colorArgb ?: 0xFF8AB4F8.toInt(),
                source?.cell?.icon,
                null,
            )
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { requestClose() } },
    ) {
        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                // --- Шапка: ✕ | заголовок (3с = удалить) | Сохранить ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { requestClose() }) {
                        Icon(Icons.Default.Close, "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .pointerInput(source?.cell?.id) {
                                detectTapGestures(
                                    onPress = {
                                        try { awaitRelease() } catch (_: Exception) {}
                                        stopDeleteHold()
                                    },
                                    onLongPress = {
                                        // Долгий тап включает 3-секундный отсчёт.
                                        startDeleteHold()
                                    },
                                )
                            },
                    ) {
                        Text(
                            if (source == null) "Новая ячейка" else "Редактирование",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (holdProgress.floatValue > 0f) {
                            // Индикатор «до удаления»: тонкая линия под заголовком.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .graphicsLayer { scaleX = holdProgress.floatValue.coerceIn(0.02f, 1f) },
                            )
                        }
                    }
                    Button(
                        onClick = { performSave() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Text("Сохранить") }
                }
                Spacer(Modifier.height(12.dp))
                MonoField(
                    value = name,
                    onValueChange = { name = it },
                    caption = "Название",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ручка слева — перестановка · карандаш — правка",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(messages, key = { _, item -> item.id }) { index, item ->
                        val editing = item.id in editingIds
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .animateItemPlacement()
                                .graphicsLayer {
                                    alpha = if (draggedIndex.intValue == index) 0.85f else 1f
                                    shadowElevation = if (draggedIndex.intValue == index) 12f else 0f
                                },
                        ) {
                            // Ручка перестановки: драг ТОЛЬКО отсюда — не
                            // конфликтует с тапом по тексту и карандашом.
                            Icon(
                                Icons.Default.Menu,
                                "Переставить",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (editing) 0.25f else 0.8f),
                                modifier = Modifier
                                    .size(44.dp)
                                    .pointerInput(item.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                // Индекс решается ПО id в момент
                                                // старта: замыкание могло устареть
                                                // после предыдущей перестановки.
                                                draggedIndex.intValue =
                                                    messages.indexOfFirst { it.id == item.id }
                                                dragAccum.floatValue = 0f
                                            },
                                            onDrag = { _, dragAmount ->
                                                val current = draggedIndex.intValue
                                                if (current < 0) return@detectDragGestures
                                                dragAccum.floatValue += dragAmount.y
                                                if (dragAccum.floatValue > stridePx && current < messages.lastIndex) {
                                                    Collections.swap(messages, current, current + 1)
                                                    draggedIndex.intValue = current + 1
                                                    dragAccum.floatValue -= stridePx
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                } else if (dragAccum.floatValue < -stridePx && current > 0) {
                                                    Collections.swap(messages, current, current - 1)
                                                    draggedIndex.intValue = current - 1
                                                    dragAccum.floatValue += stridePx
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = { draggedIndex.intValue = -1; dragAccum.floatValue = 0f },
                                            onDragCancel = { draggedIndex.intValue = -1; dragAccum.floatValue = 0f },
                                        )
                                    },
                            )
                            Spacer(Modifier.width(4.dp))
                            // Номер строки: только цифра, приглушённая.
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.width(24.dp).padding(top = 14.dp),
                            )
                            MonoField(
                                value = item.text,
                                onValueChange = { text ->
                                    if (editing) {
                                        // Пишем ТОЧНО в свою строку (по id):
                                        // захваченный index мог устареть.
                                        val i = messages.indexOfFirst { it.id == item.id }
                                        if (i >= 0) messages[i] = item.copy(text = text)
                                    }
                                },
                                caption = null,
                                modifier = Modifier.weight(1f),
                                fieldHeight = 88.dp,
                                autoFocus = item.id == lastAddedId,
                                readOnly = !editing,
                            )
                            // Карандаш/галочка: включение и завершение правки.
                            IconButton(
                                onClick = {
                                    if (item.id in editingIds) editingIds.remove(item.id)
                                    else {
                                        editingIds.add(item.id)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    if (editing) Icons.Default.Check else Icons.Default.Edit,
                                    if (editing) "Завершить правку" else "Редактировать",
                                    tint = if (editing) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val newId = nextId--
                        messages.add(MessageDraft(newId, ""))
                        editingIds.add(newId)
                        lastAddedId = newId
                        scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Default.Add, null); Text(" Добавить сообщение") }
            }
        }
    }

    if (discardConfirm) {
        AlertDialog(
            onDismissRequest = { discardConfirm = false },
            title = { Text("Отменить изменения?") },
            text = { Text("Правки не сохранятся") },
            confirmButton = { Button(onClick = onDismiss) { Text("Отменить правки") } },
            dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("Продолжить") } },
        )
    }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить ячейку?") },
            text = { Text("«${source?.cell?.name ?: ""}» и все её сообщения") },
            confirmButton = {
                Button(
                    onClick = { deleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Поле ввода «стекла». readOnly: текст читается (рамки почти нет, курсора
 * нет, клавиатура не вызывается) — редактирование только после карандаша.
 */
@Composable
internal fun MonoField(
    value: String,
    onValueChange: (String) -> Unit,
    caption: String?,
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
    fieldHeight: androidx.compose.ui.unit.Dp = 56.dp,
    autoFocus: Boolean = false,
    readOnly: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
    val active = focused && !readOnly
    val border by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = if (readOnly) 0.25f else 1f),
        label = "border",
    )
    Column(modifier) {
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 4,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (readOnly) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                else MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .focusRequester(focusRequester)
                .drawBehind {
                    drawRoundRect(
                        color = border,
                        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            "Введите текст…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun DisplaySettingsDialog(columns: Int, rows: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var c by remember { mutableIntStateOf(columns) }
    var r by remember { mutableIntStateOf(rows) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вид главного экрана") },
        text = {
            Column {
                Text("Столбцов: $c")
                Slider(c.toFloat(), { c = it.toInt() }, valueRange = 2f..5f, steps = 2)
                Text("Видимых строк: $r")
                Slider(r.toFloat(), { r = it.toInt() }, valueRange = 3f..8f, steps = 4)
                Spacer(Modifier.height(8.dp))
                Text("На экране: ${c * r} ячеек. Остальные доступны прокруткой.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(c, r) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
