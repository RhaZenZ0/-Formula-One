package com.rhazenz0.f1info.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhazenz0.f1info.data.timing.TrackOutline
import com.rhazenz0.f1info.ui.theme.F1Colors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A car to draw on the track map, in F1 position coordinates. */
data class TrackCar(
    val number: String,
    val tla: String,
    val color: Color,
    val x: Float,
    val y: Float,
    val position: Int?,
    val dimmed: Boolean = false,
)

/**
 * Draws the circuit with every car as a team-coloured dot showing its race position.
 * Tapping a car reports its number through [onTapCar].
 */
@Composable
fun TrackPositionsMap(
    outline: TrackOutline,
    cars: List<TrackCar>,
    modifier: Modifier = Modifier,
    selected: String? = null,
    trackTint: Color? = null,
    height: Dp = 340.dp,
    onTapCar: (String) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    // Rotate the outline once; cars are rotated per frame.
    val rotated = remember(outline) {
        val xs = FloatArray(outline.xs.size); val ys = FloatArray(outline.ys.size)
        for (i in xs.indices) { val (x, y) = outline.rotate(outline.xs[i], outline.ys[i]); xs[i] = x; ys[i] = y }
        xs to ys
    }
    val corners = remember(outline) { outline.corners.map { c -> c.number to outline.rotate(c.x, c.y) } }
    val dotPositions = remember { HashMap<String, Offset>() }

    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(cars) {
            detectTapGestures { tap ->
                dotPositions.minByOrNull { (it.value - tap).getDistance() }
                    ?.takeIf { (it.value - tap).getDistance() < 28.dp.toPx() }
                    ?.let { onTapCar(it.key) }
            }
        },
    ) {
        val (xs, ys) = rotated
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 22.dp.toPx()
        val scale = min((size.width - 2 * pad) / max(1f, maxX - minX), (size.height - 2 * pad) / max(1f, maxY - minY))
        val offX = (size.width - (maxX - minX) * scale) / 2
        val offY = (size.height - (maxY - minY) * scale) / 2
        fun p(x: Float, y: Float) = Offset(offX + (x - minX) * scale, size.height - (offY + (y - minY) * scale))
        val centre = p((minX + maxX) / 2, (minY + maxY) / 2)

        val path = Path().apply {
            moveTo(p(xs[0], ys[0]).x, p(xs[0], ys[0]).y)
            for (i in 1 until xs.size) lineTo(p(xs[i], ys[i]).x, p(xs[i], ys[i]).y)
            close()
        }
        drawPath(path, Color(0xFF2E2E38), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(
            path, trackTint ?: Color(0xFF6B6B78),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Start/finish line across the track at the first outline point.
        val s0 = p(xs[0], ys[0]); val s1 = p(xs[1], ys[1])
        val dx = s1.x - s0.x; val dy = s1.y - s0.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val nx = -dy / len * 9.dp.toPx(); val ny = dx / len * 9.dp.toPx()
        drawLine(Color.White, Offset(s0.x - nx, s0.y - ny), Offset(s0.x + nx, s0.y + ny), strokeWidth = 3.dp.toPx())

        val cornerStyle = TextStyle(color = F1Colors.TextSecondary, fontSize = 9.sp)
        corners.forEach { (n, xy) ->
            val c = p(xy.first, xy.second)
            val away = c - centre
            val d = away.getDistance().coerceAtLeast(1f)
            val at = c + away / d * 14.dp.toPx()
            val layout = measurer.measure(n.toString(), cornerStyle)
            drawText(layout, topLeft = Offset(at.x - layout.size.width / 2, at.y - layout.size.height / 2))
        }

        dotPositions.clear()
        val radius = 9.dp.toPx()
        // Draw the leader last so it sits on top.
        cars.sortedByDescending { it.position ?: 99 }.forEach { car ->
            val (rx, ry) = outline.rotate(car.x, car.y)
            val c = p(rx, ry)
            dotPositions[car.number] = c
            val alpha = if (car.dimmed) 0.45f else 1f
            if (car.number == selected) drawCircle(Color.White, radius + 3.dp.toPx(), c)
            drawCircle(Color.Black.copy(alpha = alpha), radius + 1.dp.toPx(), c)
            drawCircle(car.color.copy(alpha = alpha), radius, c)
            val label = car.position?.toString() ?: ""
            val textColor = if (car.color.luminance() > 0.5f) Color.Black else Color.White
            val layout = measurer.measure(label, TextStyle(color = textColor.copy(alpha = alpha), fontSize = 9.sp, fontWeight = FontWeight.Black))
            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2, c.y - layout.size.height / 2))
            if (car.number == selected || (car.position ?: 99) <= 3) {
                val name = measurer.measure(car.tla, TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                drawText(name, topLeft = Offset(c.x + radius + 2.dp.toPx(), c.y - radius - 6.dp.toPx()))
            }
        }
    }
}
