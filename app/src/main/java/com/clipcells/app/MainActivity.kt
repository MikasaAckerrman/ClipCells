package com.clipcells.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellWithMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class HomeMode { NORMAL, EDIT, DELETE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClipCellsTheme { ClipCellsApp() } }
    }
}

@Composable
private fun ClipCellsTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipCellsApp(vm: MainViewModel = viewModel()) {
    val cells by vm.cells.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("display", 0) }
    var columns by remember { mutableIntStateOf(prefs.getInt("columns", 2).coerceIn(2, 5)) }
    var visibleRows by remember { mutableIntStateOf(prefs.getInt("rows", 3).coerceIn(3, 8)) }
    var mode by remember { mutableStateOf(HomeMode.NORMAL) }
    var editorCell by remember { mutableStateOf<CellWithMessages?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var selectorCell by remember { mutableStateOf<CellWithMessages?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var selectedCells by remember { mutableStateOf(setOf<Long>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startCopy(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        block()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (mode == HomeMode.NORMAL) "ClipCells" else if (mode == HomeMode.EDIT) "Редактирование" else "Выбрано: ${selectedCells.size}") },
                actions = {
                    if (mode == HomeMode.NORMAL) {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") }
                        IconButton(onClick = { mode = HomeMode.EDIT }) { Icon(Icons.Default.Edit, "Редактировать") }
                        IconButton(onClick = { mode = HomeMode.DELETE; selectedCells = emptySet() }, enabled = cells.isNotEmpty()) {
                            Icon(Icons.Default.Delete, "Удалить")
                        }
                    } else {
                        if (mode == HomeMode.DELETE) {
                            IconButton(onClick = { deleteConfirm = true }, enabled = selectedCells.isNotEmpty()) {
                                Icon(Icons.Default.Delete, "Подтвердить выбор")
                            }
                        }
                        IconButton(onClick = { mode = HomeMode.NORMAL; selectedCells = emptySet() }) { Icon(Icons.Default.Close, "Выйти из режима") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (mode == HomeMode.NORMAL) {
                FloatingActionButton(onClick = { editorCell = null; showEditor = true }) { Icon(Icons.Default.Add, "Создать ячейку") }
            }
        },
    ) { padding ->
        if (cells.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding), onCreate = { editorCell = null; showEditor = true })
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                val cardHeight = ((maxHeight - 12.dp * (visibleRows - 1)) / visibleRows).coerceAtLeast(64.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(cells, key = { it.cell.id }) { cell ->
                        CellCard(
                            cell = cell,
                            height = cardHeight,
                            mode = mode,
                            selected = cell.cell.id in selectedCells,
                            onTap = {
                                when (mode) {
                                    HomeMode.NORMAL -> startCopy { vm.copyWhole(cell.cell.id) { error = it.message } }
                                    HomeMode.EDIT -> { editorCell = cell; showEditor = true }
                                    HomeMode.DELETE -> selectedCells = if (cell.cell.id in selectedCells) selectedCells - cell.cell.id else selectedCells + cell.cell.id
                                }
                            },
                            onHold = { if (mode == HomeMode.NORMAL) selectorCell = cell },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        CellEditorDialog(
            source = editorCell,
            onDismiss = { showEditor = false },
            onSave = { draft ->
                vm.save(draft) { result ->
                    result.onSuccess { showEditor = false }.onFailure { error = it.message }
                }
            },
        )
    }
    selectorCell?.let { cell ->
        MessageSelectorDialog(
            cell = cell,
            onDismiss = { selectorCell = null },
            onCopy = { ids ->
                selectorCell = null
                if (ids.isNotEmpty()) startCopy { vm.copySelected(cell.cell.id, ids) { error = it.message } }
            },
        )
    }
    if (showSettings) {
        DisplaySettingsDialog(
            columns = columns,
            rows = visibleRows,
            onDismiss = { showSettings = false },
            onSave = { c, r ->
                columns = c; visibleRows = r
                prefs.edit().putInt("columns", c).putInt("rows", r).apply()
                showSettings = false
            },
        )
    }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить ячейки?") },
            text = { Text("Будут удалены ${selectedCells.size} ячеек и все сообщения внутри.") },
            confirmButton = {
                Button(onClick = {
                    val count = selectedCells.size
                    deleteConfirm = false
                    vm.delete(selectedCells, onDone = {
                        selectedCells = emptySet(); mode = HomeMode.NORMAL
                        scope.launch {
                            val action = snackbar.showSnackbar("Удалено: $count", "Отменить", duration = SnackbarDuration.Short)
                            if (action == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undoDelete { error = it.message }
                        }
                    }, onError = { error = it.message })
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Не удалось выполнить действие") },
            text = { Text(message ?: "Неизвестная ошибка") },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onCreate: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Здесь появятся ваши ячейки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Создайте первую ячейку и добавьте сообщения", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreate) { Icon(Icons.Default.Add, null); Text(" Создать") }
    }
}

@Composable
private fun CellCard(
    cell: CellWithMessages,
    height: androidx.compose.ui.unit.Dp,
    mode: HomeMode,
    selected: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val progress = remember(cell.cell.id) { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val gestureScope = rememberCoroutineScope()
    val shape = RoundedCornerShape(28.dp)
    val accent = Color(cell.cell.colorArgb.toInt())
    val gesture = Modifier.pointerInput(cell.cell.id, mode) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val downTime = System.currentTimeMillis()
            var held = false
            val timer = gestureScope.launch {
                try {
                    progress.animateTo(1f, tween(2_700))
                    held = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold()
                } catch (_: CancellationException) {
                    // удержание прервано раньше 2,7 с — диалог не открываем
                }
            }
            val up = waitForUpOrCancellation()
            timer.cancel()
            if (up != null && !held && System.currentTimeMillis() - downTime < 600) {
                onTap()
            }
            progress.snapTo(0f)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(height).then(gesture).border(
            BorderStroke(if (selected) 3.dp else (1 + progress.value * 4).dp, if (selected) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.45f + progress.value * 0.55f)),
            shape,
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = if (selected) 0.28f else 0.16f)),
    ) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell.cell.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("${cell.messages.size} сообщ.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(selected, Modifier.align(Alignment.TopEnd)) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
                }
            }
            if (mode == HomeMode.EDIT) Text("Изменить", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun CellEditorDialog(source: CellWithMessages?, onDismiss: () -> Unit, onSave: (CellDraft) -> Unit) {
    var name by remember(source?.cell?.id) { mutableStateOf(source?.cell?.name.orEmpty()) }
    val messages = remember(source?.cell?.id) { mutableStateListOf<String>().apply { addAll(source?.orderedMessages?.map { it.text } ?: listOf("")) } }
    var interval by remember(source?.cell?.id) { mutableIntStateOf((source?.cell?.intervalMillis ?: 1_000L).toInt()) }
    var discardConfirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { discardConfirm = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxSize(0.9f), shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                Text(if (source == null) "Новая ячейка" else "Редактирование", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Сообщения — копируются сверху вниз", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages.size) { index ->
                        val text = messages[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(text, { messages[index] = it }, label = { Text("Сообщение ${index + 1}") }, modifier = Modifier.weight(1f), minLines = 2)
                            IconButton(onClick = { if (messages.size > 1) messages.removeAt(index) }, enabled = messages.size > 1) { Icon(Icons.Default.Close, "Удалить сообщение") }
                        }
                    }
                }
                OutlinedButton(onClick = { messages.add("") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(" Добавить сообщение") }
                Spacer(Modifier.height(8.dp))
                Text("Интервал: ${"%.1f".format(interval / 1000f)} сек.")
                androidx.compose.material3.Slider(
                    value = interval.toFloat(),
                    onValueChange = { interval = ((it / 100).toInt() * 100).coerceIn(300, 3_000) },
                    valueRange = 300f..3_000f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { discardConfirm = true }) { Text("Отмена") }
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
private fun MessageSelectorDialog(cell: CellWithMessages, onDismiss: () -> Unit, onCopy: (List<Long>) -> Unit) {
    var selected by remember(cell.cell.id) { mutableStateOf(listOf<Long>()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 8.dp) {
            Column(Modifier.padding(18.dp)) {
                Text(cell.cell.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Выберите сообщения в нужном порядке", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cell.orderedMessages, key = { it.id }) { message ->
                        val order = selected.indexOf(message.id)
                        Card(
                            onClick = { selected = if (order >= 0) selected - message.id else selected + message.id },
                            modifier = Modifier.height(92.dp),
                            colors = CardDefaults.cardColors(containerColor = if (order >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(Modifier.fillMaxSize().padding(8.dp)) {
                                Text(message.text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (order >= 0) Text("${order + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd))
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
private fun DisplaySettingsDialog(columns: Int, rows: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var c by remember { mutableIntStateOf(columns) }
    var r by remember { mutableIntStateOf(rows) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вид главного экрана") },
        text = {
            Column {
                Text("Столбцов: $c")
                androidx.compose.material3.Slider(c.toFloat(), { c = it.toInt() }, valueRange = 2f..5f, steps = 2)
                Text("Видимых строк: $r")
                androidx.compose.material3.Slider(r.toFloat(), { r = it.toInt() }, valueRange = 3f..8f, steps = 4)
                Spacer(Modifier.height(8.dp))
                Text("На экране: ${c * r} ячеек. Остальные доступны прокруткой.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(c, r) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
