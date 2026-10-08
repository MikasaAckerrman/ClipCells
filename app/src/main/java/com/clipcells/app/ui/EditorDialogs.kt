package com.clipcells.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellWithMessages
import kotlinx.coroutines.launch
import java.util.Collections

internal fun copiedText(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "Скопировано $n сообщение"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "Скопировано $n сообщения"
    else -> "Скопировано $n сообщений"
}

private data class MessageDraft(val id: Long, val text: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CellEditorDialog(source: CellWithMessages?, onDismiss: () -> Unit, onSave: (CellDraft) -> Unit) {
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
    var discardConfirm by remember { mutableStateOf(false) }
    var lastAddedId by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var lastDeletedMessage by remember { mutableStateOf<Pair<Int, MessageDraft>?>(null) }
    val editorSnackbar = remember { SnackbarHostState() }

    val dirty = name != initialName || messages.map { it.text.trim() } != initialMessages
    fun requestClose() = if (dirty) { discardConfirm = true } else onDismiss()

    BackHandler { requestClose() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // drag-reorder state
    val draggedIndex = remember { mutableIntStateOf(-1) }
    val dragAccum = remember { mutableFloatStateOf(0f) }
    val stridePx = with(LocalDensity.current) { 57.dp.toPx() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

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
                Text(
                    if (source == null) "Новая ячейка" else "Редактирование",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                MonoField(
                    value = name,
                    onValueChange = { name = it },
                    caption = "Название",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)))
                Spacer(Modifier.height(10.dp))
                Text(
                    "Тяните строку для перестановки",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(messages, key = { _, item -> item.id }) { index, item ->
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier
                                .animateItemPlacement()
                                .graphicsLayer {
                                    alpha = if (draggedIndex.intValue == index) 0.85f else 1f
                                    shadowElevation = if (draggedIndex.intValue == index) 12f else 0f
                                }
                                .pointerInput(item.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedIndex.intValue = index
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
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            } else if (dragAccum.floatValue < -stridePx && current > 0) {
                                                Collections.swap(messages, current, current - 1)
                                                draggedIndex.intValue = current - 1
                                                dragAccum.floatValue += stridePx
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = { draggedIndex.intValue = -1; dragAccum.floatValue = 0f },
                                        onDragCancel = { draggedIndex.intValue = -1; dragAccum.floatValue = 0f },
                                    )
                                },
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                "Перетащить",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                            MonoField(
                                value = item.text,
                                onValueChange = { messages[index] = item.copy(text = it) },
                                caption = "Сообщение ${index + 1}",
                                modifier = Modifier.weight(1f),
                                fieldHeight = 88.dp,
                                autoFocus = item.id == lastAddedId,
                            )
                            IconButton(
                                onClick = {
                                    if (messages.size > 1) {
                                        val deleted = messages.removeAt(index)
                                        lastDeletedMessage = index to deleted
                                        scope.launch {
                                            val action = editorSnackbar.showSnackbar(
                                                "Сообщение удалено",
                                                "Отменить",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (action == SnackbarResult.ActionPerformed) {
                                                lastDeletedMessage?.let { (pos, msg) ->
                                                    messages.add(pos.coerceAtMost(messages.size), msg)
                                                    lastDeletedMessage = null
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = messages.size > 1,
                            ) { Icon(Icons.Default.Close, "Удалить сообщение") }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val newId = nextId--
                        messages.add(MessageDraft(newId, ""))
                        lastAddedId = newId
                        scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Default.Add, null); Text(" Добавить сообщение") }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { requestClose() }) { Text("Отмена") }
                    Button(onClick = {
                        onSave(CellDraft(source?.cell?.id, name, messages.map { it.text }, source?.cell?.colorArgb ?: 0xFF6750A4, source?.cell?.icon, null))
                    }) { Text("Сохранить") }
                }
                SnackbarHost(editorSnackbar)
            }
        }
    }
    if (discardConfirm) {
        AlertDialog(
            onDismissRequest = { discardConfirm = false },
            title = { Text("Закрыть редактор?") },
            text = { Text("Несохранённые изменения будут потеряны.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("Продолжить") } },
        )
    }
}

@Composable
private fun MonoField(
    value: String,
    onValueChange: (String) -> Unit,
    caption: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    fieldHeight: androidx.compose.ui.unit.Dp = 44.dp,
    autoFocus: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
    val border = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val captionColor = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = captionColor)
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 4,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
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
internal fun MessageSelectorDialog(cell: CellWithMessages, onDismiss: () -> Unit, onCopy: (List<Long>) -> Unit) {
    var selected by remember(cell.cell.id) { mutableStateOf(listOf<Long>()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(Modifier.padding(18.dp)) {
                Text(cell.cell.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Выберите сообщения в нужном порядке", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cell.orderedMessages, key = { it.id }, contentType = { "message" }) { message ->
                        val order = selected.indexOf(message.id)
                        Card(
                            onClick = { selected = if (order >= 0) selected - message.id else selected + message.id },
                            modifier = Modifier.height(92.dp),
                            shape = RoundedCornerShape(percent = 32),
                            colors = CardDefaults.cardColors(
                                containerColor = if (order >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Box(Modifier.fillMaxSize().padding(8.dp)) {
                                Text(
                                    message.text,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (order >= 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (order >= 0) {
                                    Text(
                                        "${order + 1}",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.align(Alignment.BottomEnd),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                    Button(onClick = { onCopy(selected) }) { Text("Копировать") }
                }
            }
        }
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
