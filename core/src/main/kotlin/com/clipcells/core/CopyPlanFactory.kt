package com.clipcells.core

class CopyPlanFactory(
    private val defaultIntervalMillis: Long = DEFAULT_COPY_INTERVAL_MILLIS,
) {
    init {
        require(defaultIntervalMillis in MIN_COPY_INTERVAL_MILLIS..MAX_COPY_INTERVAL_MILLIS)
    }

    fun forWholeCell(cell: Cell): CopyPlan = create(
        cell = cell,
        selectedMessageIds = null,
    )

    fun forSelection(cell: Cell, selectedMessageIds: List<Long>): CopyPlan = create(
        cell = cell,
        selectedMessageIds = selectedMessageIds,
    )

    private fun create(cell: Cell, selectedMessageIds: List<Long>?): CopyPlan {
        require(cell.name.isNotBlank()) { "Cell name must not be blank" }
        require(cell.messages.isNotEmpty()) { "Cell must contain at least one message" }

        val messagesById = cell.messages.associateBy(CellMessage::id)
        val ordered = if (selectedMessageIds == null) {
            cell.messages.sortedBy(CellMessage::position)
        } else {
            require(selectedMessageIds.distinct().size == selectedMessageIds.size) {
                "Selected message IDs must be unique"
            }
            selectedMessageIds.map { id ->
                messagesById[id] ?: throw IllegalArgumentException("Unknown message ID: $id")
            }
        }
        require(ordered.isNotEmpty()) { "At least one message must be selected" }
        require(ordered.all { it.text.isNotBlank() }) { "Messages must not be blank" }

        val interval = (cell.intervalMillis ?: defaultIntervalMillis)
            .coerceIn(MIN_COPY_INTERVAL_MILLIS, MAX_COPY_INTERVAL_MILLIS)

        return CopyPlan(
            cellId = cell.id,
            cellName = cell.name.trim(),
            intervalMillis = interval,
            items = ordered.map { CopyItem(it.id, it.text) },
        )
    }
}
