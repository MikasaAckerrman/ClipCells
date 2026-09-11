package com.clipcells.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CopyPlanFactoryTest {
    private val cell = Cell(
        id = 7,
        name = " Ответы ",
        position = 0,
        colorArgb = 0xFF6750A4,
        icon = null,
        intervalMillis = null,
        messages = listOf(
            CellMessage(11, "второе", 1),
            CellMessage(10, "первое", 0),
            CellMessage(12, "третье", 2),
        ),
    )

    @Test
    fun `whole cell follows editor order`() {
        val plan = CopyPlanFactory().forWholeCell(cell)

        assertEquals(listOf("первое", "второе", "третье"), plan.items.map { it.text })
        assertEquals(1_000L, plan.intervalMillis)
        assertEquals("Ответы", plan.cellName)
    }

    @Test
    fun `selection follows tap order rather than editor order`() {
        val plan = CopyPlanFactory().forSelection(cell, listOf(12, 10))

        assertEquals(listOf("третье", "первое"), plan.items.map { it.text })
    }

    @Test
    fun `per-cell interval is clamped to supported range`() {
        val plan = CopyPlanFactory().forWholeCell(cell.copy(intervalMillis = 9_000))

        assertEquals(3_000L, plan.intervalMillis)
    }

    @Test
    fun `empty selection does not start copying`() {
        assertFailsWith<IllegalArgumentException> {
            CopyPlanFactory().forSelection(cell, emptyList())
        }
    }

    @Test
    fun `unknown selection is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CopyPlanFactory().forSelection(cell, listOf(99))
        }
    }

    @Test
    fun `blank messages are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CopyPlanFactory().forWholeCell(cell.copy(messages = listOf(CellMessage(1, "  ", 0))))
        }
    }
}
