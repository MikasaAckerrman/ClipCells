package com.clipcells.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.CopyQueueEntity
import com.clipcells.app.ui.CellCard
import com.clipcells.app.ui.CellEditorDialog
import com.clipcells.app.ui.ClipCellsTheme
import com.clipcells.app.ui.DisplaySettingsDialog
import com.clipcells.app.ui.EmptyState
import com.clipcells.app.ui.HomeMode
import com.clipcells.app.ui.MessageSelectorDialog
import com.clipcells.app.ui.copiedText
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClipCellsTheme { ClipCellsApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipCellsApp(vm: MainViewModel = viewModel()) {
    val cellsOrNull by vm.cells.collectAsStateWithLifecycle()
    val cells = cellsOrNull ?: return
    val queueState by vm.queue.collectAsStateWithLifecycle()
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
    var lastQueue by remember { mutableStateOf<CopyQueueEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(queueState) {
        val previous = lastQueue
        lastQueue = queueState
        if (previous != null && queueState == null) {
            val count = vm.consumePendingCount()
            snackbar.showSnackbar(
                message = count?.let(::copiedText) ?: "Копирование завершено",
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
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
                FloatingActionButton(
                    onClick = { editorCell = null; showEditor = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Icon(Icons.Default.Add, "Создать ячейку") }
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
                    items(cells, key = { it.cell.id }, contentType = { "cell" }) { cell ->
                        CellCard(
                            cell = cell,
                            height = cardHeight,
                            mode = mode,
                            selected = cell.cell.id in selectedCells,
                            onTap = {
                                when (mode) {
                                    HomeMode.NORMAL -> vm.copyWhole(cell.cell.id) { error = it.message }
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

    AnimatedVisibility(
        visible = showEditor,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
            fadeIn(tween(240, easing = FastOutSlowInEasing)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200, easing = FastOutSlowInEasing)) +
            fadeOut(tween(200, easing = FastOutSlowInEasing)),
    ) {
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
                if (ids.isNotEmpty()) vm.copySelected(cell.cell.id, ids) { error = it.message }
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
