package com.clipcells.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellRepository
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CellRepository(ClipCellsDatabase.get(application))
    private var lastDeleted: List<CellWithMessages> = emptyList()

    val cells = repository.observeCells().map<List<CellWithMessages>, List<CellWithMessages>?> { it }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun save(draft: CellDraft, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { repository.save(draft) }) }
    }

    /**
     * v0.13: тап = ВСЯ ячейка одним куском в буфер, мгновенно. Никаких очередей
     * и счётчиков. Прямая запись из открытого (сфокусированного) приложения —
     * легальный путь на Android 10+.
     */
    fun copyWhole(cellId: Long, onCopied: (Int) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val (text, count) = repository.cellContent(cellId)
                clipboard().setPrimaryClip(ClipData.newPlainText("ClipCells", text))
                count
            }.onSuccess(onCopied).onFailure(onError)
        }
    }

    /** Выбранные сообщения — одним куском, в порядке выбора. */
    fun copySelected(cellId: Long, selectedIds: List<Long>, onCopied: (Int) -> Unit, onError: (Throwable) -> Unit) {
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val (text, count) = repository.selectedContent(cellId, selectedIds)
                clipboard().setPrimaryClip(ClipData.newPlainText("ClipCells", text))
                count
            }.onSuccess(onCopied).onFailure(onError)
        }
    }

    private fun clipboard(): ClipboardManager =
        getApplication<Application>().getSystemService(ClipboardManager::class.java)

    fun delete(ids: Set<Long>, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(ids) }
                .onSuccess { lastDeleted = it; onDone() }
                .onFailure(onError)
        }
    }

    fun undoDelete(onError: (Throwable) -> Unit) {
        val snapshot = lastDeleted
        if (snapshot.isEmpty()) return
        lastDeleted = emptyList()
        viewModelScope.launch { runCatching { repository.restore(snapshot) }.onFailure(onError) }
    }
}
