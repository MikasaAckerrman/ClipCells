package com.clipcells.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellWithMessages

internal fun copiedText(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "Скопировано $n сообщение"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "Скопировано $n сообщения"
    else -> "Скопировано $n сообщений"
}

@Composable
internal fun CellEditorDialog(source: CellWithMessages?, onDismiss: () -> Unit, onSave: (CellDraft) -> Unit) {
    val initialName = remember(source?.cell?.id) { source?.cell?.name.orEmpty().trim() }
    val initialMessages = remember(source?.cell?.id) { source?.orderedMessages?.map { it.text } ?: listOf("") }
    val initialInterval = remember(source?.cell?.id) { (source?.cell?.intervalMillis ?: 1_000L).toInt() }
    var name by remember(source?.cell?.id) { mutableStateOf(initialName) }
    val messages = remember(source?.cell?.id) {
        mutableStateListOf<String>().apply { addAll(if (source == null) listOf("") else initialMessages) }
    }
    var interval by remember(source?.cell?.id) { mutableIntStateOf(initialInterval) }
    var discardConfirm by remember { mutableStateOf(false) }

    val dirty = name != initialName || messages.map(String::trim) != initialMessages || interval != initialInterval
    fun requestClose() = if (dirty) { discardConfirm = true } else onDismiss()

    Dialog(onDismissRequest = { requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxSize(0.9f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (source == null) "Новая ячейка" else "Редактирование",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Сообщения — копируются сверху вниз", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages.size) { index ->
                        val text = messages[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                text,
                                { messages[index] = it },
                                label = { Text("Сообщение ${index + 1}") },
                                modifier = Modifier.weight(1f).height(84.dp),
                                minLines = 2,
                                maxLines = 3,
                            )
                            IconButton(onClick = { if (messages.size > 1) messages.removeAt(index) }, enabled = messages.size > 1) {
                                Icon(Icons.Default.Close, "Удалить сообщение")
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { messages.add("") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Text(" Добавить сообщение")
                }
                Spacer(Modifier.height(8.dp))
                Text("Интервал: ${"%.1f".format(interval / 1000f)} сек.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = interval.toFloat(),
                    onValueChange = { interval = ((it / 100).toInt() * 100).coerceIn(300, 3_000) },
                    valueRange = 300f..3_000f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { requestClose() }) { Text("Отмена") }
                    Button(onClick = { onSave(CellDraft(source?.cell?.id, name, messages.toList(), source?.cell?.colorArgb ?: 0xFF6750A4, source?.cell?.icon, interval.toLong())) }) { Text("Сохранить") }
                }
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
