package com.clipcells.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipcells.app.copy.CopyService
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellRepository
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import com.clipcells.app.data.CopyQueueEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CellRepository(ClipCellsDatabase.get(application))
    private var lastDeleted: List<CellWithMessages> = emptyList()
    private var pendingCount: Int? = null

    val cells = repository.observeCells().map<List<CellWithMessages>, List<CellWithMessages>?> { it }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    val queue: kotlinx.coroutines.flow.StateFlow<CopyQueueEntity?> = repository.observeQueue().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    init {
        viewModelScope.launch {
            val unfinished = repository.unfinishedQueueSize() ?: return@launch
            pendingCount = unfinished
            runCatching { application.startService(CopyService.startIntent(application)) }
                .onFailure { pendingCount = null }
        }
    }

    fun consumePendingCount(): Int? = pendingCount.also { pendingCount = null }

    fun save(draft: CellDraft, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { repository.save(draft) }) }
    }

    fun copyWhole(cellId: Long, onError: (Throwable) -> Unit) {
        advanceOrStart(cellId, onError) { repository.queueWholeCell(cellId) }
    }

    fun copySelected(cellId: Long, selectedIds: List<Long>, onError: (Throwable) -> Unit) {
        if (selectedIds.isEmpty()) return
        advanceOrStart(cellId, onError) { repository.queueSelection(cellId, selectedIds) }
    }

    /**
     * Tap on a cell while its own queue is alive: ADVANCE — the next message
     * lands in the clipboard instantly (a ~ms binder write), the queue keeps
     * its position and its pacing. A tap on a DIFFERENT cell (or when no
     * queue is running) starts a fresh queue as before. This replaces the
     * restart-from-zero race: the pace belongs to the user's taps, the
     * interval is only the idle auto-advance.
     */
    private fun advanceOrStart(cellId: Long, onError: (Throwable) -> Unit, prepare: suspend () -> Int) {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val active = queue.value
                if (active != null && active.cellId == cellId) {
                    context.startService(CopyService.advanceIntent(context))
                } else {
                    pendingCount = prepare()
                    context.startService(CopyService.startIntent(context))
                }
            }.onFailure {
                pendingCount = null
                onError(it)
            }
        }
    }

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
