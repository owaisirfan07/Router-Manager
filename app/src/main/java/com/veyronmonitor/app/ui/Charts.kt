package com.veyronmonitor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.data.EnergyStore
import kotlin.math.ceil
import kotlin.math.max

/** Axis top = 4 x a "nice" tick (e.g. 3000 W -> ticks of 800 -> top 3200). */
private fun niceTop(v: Float): Float {
    if (v <= 0f) return 4f
    val raw = v / 4f
    var mag = 1f
    while (mag * 10 <= raw) mag *= 10
    while (mag > raw) mag /= 10
    val steps = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)
    for (s in steps) if (s * mag >= raw) return 4 * s * mag
    return 40 * mag
}

private fun DrawScope.label(text: String, x: Float, y: Float, color: Color, sizePx: Float, align: android.graphics.Paint.Align) {
    val p = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizePx
        textAlign = align
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, p)
}

/**
 * A day's power curve: solar as a filled area, grid and house load as
 * lines. X axis is 00:00-24:00. Gaps longer than the meter's max gap are
 * left empty (no line drawn across missing data).
 */
@Composable
fun PowerCurveChart(
    samples: List<EnergyStore.Sample>,
    axisColor: Color,
    gridLineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(210.dp)) {
        val leftPad = 44.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 8.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - topPad
        val maxW = samples.maxOfOrNull { max(it.solarW, max(it.gridW, it.loadW)) } ?: 0f
        val top = niceTop(max(maxW, 100f))
        val textPx = 10.dp.toPx()

        // horizontal grid + y labels
        for (i in 0..4) {
            val y = topPad + h - h * i / 4f
            drawLine(gridLineColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f,
                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val v = top * i / 4f
            val txt = if (v >= 1000) ("%.1f".format(v / 1000f).removeSuffix(".0") + "k") else "${v.toInt()}"
            label(txt, leftPad - 6.dp.toPx(), y + textPx / 3, axisColor, textPx, android.graphics.Paint.Align.RIGHT)
        }
        // x labels
        for (hr in 0..24 step 6) {
            val x = leftPad + w * hr / 24f
            label("%02d:00".format(hr % 24).let { if (hr == 24) "24:00" else it }, x, size.height - 4.dp.toPx(), axisColor, textPx,
                when (hr) { 0 -> android.graphics.Paint.Align.LEFT; 24 -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.CENTER })
        }
        if (samples.isEmpty()) return@Canvas

        fun xOf(m: Int) = leftPad + w * m / 1440f
        fun yOf(v: Float) = topPad + h - h * (v / top).coerceIn(0f, 1f)
        val maxGapMin = (EnergyStore.MAX_GAP_MS / 60_000L).toInt()

        // split into continuous segments
        val segments = mutableListOf<MutableList<EnergyStore.Sample>>()
        samples.forEach { s ->
            val last = segments.lastOrNull()?.lastOrNull()
            if (last == null || s.minuteOfDay - last.minuteOfDay > maxGapMin) segments.add(mutableListOf(s))
            else segments.last().add(s)
        }

        segments.forEach { seg ->
            // solar area
            if (seg.size >= 2) {
                val area = Path().apply {
                    moveTo(xOf(seg.first().minuteOfDay), yOf(0f))
                    seg.forEach { lineTo(xOf(it.minuteOfDay), yOf(it.solarW)) }
                    lineTo(xOf(seg.last().minuteOfDay), yOf(0f))
                    close()
                }
                drawPath(area, Brush.verticalGradient(
                    listOf(SolarColor.copy(alpha = 0.55f), SolarColor.copy(alpha = 0.05f)),
                    startY = topPad, endY = topPad + h))
            }
            fun line(sel: (EnergyStore.Sample) -> Float, color: Color, width: Float, dashed: Boolean = false) {
                if (seg.size < 2) {
                    drawCircle(color, 3.dp.toPx(), Offset(xOf(seg[0].minuteOfDay), yOf(sel(seg[0]))))
                    return
                }
                val path = Path().apply {
                    moveTo(xOf(seg[0].minuteOfDay), yOf(sel(seg[0])))
                    seg.drop(1).forEach { lineTo(xOf(it.minuteOfDay), yOf(sel(it))) }
                }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
            line({ it.loadW }, LoadColor, 1.6.dp.toPx(), dashed = true)
            line({ it.gridW }, GridColor, 2.dp.toPx())
            line({ it.solarW }, SolarDeep, 2.4.dp.toPx())
        }
    }
}

/** Paired bars per day: solar (yellow) and grid (blue), in units (kWh). */
@Composable
fun DailyBarsChart(
    days: List<EnergyStore.DayRecord>,   // oldest -> newest
    labels: List<String>,
    axisColor: Color,
    gridLineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val leftPad = 34.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 8.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - topPad
        val textPx = 10.dp.toPx()
        val maxKwh = days.maxOfOrNull { max(it.solarWh, it.gridWh) / 1000.0 }?.toFloat() ?: 0f
        val top = niceTop(max(maxKwh, 1f))
        for (i in 0..4) {
            val y = topPad + h - h * i / 4f
            drawLine(gridLineColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f,
                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val v = top * i / 4f
            label(if (v % 1f == 0f) "${v.toInt()}" else "%.1f".format(v), leftPad - 6.dp.toPx(), y + textPx / 3,
                axisColor, textPx, android.graphics.Paint.Align.RIGHT)
        }
        if (days.isEmpty()) return@Canvas
        val slot = w / days.size
        val barW = (slot * 0.34f).coerceAtMost(14.dp.toPx())
        val labelEvery = ceil(days.size / 8f).toInt().coerceAtLeast(1)
        days.forEachIndexed { i, d ->
            val cx = leftPad + slot * i + slot / 2
            val sH = h * ((d.solarWh / 1000.0).toFloat() / top).coerceIn(0f, 1f)
            val gH = h * ((d.gridWh / 1000.0).toFloat() / top).coerceIn(0f, 1f)
            val r = CornerRadius(barW / 3, barW / 3)
            if (sH > 0f) drawRoundRect(SolarColor, Offset(cx - barW - 1f, topPad + h - sH), Size(barW, sH), r)
            if (gH > 0f) drawRoundRect(GridColor, Offset(cx + 1f, topPad + h - gH), Size(barW, gH), r)
            if ((days.size - 1 - i) % labelEvery == 0) {
                label(labels.getOrElse(i) { "" }, cx, size.height - 4.dp.toPx(), axisColor, textPx, android.graphics.Paint.Align.CENTER)
            }
        }
    }
}

/** Single-series bars for estimated daily units (drawn lighter, since they are an estimate). */
@Composable
fun EstimateBarsChart(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    axisColor: Color,
    gridLineColor: Color,
    highlightIndex: Int = -1,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(170.dp)) {
        val leftPad = 30.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 8.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - topPad
        val textPx = 10.dp.toPx()
        val top = niceTop(max(values.maxOrNull()?.toFloat() ?: 0f, 1f))
        for (i in 0..4) {
            val y = topPad + h - h * i / 4f
            drawLine(gridLineColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f,
                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val v = top * i / 4f
            label(if (v % 1f == 0f) "${v.toInt()}" else "%.1f".format(v), leftPad - 6.dp.toPx(), y + textPx / 3,
                axisColor, textPx, android.graphics.Paint.Align.RIGHT)
        }
        if (values.isEmpty()) return@Canvas
        val slot = w / values.size
        val barW = (slot * 0.62f).coerceAtMost(16.dp.toPx())
        val labelEvery = ceil(values.size / 8f).toInt().coerceAtLeast(1)
        values.forEachIndexed { i, v ->
            val cx = leftPad + slot * i + slot / 2
            val bh = h * (v.toFloat() / top).coerceIn(0f, 1f)
            if (bh > 0f) drawRoundRect(
                if (i == highlightIndex) color else color.copy(alpha = 0.45f),
                Offset(cx - barW / 2, topPad + h - bh), Size(barW, bh), CornerRadius(barW / 4, barW / 4)
            )
            if ((values.size - 1 - i) % labelEvery == 0) {
                label(labels.getOrElse(i) { "" }, cx, size.height - 4.dp.toPx(), axisColor, textPx, android.graphics.Paint.Align.CENTER)
            }
        }
    }
}

/** Grouped bars: one group per bucket, one bar per series. Values in kWh. */
@Composable
fun GroupedBarsChart(
    series: List<Pair<Color, List<Double>>>,
    labels: List<String>,
    axisColor: Color,
    gridLineColor: Color,
    unit: String = "",
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(210.dp)) {
        val leftPad = 34.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 8.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - topPad
        val textPx = 10.dp.toPx()
        val n = labels.size
        val maxV = series.flatMap { it.second }.maxOrNull()?.toFloat() ?: 0f
        val top = niceTop(max(maxV, 0.4f))
        for (i in 0..4) {
            val y = topPad + h - h * i / 4f
            drawLine(gridLineColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f,
                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val v = top * i / 4f
            label(if (v >= 10 || v % 1f == 0f) "%.0f".format(v) else "%.1f".format(v), leftPad - 6.dp.toPx(), y + textPx / 3,
                axisColor, textPx, android.graphics.Paint.Align.RIGHT)
        }
        if (n == 0) return@Canvas
        val slot = w / n
        val groupW = slot * 0.78f
        val barW = (groupW / series.size).coerceAtMost(12.dp.toPx())
        val labelEvery = ceil(n / 8f).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val cx = leftPad + slot * i + slot / 2
            val start = cx - barW * series.size / 2
            series.forEachIndexed { si, (color, vals) ->
                val v = vals.getOrElse(i) { 0.0 }.toFloat()
                val bh = h * (v / top).coerceIn(0f, 1f)
                if (bh > 0.5f) drawRoundRect(color, Offset(start + barW * si + 0.5f, topPad + h - bh), Size(barW - 1f, bh),
                    CornerRadius(barW / 4, barW / 4))
            }
            if ((n - 1 - i) % labelEvery == 0) label(labels[i], cx, size.height - 4.dp.toPx(), axisColor, textPx, android.graphics.Paint.Align.CENTER)
        }
    }
}
