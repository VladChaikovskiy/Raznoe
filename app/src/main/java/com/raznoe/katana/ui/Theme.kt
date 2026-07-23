package com.raznoe.katana.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFE23B2E)
private val AccentDark = Color(0xFFB52A20)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF9AA0A6),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161A21),
    surfaceVariant = Color(0xFF1E232C),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = Color(0xFF5F6368),
)

@Composable
fun KatanaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
