package com.cpagency.wifimanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Simple line chart of the fibre Rx power per day: a line for the daily average
 * and a shaded band from the day's weakest to strongest reading. The green
 * background strip is the healthy range (-8 to -27 dBm).
 */
class FibreChart(context: Context) : View(context) {

    var days: List<FibreStore.Day> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private fun col(id: Int) = ContextCompat.getColor(context, id)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = col(R.color.primary) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.primary) }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.primary); alpha = 45 }
    private val good = Paint().apply { color = col(R.color.good); alpha = 28 }
    private val grid = Paint().apply { color = col(R.color.divider); strokeWidth = 1f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.textSecondary); textSize = 10.5f * d }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (200 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = 40 * d; val right = width - 10 * d
        val top = 10 * d; val bottom = height - 22 * d
        // y range: always show the healthy range, widen if readings go outside it
        val vals = days.flatMap { listOf(it.minRx, it.maxRx) }
        val hi = maxOf(-6.0, vals.maxOrNull() ?: -6.0)
        val lo = minOf(-30.0, vals.minOrNull() ?: -30.0)
        fun y(v: Double) = (top + (hi - v) / (hi - lo) * (bottom - top)).toFloat()

        c.drawRect(left, y(-8.0), right, y(-27.0), good)
        var g = Math.ceil(hi / 5) * 5
        while (g >= lo) {
            c.drawLine(left, y(g), right, y(g), grid)
            c.drawText("%.0f".format(g), 4 * d, y(g) + 4 * d, label)
            g -= 5
        }
        if (days.isEmpty()) return

        val n = days.size
        fun x(i: Int) = if (n == 1) (left + right) / 2 else left + i * (right - left) / (n - 1)

        val bandPath = Path()
        days.forEachIndexed { i, dd -> if (i == 0) bandPath.moveTo(x(i), y(dd.maxRx)) else bandPath.lineTo(x(i), y(dd.maxRx)) }
        for (i in n - 1 downTo 0) bandPath.lineTo(x(i), y(days[i].minRx))
        bandPath.close()
        if (n > 1) c.drawPath(bandPath, band)

        val p = Path()
        days.forEachIndexed { i, dd -> if (i == 0) p.moveTo(x(i), y(dd.avgRx)) else p.lineTo(x(i), y(dd.avgRx)) }
        c.drawPath(p, line)
        if (n <= 31) days.forEachIndexed { i, dd -> c.drawCircle(x(i), y(dd.avgRx), 3 * d, dot) }

        // first / last date under the axis
        val fmtIn = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val fmtOut = java.text.SimpleDateFormat("d MMM", java.util.Locale.US)
        fun lab(k: String) = runCatching { fmtOut.format(fmtIn.parse(k)!!) }.getOrDefault(k)
        c.drawText(lab(days.first().key), left, height - 6 * d, label)
        if (n > 1) {
            val t = lab(days.last().key)
            c.drawText(t, right - label.measureText(t), height - 6 * d, label)
        }
    }
}
