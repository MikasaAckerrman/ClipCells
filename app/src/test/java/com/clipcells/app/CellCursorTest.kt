package com.clipcells.app

import com.clipcells.app.overlay.CellCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CellCursorTest {

    @Test
    fun `advance walks every message in order then wraps`() {
        val cursor = CellCursor()
        assertEquals(0, cursor.advance(1L, 3))
        assertEquals(1, cursor.advance(1L, 3))
        assertEquals(2, cursor.advance(1L, 3))
        assertEquals(0, cursor.advance(1L, 3))
    }

    @Test
    fun `peek does not advance`() {
        val cursor = CellCursor()
        assertEquals(0, cursor.peek(1L, 3))
        assertEquals(0, cursor.peek(1L, 3))
        assertEquals(0, cursor.advance(1L, 3))
        assertEquals(1, cursor.peek(1L, 3))
        assertEquals(1, cursor.peek(1L, 3))
        assertEquals(1, cursor.advance(1L, 3))
    }

    @Test
    fun `cells rotate independently`() {
        val cursor = CellCursor()
        cursor.advance(1L, 2) // cell 1 -> next 1
        assertEquals(0, cursor.advance(2L, 2)) // cell 2 untouched
        assertEquals(1, cursor.advance(1L, 2)) // cell 1 continues its own cycle
    }

    @Test
    fun `clamps when a cell shrinks after editing`() {
        val cursor = CellCursor()
        cursor.advance(1L, 5)
        cursor.advance(1L, 5)
        cursor.advance(1L, 5) // next would be 3
        // The cell is edited down to 2 messages while the overlay is up:
        // 3 clamps to 1, and the rotation continues over the new size.
        assertEquals(1, cursor.advance(1L, 2))
        assertEquals(0, cursor.advance(1L, 2))
        assertEquals(1, cursor.peek(1L, 2))
    }

    @Test
    fun `reset restarts from the first message`() {
        val cursor = CellCursor()
        cursor.advance(1L, 4)
        cursor.advance(1L, 4)
        cursor.reset(1L)
        assertEquals(0, cursor.peek(1L, 4))
        assertEquals(0, cursor.advance(1L, 4))
    }

    @Test
    fun `zero size is safe to peek`() {
        val cursor = CellCursor()
        assertEquals(0, cursor.peek(1L, 0))
    }

    @Test
    fun `advance requires messages`() {
        val cursor = CellCursor()
        val failed = try {
            cursor.advance(1L, 0)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }
}
