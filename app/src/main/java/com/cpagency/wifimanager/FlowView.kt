package com.cpagency.wifimanager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Dashboard hero: a gradient panel showing the live network.
 *
 *   ONLINE                               HG8546M
 *   100.70.30.30 · up 29m           CPU 23% · RAM 68%
 *
 *     (globe) ====>>>==== (router) ====>>>==== (devices)
 *     Internet             Router              6 devices
 *
 *   ↓ 12.4 Mbps        ↑ 1.2 Mbps        ~~~~sparkline~~~~
 *
 * Mint particles flow towards the devices (download), amber ones flow back
 * (upload); both speed up with real traffic. Turns red and stops when offline.
 */
class FlowView(context: Context) : View(context) {

    // ---- data set by the activity ----
    var internetUp = false
    var statusTitle = "Checking..."
    var statusSub = ""
    var routerName = "Router"
    var routerSub = ""
    var devicesLabel = "Devices"
    var downMbps = 0.0
    var upMbps = 0.0
    private val history = ArrayList<Double>()

    /** Add a live download sample (Mbps) for the sparkline. */
    fun addSample(down: Double, up: Double) {
        downMbps = down; upMbps = up
        history.add(down)
        while (history.size > 30) history.removeAt(0)
        invalidate()
    }

    fun update() = invalidate()

    // ---- drawing setup ----
    private val dpF = resources.displayMetrics.density
    private fun dp(v: Float) = v * dpF

    private val mint = Color.parseColor("#6EF2C2")
    private val amber = Color.parseColor("#FFC86B")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.WHITE; alpha = 60 }
    private val particle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.WHITE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = mint; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val sparkFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val caps = text(10.5f, bold = true, alpha = 170).apply { letterSpacing = 0.12f }
    private val big = text(26f, bold = true)
    private val small = text(12.5f, alpha = 200)
    private val nodeLabel = text(12f, alpha = 220).apply { textAlign = Paint.Align.CENTER }
    private val statValue = text(20f, bold = true)
    private val statUnit = text(12f, alpha = 190)

    private fun text(sp: Float, bold: Boolean = false, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; this.alpha = alpha; textSize = sp * resources.displayMetrics.scaledDensity
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val icGlobe = icon(R.drawable.ic_globe)
    private val icRouter = icon(R.drawable.ic_router)
    private val icDevices = icon(R.drawable.ic_tab_devices)
    private fun icon(id: Int): Drawable = DrawableCompat.wrap(ContextCompat.getDrawable(context, id)!!.mutate()).also { DrawableCompat.setTint(it, Color.WHITE) }

    private val rect = RectF()
    private val path = Path()

    // ---- animation ----
    private var downPhase = 0f
    private var upPhase = 0f
    private var pulse = 0f
    private var lastFrame = 0L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    private fun speedFor(mbps: Double) = 0.18f + (ln(1.0 + max(0.0, mbps)) / ln(101.0)).toFloat().coerceIn(0f, 1f) * 0.9f

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        if (internetUp) {
            downPhase = (downPhase + dt * speedFor(downMbps)) % 1f
            upPhase = (upPhase + dt * speedFor(upMbps) * 0.8f) % 1f
        }
        pulse = (pulse + dt * 0.45f) % 1f
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0; animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(318f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(20f)

        // background gradient panel
        val (c1, c2) = if (internetUp) Color.parseColor("#1B1F5E") to Color.parseColor("#3D5AFE")
                       else Color.parseColor("#3A1420") to Color.parseColor("#B23A48")
        bgPaint.shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, dp(22f), dp(22f), bgPaint)
        // soft light in the top-right corner
        glow.shader = RadialGradient(w * 0.85f, h * 0.05f, w * 0.6f, Color.argb(55, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, dp(22f), dp(22f), glow)

        // ---- header ----
        var y = pad + dp(10f)
        c.drawText("INTERNET", pad, y, caps)
        y += dp(30f)
        val dotR = dp(5f)
        particle.shader = null
        particle.color = if (internetUp) mint else Color.parseColor("#FF8A9A")
        particle.alpha = 255
        c.drawCircle(pad + dotR, y - dp(9f), dotR, particle)
        c.drawText(statusTitle, pad + dotR * 2 + dp(8f), y, big)
        c.drawText(statusSub, pad, y + dp(20f), small)

        val right = w - pad
        caps.textAlign = Paint.Align.RIGHT; small.textAlign = Paint.Align.RIGHT
        c.drawText(routerName.uppercase(), right, pad + dp(10f), caps)
        c.drawText(routerSub, right, pad + dp(30f), small)
        caps.textAlign = Paint.Align.LEFT; small.textAlign = Paint.Align.LEFT

        // ---- network row ----
        val cy = dp(168f)
        val xs = floatArrayOf(w * 0.13f, w * 0.5f, w * 0.87f)
        val r = dp(22f)
        val rRouter = dp(28f)

        // connectors
        for (i in 0..1) {
            val sx = xs[i] + (if (i == 0) r else rRouter) + dp(6f)
            val ex = xs[i + 1] - (if (i == 0) rRouter else r) - dp(6f)
            if (internetUp) {
                linePaint.pathEffect = null
            } else {
                linePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(6f)), 0f)
            }
            c.drawLine(sx, cy, ex, cy, linePaint)
            if (internetUp) {
                drawStream(c, sx, ex, cy - dp(5f), downPhase + i * 0.37f, mint, forward = true)
                drawStream(c, sx, ex, cy + dp(5f), upPhase + i * 0.21f, amber, forward = false)
            }
        }

        // router halo rings
        if (internetUp) for (k in 0..1) {
            val p = (pulse + k * 0.5f) % 1f
            halo.alpha = ((1f - p) * 90).toInt()
            c.drawCircle(xs[1], cy, rRouter + dp(3f) + p * dp(15f), halo)
        }

        drawNode(c, xs[0], cy, r, icGlobe)
        drawNode(c, xs[1], cy, rRouter, icRouter)
        drawNode(c, xs[2], cy, r, icDevices)
        c.drawText("Internet", xs[0], cy + rRouter + dp(18f), nodeLabel)
        c.drawText("Router", xs[1], cy + rRouter + dp(18f), nodeLabel)
        c.drawText(devicesLabel, xs[2], cy + rRouter + dp(18f), nodeLabel)

        // ---- bottom stats + sparkline ----
        val by = h - pad - dp(4f)
        drawStat(c, pad, by, "↓", downMbps, mint)
        drawStat(c, pad + dp(118f), by, "↑", upMbps, amber)

        val sx0 = pad + dp(232f)
        val sx1 = w - pad
        if (sx1 - sx0 > dp(40f) && history.size >= 2) {
            val top = by - dp(34f)
            val bottom = by
            val maxV = max(1.0, history.maxOrNull() ?: 1.0)
            path.reset()
            history.forEachIndexed { i, v ->
                val x = sx0 + (sx1 - sx0) * i / (history.size - 1)
                val yy = bottom - ((v / maxV).toFloat() * (bottom - top))
                if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            c.drawPath(path, sparkLine)
            path.lineTo(sx1, bottom); path.lineTo(sx0, bottom); path.close()
            sparkFill.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(90, 110, 242, 194), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawPath(path, sparkFill)
        }
    }

    private fun drawStat(c: Canvas, x: Float, baseline: Float, arrow: String, mbps: Double, color: Int) {
        statValue.color = color
        val v = if (!internetUp) "--" else if (mbps >= 100) "%.0f".format(mbps) else "%.1f".format(mbps)
        c.drawText("$arrow $v", x, baseline - dp(14f), statValue)
        c.drawText(if (arrow == "↓") "Mbps download" else "Mbps upload", x, baseline + dp(2f), statUnit)
    }

    /** a lane of particles with short fading tails */
    private fun drawStream(c: Canvas, sx: Float, ex: Float, y: Float, phase: Float, color: Int, forward: Boolean) {
        val len = ex - sx
        for (k in 0 until 2) {
            var t = (phase + k / 2f) % 1f
            if (!forward) t = 1f - t
            val fade = sin(t * Math.PI).toFloat()
            for (tail in 0 until 6) {
                val tt = if (forward) t - tail * 0.022f else t + tail * 0.022f
                if (tt < 0f || tt > 1f) continue
                particle.color = color
                particle.alpha = (fade * (235 - tail * 38)).toInt().coerceIn(0, 255)
                c.drawCircle(sx + len * tt, y, dp(2.6f) - tail * dp(0.35f), particle)
            }
        }
    }

    private fun drawNode(c: Canvas, x: Float, y: Float, r: Float, icon: Drawable) {
        nodeFill.alpha = 34
        c.drawCircle(x, y, r, nodeFill)
        nodeStroke.alpha = 110
        c.drawCircle(x, y, r, nodeStroke)
        val s = (r * 0.95f).toInt()
        icon.setBounds((x - s / 2).toInt(), (y - s / 2).toInt(), (x + s / 2).toInt(), (y + s / 2).toInt())
        icon.draw(c)
    }
}
