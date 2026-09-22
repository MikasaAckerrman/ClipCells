package com.clipcells.app.overlay

/**
 * Rotating per-cell message index for the floating overlay: every tap on a
 * cell copies the NEXT message of that cell in order, wrapping around — the
 * overlay's fast version of the main screen's sequential copy queue.
 *
 * Pure JVM (no Android imports) — covered by unit tests, and it outlives the
 * panel window: the owning service keeps one instance, so the rotation state
 * survives panel close/reopen while the overlay runs.
 */
class CellCursor {

    private val indices = mutableMapOf<Long, Int>()

    /**
     * The 0-based index the NEXT tap will copy, clamped to the current
     * message count (a cell edited behind the overlay can shrink).
     * Never advances the cursor.
     */
    fun peek(cellId: Long, size: Int): Int {
        if (size <= 0) return 0
        return (indices[cellId] ?: 0).coerceIn(0, size - 1)
    }

    /**
     * Consumes the current index and rotates. Returns the 0-based index that
     * was just consumed (the message to copy now).
     */
    fun advance(cellId: Long, size: Int): Int {
        require(size > 0) { "cell must have at least one message" }
        val current = peek(cellId, size)
        indices[cellId] = (current + 1) % size
        return current
    }

    /** Restarts the cell from its first message. */
    fun reset(cellId: Long) {
        indices.remove(cellId)
    }
}
