package com.clipcells.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipcells.app.copy.CopyService
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellRepository
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CellRepository(ClipCellsDatabase.get(application))
    private var lastDeleted: List<CellWithMessages> = emptyList()

    val cells = repository.observeCells().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

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

    private fun startQueue(onError: (Throwable) -> Unit, prepare: suspend () -> Long) {
        viewModelScope.launch {
            runCatching {
                prepare()
                val context = getApplication<Application>()
                ContextCompat.startForegroundService(context, CopyService.startIntent(context))
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
