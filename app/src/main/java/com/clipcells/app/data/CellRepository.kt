package com.clipcells.app.data

import androidx.room.withTransaction
import com.clipcells.core.Cell
import com.clipcells.core.CellMessage
import com.clipcells.core.CopyPlanFactory
import kotlinx.coroutines.flow.Flow

data class CellDraft(
    val id: Long? = null,
    val name: String,
    val messages: List<String>,
    val colorArgb: Long = 0xFF6750A4,
    val icon: String? = null,
    val intervalMillis: Long? = null,
)

class CellRepository(private val db: ClipCellsDatabase) {
    private val cells = db.cellDao()
    private val queues = db.queueDao()
    private val plans = CopyPlanFactory()

    fun observeCells(): Flow<List<CellWithMessages>> = cells.observeAll()

    suspend fun save(draft: CellDraft): Long {
        val name = draft.name.trim()
        val messages = draft.messages.map(String::trim).filter(String::isNotEmpty)
        require(name.isNotEmpty()) { "Название не может быть пустым" }
        require(messages.isNotEmpty()) { "Добавьте хотя бы одно сообщение" }

        return db.withTransaction {
            val id = if (draft.id == null) {
                cells.insertCell(
                    CellEntity(
                        name = name,
                        position = cells.nextPosition(),
                        colorArgb = draft.colorArgb,
                        icon = draft.icon,
                        intervalMillis = draft.intervalMillis,
                    ),
                )
            } else {
                requireNotNull(cells.get(draft.id)) { "Ячейка уже удалена" }
                cells.updateCell(draft.id, name, draft.colorArgb, draft.icon, draft.intervalMillis)
                cells.deleteMessages(draft.id)
                draft.id
            }
            cells.insertMessages(messages.mapIndexed { index, text ->
                MessageEntity(cellId = id, text = text, position = index)
            })
            id
        }
    }

    suspend fun delete(ids: Set<Long>): List<CellWithMessages> = db.withTransaction {
        val snapshots = ids.mapNotNull { cells.get(it) }
        cells.deleteCells(ids)
        snapshots
    }

    suspend fun restore(snapshots: List<CellWithMessages>) = db.withTransaction {
        val maxExisting = cells.nextPosition()
        snapshots.forEachIndexed { offset, snapshot ->
            val restored = snapshot.cell.copy(position = maxExisting + offset)
            cells.insertCell(restored)
            cells.insertMessages(snapshot.messages)
        }
    }

    suspend fun queueWholeCell(cellId: Long): Int = queue(cellId, null)

    suspend fun queueSelection(cellId: Long, selectedIds: List<Long>): Int = queue(cellId, selectedIds)

    suspend fun unfinishedQueueSize(): Int? = db.withTransaction {
        val q = queues.getQueue() ?: return@withTransaction null
        val items = queues.getItems()
        if (items.isNotEmpty() && q.nextIndex < items.size) items.size else null
    }

    fun observeQueue(): Flow<CopyQueueEntity?> = queues.observeQueue()

    private suspend fun queue(cellId: Long, selectedIds: List<Long>?): Int {
        val stored = requireNotNull(cells.get(cellId)) { "Ячейка не найдена" }
        val cell = Cell(
            id = stored.cell.id,
            name = stored.cell.name,
            position = stored.cell.position,
            colorArgb = stored.cell.colorArgb,
            icon = stored.cell.icon,
            intervalMillis = stored.cell.intervalMillis,
            messages = stored.messages.map { CellMessage(it.id, it.text, it.position) },
        )
        val plan = if (selectedIds == null) plans.forWholeCell(cell) else plans.forSelection(cell, selectedIds)
        val size = plan.items.size

        return db.withTransaction {
            val revision = (queues.getQueue()?.revision ?: 0L) + 1L
            queues.deleteQueue()
            queues.putQueue(
                CopyQueueEntity(
                    title = plan.cellName,
                    intervalMillis = plan.intervalMillis,
                    nextIndex = 0,
                    revision = revision,
                ),
            )
            queues.putItems(plan.items.mapIndexed { index, item ->
                CopyQueueItemEntity(position = index, sourceMessageId = item.sourceMessageId, text = item.text)
            })
            size
        }
    }
}
