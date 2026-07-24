package com.raznoe.katana.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** NUX-style circular arc knob: orange arc on a grey track, value in the centre.
 *  Change the value by dragging vertically. */
@Composable
fun Knob(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val range = (max - min).coerceAtLeast(1)
    var acc by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { acc = value.toFloat() }

    val frac = ((value - min).toFloat() / range).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(104.dp)
                .pointerInput(min, max) {
                    detectVerticalDragGestures { _, dy ->
                        // ~a full sweep over 320 px of travel
                        acc = (acc - dy * range / 320f).coerceIn(min.toFloat(), max.toFloat())
                        onChange(acc.roundToInt())
                    }
                },
        ) {
            Canvas(Modifier.size(104.dp)) {
                val sw = 10.dp.toPx()
                val inset = sw / 2f
                val arcSize = Size(size.width - sw, size.height - sw)
                val topLeft = Offset(inset, inset)
                val start = 135f
                val full = 270f
                drawArc(
                    color = Nux.KnobTrack, startAngle = start, sweepAngle = full,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accent, startAngle = start, sweepAngle = full * frac,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Nux.TextLo, fontSize = 12.sp)
                Text("$value", color = Nux.TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Off / On pill pair, like the NUX effect enable control. */
@Composable
fun OnOffPills(on: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("Off", selected = !on, accent = accent) { onChange(false) }
        Pill("On", selected = on, accent = accent) { onChange(true) }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val bg = if (selected) accent else Color.Transparent
    val fg = if (selected) Color.Black else accent
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.5.dp, accent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, color = fg, fontWeight = FontWeight.SemiBold) }
}

/** Horizontal scrollable chip row for enum selection (amp/effect types). */
@Composable
fun ChipRow(options: List<String>, selectedIndex: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            Pill(label, selected = i == selectedIndex, accent = accent) { onSelect(i) }
        }
    }
}

/** Round preset/channel button in the NUX style. */
@Composable
fun RoundButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) Nux.Pink else Nux.Stroke
    val fg = if (selected) Nux.Pink else Nux.TextHi
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Nux.PanelHi)
            .border(2.dp, border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
}
