package com.clipcells.app.data

import androidx.room.withTransaction
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

    /**
     * Все сообщения ячейки одним куском (v0.13: тап = мгновенная копия всей
     * ячейки; очередей больше не существует). Возвращает текст и количество
     * сообщений для фидбека.
     */
    suspend fun cellContent(cellId: Long): Pair<String, Int> {
        val stored = requireNotNull(cells.get(cellId)) { "Ячейка не найдена" }
        val texts = stored.messages.sortedBy { it.position }.map { it.text.trim() }.filter { it.isNotEmpty() }
        require(texts.isNotEmpty()) { "Пустая ячейка" }
        return texts.joinToString("\n") to texts.size
    }

    /** Выбранные сообщения ячейки одним куском, в порядке тапов юзера. */
    suspend fun selectedContent(cellId: Long, selectedIds: List<Long>): Pair<String, Int> {
        val stored = requireNotNull(cells.get(cellId)) { "Ячейка не найдена" }
        val byId = stored.messages.associateBy { it.id }
        val texts = selectedIds.mapNotNull { byId[it]?.text?.trim() }.filter { it.isNotEmpty() }
        require(texts.isNotEmpty()) { "Ничего не выбрано" }
        return texts.joinToString("\n") to texts.size
    }
}
