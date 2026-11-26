package com.yaroslav.habittracker.habits

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yaroslav.habittracker.R

@Composable
internal fun Mosaic(
    completedDays: Int,
    modifier: Modifier = Modifier,
    highlightNext: Boolean = false,
    emphasizeLast: Boolean = false,
    onMarkToday: (() -> Unit)? = null,
) {
    val layout = mosaicLayout(completedDays)
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val pitch = maxWidth / layout.side
        val next = completedDays.toLong() + 1

        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / layout.side
            val gap = step * 0.16f
            val tile = step - gap
            val corner = CornerRadius(tile * 0.2f)
            val lineWidth = minOf(1.dp.toPx(), tile * 0.12f)

            for (row in 0 until layout.side) {
                for (column in 0 until layout.side) {
                    val day = layout.dayAt(row, column)
                    val filled = day <= completedDays
                    val current = highlightNext && day == next
                    val origin = Offset(column * step + gap / 2, row * step + gap / 2)
                    val tileSize = Size(tile, tile)
                    val fill = when {
                        filled -> colors.primary
                        current -> colors.primaryContainer
                        else -> colors.surfaceVariant
                    }

                    drawRoundRect(fill, origin, tileSize, corner)
                    if (!filled) {
                        drawRoundRect(
                            color = if (current) colors.primary else colors.outlineVariant,
                            topLeft = origin,
                            size = tileSize,
                            cornerRadius = corner,
                            style = Stroke(lineWidth),
                        )
                    }
                    val center = origin + Offset(tile / 2, tile / 2)
                    val arm = tile * 0.15f
                    val stroke = minOf(2.dp.toPx(), tile * 0.1f)
                    if (current) {
                        drawLine(
                            colors.onPrimaryContainer,
                            center - Offset(arm, 0f),
                            center + Offset(arm, 0f),
                            stroke,
                            StrokeCap.Round,
                        )
                        drawLine(
                            colors.onPrimaryContainer,
                            center - Offset(0f, arm),
                            center + Offset(0f, arm),
                            stroke,
                            StrokeCap.Round,
                        )
                    } else if (emphasizeLast && day == completedDays.toLong()) {
                        val bend = center + Offset(-arm * 0.25f, arm * 0.66f)
                        drawLine(
                            colors.onPrimary,
                            center - Offset(arm, 0f),
                            bend,
                            stroke,
                            StrokeCap.Round,
                        )
                        drawLine(
                            colors.onPrimary,
                            bend,
                            center + Offset(arm, -arm * 0.85f),
                            stroke,
                            StrokeCap.Round,
                        )
                    }
                }
            }
        }

        if (highlightNext && onMarkToday != null) {
            val label = stringResource(R.string.habit_mark_today)
            for (row in 0 until layout.side) {
                for (column in 0 until layout.side) {
                    if (layout.dayAt(row, column) == next) {
                        Box(
                            Modifier
                                .offset(x = pitch * column, y = pitch * row)
                                .size(pitch)
                                .semantics { contentDescription = label }
                                .clickable(role = Role.Button, onClick = onMarkToday)
                        )
                    }
                }
            }
        }
    }
}
