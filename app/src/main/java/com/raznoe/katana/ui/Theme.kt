package com.raznoe.katana.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** NUX Mighty-Amp-inspired palette: black canvas, orange accent, neon blocks. */
object Nux {
    val Bg = Color(0xFF000000)
    val Panel = Color(0xFF1C1C1E)
    val PanelHi = Color(0xFF2A2A2C)
    val Stroke = Color(0xFF3A3A3C)
    val Orange = Color(0xFFF07A29)
    val OrangeDim = Color(0xFF7A3E15)
    val Pink = Color(0xFFE89BD0)
    val TextHi = Color(0xFFF2F2F2)
    val TextLo = Color(0xFF9AA0A6)
    val KnobTrack = Color(0xFF3A3A3C)

    // Per-block neon accents (chain strip), echoing the NUX look.
    val Gate = Color(0xFFBEE14A)
    val Boost = Color(0xFFF07A29)
    val Amp = Color(0xFFE23B2E)
    val Mod = Color(0xFF5B8CFF)
    val Fx = Color(0xFF00C2C7)
    val Delay = Color(0xFF3ED0B0)
    val Reverb = Color(0xFFB06BE8)
}

private val DarkColors = darkColorScheme(
    primary = Nux.Orange,
    onPrimary = Color.Black,
    secondary = Nux.TextLo,
    background = Nux.Bg,
    onBackground = Nux.TextHi,
    surface = Nux.Panel,
    onSurface = Nux.TextHi,
    surfaceVariant = Nux.PanelHi,
    onSurfaceVariant = Nux.TextHi,
)

@Composable
fun KatanaTheme(content: @Composable () -> Unit) {
    // NUX styling is committed to a dark look regardless of system theme.
    MaterialTheme(colorScheme = DarkColors, content = content)
}
