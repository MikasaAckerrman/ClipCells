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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CellRepository(ClipCellsDatabase.get(application))
    private var lastDeleted: List<CellWithMessages> = emptyList()
    private var pendingCount: Int? = null

    val cells = repository.observeCells().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
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
        }
    }

    fun consumePendingCount(): Int? = pendingCount.also { pendingCount = null }

    fun save(draft: CellDraft, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { repository.save(draft) }) }
    }

    fun copyWhole(cellId: Long, onError: (Throwable) -> Unit) {
        startQueue(onError) { repository.queueWholeCell(cellId) }
    }

    fun copySelected(cellId: Long, selectedIds: List<Long>, onError: (Throwable) -> Unit) {
        if (selectedIds.isEmpty()) return
        startQueue(onError) { repository.queueSelection(cellId, selectedIds) }
    }

    private fun startQueue(onError: (Throwable) -> Unit, prepare: suspend () -> Int) {
        viewModelScope.launch {
            runCatching {
                pendingCount = prepare()
                val context = getApplication<Application>()
                context.startService(CopyService.startIntent(context))
            }.onFailure(onError)
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
