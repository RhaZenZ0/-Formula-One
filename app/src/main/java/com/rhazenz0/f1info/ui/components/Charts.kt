package com.rhazenz0.f1info.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhazenz0.f1info.data.timing.SegmentStatus
import com.rhazenz0.f1info.data.timing.StintInfo
import com.rhazenz0.f1info.ui.theme.F1Colors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ChartSeries(
    val label: String,
    val xs: FloatArray,
    val ys: FloatArray,
    val color: Color,
    val dashed: Boolean = false,
    val stepped: Boolean = false,
)

/** Picks round tick values between [min] and [max]. */
internal fun niceTicks(min: Float, max: Float, target: Int = 4): List<Float> {
    if (!min.isFinite() || !max.isFinite() || max <= min) return listOf(min)
    val rough = (max - min) / target
    val mag = 10.0.pow(floor(log10(rough.toDouble()))).toFloat()
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= rough }
    val start = ceil(min / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= max + step * 1e-3f }.toList()
}

private fun fmt(v: Float): String = if (abs(v) >= 100 || v == floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    yMin: Float? = null,
    yMax: Float? = null,
    invertY: Boolean = false,
    yLabel: (Float) -> String = ::fmt,
    xLabel: (Float) -> String = ::fmt,
    title: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = F1Colors.TextSecondary, fontSize = 10.sp)
    val gridColor = F1Colors.Outline
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val xsAll = series.flatMap { s -> s.xs.asList() }.filter { it.isFinite() }
            val ysAll = series.flatMap { s -> s.ys.asList() }.filter { it.isFinite() }
            if (xsAll.isEmpty() || ysAll.isEmpty()) return@Canvas
            val x0 = xsAll.min()
            val x1 = xsAll.max().let { if (it <= x0) x0 + 1 else it }
            var y0 = yMin ?: ysAll.min()
            var y1 = yMax ?: ysAll.max()
            if (y1 <= y0) { y0 -= 1; y1 += 1 }
            if (yMin == null || yMax == null) {
                val pad = (y1 - y0) * 0.06f
                if (yMin == null) y0 -= pad
                if (yMax == null) y1 += pad
            }

            val left = 40.dp.toPx()
            val bottom = 18.dp.toPx()
            val top = if (title != null) 16.dp.toPx() else 4.dp.toPx()
            val w = size.width - left - 4.dp.toPx()
            val h = size.height - bottom - top
            fun px(x: Float) = left + (x - x0) / (x1 - x0) * w
            fun py(y: Float): Float {
                val t = (y - y0) / (y1 - y0)
                return if (invertY) top + t * h else top + (1 - t) * h
            }

            if (title != null) {
                drawText(measurer, title, Offset(left, 0f), labelStyle.copy(color = F1Colors.TextPrimary, fontSize = 11.sp))
            }
            for (t in niceTicks(y0, y1)) {
                val y = py(t)
                drawLine(gridColor, Offset(left, y), Offset(left + w, y), strokeWidth = 1f)
                val layout = measurer.measure(yLabel(t), labelStyle)
                drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), y - layout.size.height / 2))
            }
            for (t in niceTicks(x0, x1, 5)) {
                val x = px(t)
                val layout = measurer.measure(xLabel(t), labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2, top + h + 2.dp.toPx()))
            }

            val edge = 2.dp.toPx()
            clipRect(left, top - edge, left + w + edge, top + h + edge) { drawSeries(series, ::px, ::py) }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    series: List<ChartSeries>,
    px: (Float) -> Float,
    py: (Float) -> Float,
) {
            for (s in series) {
                val n = min(s.xs.size, s.ys.size)
                if (n == 0) continue
                val path = Path()
                var started = false
                var lastY = 0f
                for (i in 0 until n) {
                    val xv = s.xs[i]; val yv = s.ys[i]
                    if (!xv.isFinite() || !yv.isFinite()) { started = false; continue }
                    val x = px(xv); val y = py(yv)
                    if (!started) { path.moveTo(x, y); started = true }
                    else {
                        if (s.stepped) path.lineTo(x, lastY)
                        path.lineTo(x, y)
                    }
                    lastY = y
                }
                drawPath(
                    path, s.color,
                    style = Stroke(
                        width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                    ),
                )
                if (n == 1) drawCircle(s.color, 3.dp.toPx(), Offset(px(s.xs[0]), py(s.ys[0])))
            }
}

@Composable
fun ChartLegend(items: List<Pair<String, Color>>) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, color) ->
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** Speed colour scale from slow (blue) to fast (red). */
fun speedColor(speed: Float, min: Float, max: Float): Color {
    val t = if (max > min) ((speed - min) / (max - min)).coerceIn(0f, 1f) else 0.5f
    val stops = listOf(Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFFACC15), Color(0xFFEF4444))
    val scaled = t * (stops.size - 1)
    val i = floor(scaled).toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], scaled - i)
}

class MapDot(val x: Float, val y: Float, val color: Color, val label: String)

/** Draws a circuit from car X/Y positions, optionally coloured by speed, with car dots. */
@Composable
fun TrackMap(
    xs: FloatArray,
    ys: FloatArray,
    modifier: Modifier = Modifier,
    speeds: FloatArray? = null,
    dots: List<MapDot> = emptyList(),
    height: Dp = 260.dp,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val allX = xs.asList() + dots.map { it.x }
        val allY = ys.asList() + dots.map { it.y }
        if (allX.isEmpty()) return@Canvas
        val minX = allX.min(); val maxX = allX.max()
        val minY = allY.min(); val maxY = allY.max()
        val pad = 14.dp.toPx()
        val scale = min((size.width - 2 * pad) / max(1f, maxX - minX), (size.height - 2 * pad) / max(1f, maxY - minY))
        val offX = (size.width - (maxX - minX) * scale) / 2
        val offY = (size.height - (maxY - minY) * scale) / 2
        fun p(x: Float, y: Float) = Offset(offX + (x - minX) * scale, size.height - (offY + (y - minY) * scale))

        val n = min(xs.size, ys.size)
        if (n > 1) {
            // Track outline underneath.
            val outline = Path().apply {
                moveTo(p(xs[0], ys[0]).x, p(xs[0], ys[0]).y)
                for (i in 1 until n) lineTo(p(xs[i], ys[i]).x, p(xs[i], ys[i]).y)
            }
            drawPath(outline, Color(0xFF3A3A44), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (speeds != null && speeds.size >= n) {
                val sMin = speeds.minOrNull() ?: 0f
                val sMax = speeds.maxOrNull() ?: 1f
                for (i in 1 until n) {
                    drawLine(
                        speedColor((speeds[i] + speeds[i - 1]) / 2, sMin, sMax),
                        p(xs[i - 1], ys[i - 1]), p(xs[i], ys[i]),
                        strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            } else {
                drawPath(outline, Color(0xFF6B6B78), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val start = p(xs[0], ys[0])
            drawCircle(Color.White, 4.dp.toPx(), start)
        }
        val labelStyle = TextStyle(color = Color.White, fontSize = 9.sp)
        dots.forEach { d ->
            val c = p(d.x, d.y)
            drawCircle(Color.Black, 6.dp.toPx(), c)
            drawCircle(d.color, 5.dp.toPx(), c)
            drawText(measurer, d.label, Offset(c.x + 6.dp.toPx(), c.y - 14.dp.toPx()), labelStyle)
        }
    }
}

/** Horizontal tyre strategy bars, one row per driver. */
@Composable
fun TyreStrategyChart(
    rows: List<Pair<String, List<StintInfo>>>,
    totalLaps: Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val rowHeight = 18.dp
    Canvas(modifier.fillMaxWidth().height(rowHeight * rows.size + 20.dp)) {
        val labelW = 40.dp.toPx()
        val w = size.width - labelW - 4.dp.toPx()
        val rh = rowHeight.toPx()
        val laps = max(1, totalLaps)
        val labelStyle = TextStyle(color = F1Colors.TextPrimary, fontSize = 11.sp)
        rows.forEachIndexed { index, (label, stints) ->
            val y = index * rh
            drawText(measurer, label, Offset(0f, y + 1.dp.toPx()), labelStyle)
            stints.forEach { s ->
                val x0 = labelW + (s.lapStart - 1).toFloat() / laps * w
                val x1 = labelW + s.lapEnd.toFloat() / laps * w
                val color = F1Colors.compound(s.compound)
                drawRoundRect(
                    color = if (s.isNew) color else color.copy(alpha = 0.55f),
                    topLeft = Offset(x0 + 1, y + 3.dp.toPx()),
                    size = Size(max(2f, x1 - x0 - 2), rh - 6.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                )
            }
        }
        val axisY = rows.size * rh + 4.dp.toPx()
        for (t in niceTicks(0f, laps.toFloat(), 6)) {
            val x = labelW + t / laps * w
            drawText(measurer, t.toInt().toString(), Offset(x - 6.dp.toPx(), axisY), TextStyle(color = F1Colors.TextSecondary, fontSize = 10.sp))
        }
    }
}

fun segmentColor(status: SegmentStatus): Color = when (status) {
    SegmentStatus.PURPLE -> F1Colors.Purple
    SegmentStatus.GREEN -> F1Colors.Green
    SegmentStatus.YELLOW -> F1Colors.Yellow
    SegmentStatus.PIT -> F1Colors.PitBlue
    SegmentStatus.NONE, SegmentStatus.OTHER -> F1Colors.SegmentNone
}

/** Mini-sector bars for one lap: three groups, one per sector. */
@Composable
fun MiniSectors(segments: List<List<SegmentStatus>>, modifier: Modifier = Modifier) {
    Canvas(modifier.height(6.dp)) {
        val groups = segments.filter { it.isNotEmpty() }
        if (groups.isEmpty()) return@Canvas
        val total = groups.sumOf { it.size }
        val gap = 1.5.dp.toPx()
        val groupGap = 4.dp.toPx()
        val unit = (size.width - groupGap * (groups.size - 1) - gap * total) / total
        var x = 0f
        groups.forEach { group ->
            group.forEach { s ->
                drawRect(segmentColor(s), Offset(x, 0f), Size(max(1f, unit), size.height))
                x += unit + gap
            }
            x += groupGap
        }
    }
}
