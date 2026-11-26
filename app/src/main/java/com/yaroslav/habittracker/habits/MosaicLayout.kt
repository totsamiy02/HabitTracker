package com.yaroslav.habittracker.habits

import kotlin.math.max
import kotlin.math.sqrt

internal const val INITIAL_MOSAIC_SIDE = 4

internal data class MosaicLayout(val side: Int) {
    val capacity: Long get() = side.toLong() * side

    /** Number of the day that fills this cell, starting at one. */
    fun dayAt(row: Int, column: Int): Long {
        val edge = max(row, column)
        if (edge < INITIAL_MOSAIC_SIDE) return 1L + row * INITIAL_MOSAIC_SIDE + column

        val previousStage = edge.toLong() * edge
        return if (column == edge && row < edge) {
            previousStage + row + 1
        } else {
            previousStage + edge + column + 1
        }
    }
}

internal fun mosaicLayout(completedDays: Int): MosaicLayout {
    val side = sqrt((completedDays - 1L).coerceAtLeast(0).toDouble()).toInt() + 1
    return MosaicLayout(max(INITIAL_MOSAIC_SIDE, side))
}
