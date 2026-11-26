package com.yaroslav.habittracker.habits

import org.junit.Assert.assertEquals
import org.junit.Test

class MosaicLayoutTest {
    @Test
    fun growsAndPreservesPreviousCells() {
        val first = mosaicLayout(16)
        val second = mosaicLayout(17)

        assertEquals(4, mosaicLayout(0).side)
        assertEquals(16L, first.capacity)
        assertEquals(25L, second.capacity)
        for (row in 0 until first.side) {
            for (column in 0 until first.side) {
                assertEquals(first.dayAt(row, column), second.dayAt(row, column))
            }
        }
        assertEquals(17L, second.dayAt(row = 0, column = 4))
    }

    @Test
    fun eachCellHasADistinctDayAcrossMultipleStages() {
        for (days in listOf(0, 16, 17, 25, 26, 36, 37, 100, 101)) {
            val layout = mosaicLayout(days)
            val orderedDays = (0 until layout.side).flatMap { row ->
                (0 until layout.side).map { column -> layout.dayAt(row, column) }
            }.sorted()

            assertEquals((1L..layout.capacity).toList(), orderedDays)
        }
    }
}
