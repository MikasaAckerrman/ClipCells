package com.clipcells.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.CopyQueueEntity
import com.clipcells.app.overlay.OverlayService
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

    private companion object {
        const val EXTRA_TEST_COPY = "test_copy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeStartOverlayFromIntent(intent)
        setContent { ClipCellsTheme { ClipCellsApp() } }
    }

    /**
     * Legal overlay entry point: Android 15 denies foreground-service starts
     * from outside the app (getFgsAllowStart=DENIED for cross-app/shell
     * callers), so the service must be started by the app itself while its
     * activity is visible. External launchers (the donut app, automation)
     * open this activity with EXTRA_START_OVERLAY instead.
     */
    private fun maybeStartOverlayFromIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(OverlayService.EXTRA_START_OVERLAY, false)) return
        if (!Settings.canDrawOverlays(this)) return
        OverlayService.start(
            this,
            showPanel = intent.getBooleanExtra(OverlayService.EXTRA_SHOW_PANEL, true),
        )
        if (intent.getBooleanExtra(EXTRA_TEST_COPY, false)) OverlayService.testCopy(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipCellsApp(vm: MainViewModel = viewModel()) {
    val cellsOrNull by vm.cells.collectAsStateWithLifecycle()
    val cells = cellsOrNull
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
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredCells = remember(cells, searchQuery) {
        if (searchQuery.isBlank()) cells ?: emptyList()
        else cells?.filter { it.cell.name.contains(searchQuery, ignoreCase = true) } ?: emptyList()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // --- Floating overlay toggle (bubble + cells panel above every app) ---
    var overlayOn by remember { mutableStateOf(OverlayService.isRunning) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayOn = OverlayService.isRunning
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { OverlayService.start(context) }

    fun requestNotificationsAndStartOverlay() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            OverlayService.start(context)
        }
    }

    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) requestNotificationsAndStartOverlay()
        else scope.launch { snackbar.showSnackbar("Разрешение «Поверх других приложений» не выдано") }
    }
    fun toggleOverlay() {
        if (overlayOn) OverlayService.stop(context)
        else if (Settings.canDrawOverlays(context)) requestNotificationsAndStartOverlay()
        else overlayPermLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }

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
                title = {
                    if (showSearch && mode == HomeMode.NORMAL) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Поиск ячеек…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        )
                    } else {
                        Text(
                            if (mode == HomeMode.NORMAL) "ClipCells (${cells?.size ?: 0})" else if (mode == HomeMode.EDIT) "Редактирование" else "Выбрано: ${selectedCells.size}",
                            modifier = Modifier.clickable { mode = HomeMode.NORMAL; selectedCells = emptySet() },
                        )
                    }
                },
                actions = {
                    if (showSearch && mode == HomeMode.NORMAL) {
                        IconButton(onClick = { showSearch = false; searchQuery = "" }) { Icon(Icons.Default.Close, "Закрыть поиск") }
                    } else if (mode == HomeMode.NORMAL) {
                        IconButton(onClick = { toggleOverlay() }) {
                            Icon(
                                painterResource(R.drawable.ic_float_window),
                                contentDescription = "Плавающее окно",
                                tint = if (overlayOn) Color(0xFF8AB4F8) else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, "Поиск") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") }
                        IconButton(onClick = { mode = HomeMode.EDIT }) { Icon(Icons.Default.Edit, "Редактировать") }
                        IconButton(onClick = { mode = HomeMode.DELETE; selectedCells = emptySet() }, enabled = !cells.isNullOrEmpty()) {
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
        when {
            cells == null -> Box(Modifier.fillMaxSize().padding(padding))
            cells.isEmpty() -> EmptyState(Modifier.fillMaxSize().padding(padding), onCreate = { editorCell = null; showEditor = true })
            filteredCells.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                val cardHeight = ((maxHeight - 12.dp * (visibleRows - 1)) / visibleRows).coerceAtLeast(64.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredCells, key = { it.cell.id }, contentType = { "cell" }) { cell ->
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
            onDismiss = { showEditor = false; mode = HomeMode.NORMAL },
            onSave = { draft ->
                vm.save(draft) { result ->
                    result.onSuccess { showEditor = false; mode = HomeMode.NORMAL }.onFailure { error = it.message }
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
