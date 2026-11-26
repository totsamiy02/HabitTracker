package com.yaroslav.habittracker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF784CB3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DBF5),
    onPrimaryContainer = Color(0xFF563279),
    secondaryContainer = Color(0xFFE8DBF5),
    onSecondaryContainer = Color(0xFF563279),
    background = Color(0xFFF2EDF9),
    onBackground = Color(0xFF302345),
    surface = Color(0xFFFDFAFF),
    onSurface = Color(0xFF302345),
    surfaceVariant = Color(0xFFF1E8F8),
    onSurfaceVariant = Color(0xFF655575),
    outline = Color(0xFF827191),
    outlineVariant = Color(0xFFDDCFEA),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFD5B0FF),
    onPrimary = Color(0xFF392050),
    primaryContainer = Color(0xFF4C3267),
    onPrimaryContainer = Color(0xFFEBDCFF),
    secondaryContainer = Color(0xFF4C3267),
    onSecondaryContainer = Color(0xFFEBDCFF),
    background = Color(0xFF191320),
    onBackground = Color(0xFFF0E6FA),
    surface = Color(0xFF241B2D),
    onSurface = Color(0xFFF0E6FA),
    surfaceVariant = Color(0xFF392B47),
    onSurfaceVariant = Color(0xFFCDBDDC),
    outline = Color(0xFFAA96BA),
    outlineVariant = Color(0xFF554363),
)
