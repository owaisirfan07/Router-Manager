package com.cpagency.wifimanager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Animated "where is the internet going" picture for the dashboard:
 *
 *              Internet
 *                 |
 *               Router
 *              /      \
 *          WiFi        Cable
 *
 * Dots run along the lines while the internet is up; they move faster when
 * there's more traffic. A line turns red/dashed when that link is down.
 */
class FlowView(context: Context) : View(context) {

    // ---- data set from the activity ----
    var internetUp = false
    var internetLabel = "Internet"
    var internetSub = "--"
    var routerLabel = "Router"
    var routerSub = ""
    var wifiLabel = "WiFi"
    var wifiSub = "--"
    var cableLabel = "Cable"
    var cableSub = "--"
    var wifiCount = 0
    var cableCount = 0
    /** current traffic in Mbps (controls dot speed) */
    var downMbps = 0.0
    var upMbps = 0.0

    fun update() = invalidate()

    // ---- drawing ----
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cGood = ContextCompat.getColor(context, R.color.good)
    private val cBad = ContextCompat.getColor(context, R.color.bad)
    private val cPrimary = ContextCompat.getColor(context, R.color.primary)
    private val cText = ContextCompat.getColor(context, R.color.textPrimary)
    private val cSub = ContextCompat.getColor(context, R.color.textSecondary)
    private val cLine = ContextCompat.getColor(context, R.color.divider)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(13f); typeface = Typeface.DEFAULT_BOLD; color = cText }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(11.5f); color = cSub }
    private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)

    private val icGlobe = icon(R.drawable.ic_globe)
    private val icRouter = icon(R.drawable.ic_router)
    private val icWifi = icon(R.drawable.ic_wifi)
    private val icCable = icon(R.drawable.ic_lan)

    private fun icon(id: Int): Drawable = DrawableCompat.wrap(ContextCompat.getDrawable(context, id)!!.mutate())

    private var phase = 0f      // 0..1, moves the dots
    private var pulse = 0f      // 0..1, router ring
    private var lastFrame = 0L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        // speed: 0.25 loops/s when idle, up to ~1.4 loops/s with heavy traffic
        val traffic = (downMbps + upMbps).coerceAtLeast(0.0)
        val speed = 0.25f + (ln(1.0 + traffic) / ln(101.0)).toFloat().coerceIn(0f, 1f) * 1.15f
        if (internetUp) phase = (phase + dt * speed) % 1f
        pulse = (pulse + dt * 0.6f) % 1f
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0; animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(330f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val r = dp(30f)
        val internet = PointF(w / 2, dp(46f))
        val router = PointF(w / 2, dp(160f))
        val wifi = PointF(w * 0.2f, dp(262f))
        val cable = PointF(w * 0.8f, dp(262f))

        // lines
        drawLink(canvas, internet, router, internetUp, r, forward = true)
        drawLink(canvas, router, wifi, internetUp && wifiCount > 0, r, forward = true, active = wifiCount > 0)
        drawLink(canvas, router, cable, internetUp && cableCount > 0, r, forward = true, active = cableCount > 0)

        // router pulse ring
        if (internetUp) {
            ringPaint.color = cPrimary
            ringPaint.alpha = ((1f - pulse) * 120).toInt()
            canvas.drawCircle(router.x, router.y, r + dp(4f) + pulse * dp(16f), ringPaint)
        }

        drawNode(canvas, internet, r, icGlobe, if (internetUp) cGood else cBad)
        drawNode(canvas, router, r * 1.15f, icRouter, cPrimary)
        drawNode(canvas, wifi, r, icWifi, if (wifiCount > 0) cPrimary else cSub)
        drawNode(canvas, cable, r, icCable, if (cableCount > 0) cPrimary else cSub)

        // labels
        label(canvas, internet.x + r + dp(70f), internet.y - dp(2f), internetLabel, internetSub, leftAlign = false)
        label(canvas, router.x + r + dp(70f), router.y - dp(2f), routerLabel, routerSub, leftAlign = false)
        label(canvas, wifi.x, wifi.y + r + dp(18f), wifiLabel, wifiSub)
        label(canvas, cable.x, cable.y + r + dp(18f), cableLabel, cableSub)
    }

    private fun drawLink(c: Canvas, a: PointF, b: PointF, flowing: Boolean, r: Float, forward: Boolean, active: Boolean = true) {
        // shorten the line so it starts/ends at the circle edges
        val len = hypot(b.x - a.x, b.y - a.y)
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        val sx = a.x + ux * (r + dp(4f)); val sy = a.y + uy * (r + dp(4f))
        val ex = b.x - ux * (r + dp(4f)); val ey = b.y - uy * (r + dp(4f))

        linePaint.pathEffect = if (!active || !internetUp) dash else null
        linePaint.color = when {
            !internetUp -> cBad
            !active -> cLine
            else -> cLine
        }
        linePaint.alpha = if (!internetUp) 140 else 255
        c.drawLine(sx, sy, ex, ey, linePaint)

        if (!flowing) return
        // 3 moving dots
        val segLen = hypot(ex - sx, ey - sy)
        for (i in 0 until 3) {
            var t = (phase + i / 3f) % 1f
            if (!forward) t = 1f - t
            val x = sx + (ex - sx) * t
            val y = sy + (ey - sy) * t
            val fade = sin(t * Math.PI).toFloat()           // fade in/out at the ends
            dotPaint.color = cGood
            dotPaint.alpha = (60 + 195 * fade).toInt()
            c.drawCircle(x, y, dp(4.5f), dotPaint)
            if (segLen < dp(10f)) break
        }
    }

    private fun drawNode(c: Canvas, p: PointF, r: Float, icon: Drawable, color: Int) {
        nodePaint.color = color
        nodePaint.alpha = 34
        c.drawCircle(p.x, p.y, r, nodePaint)
        nodePaint.alpha = 255
        nodePaint.style = Paint.Style.STROKE
        nodePaint.strokeWidth = dp(2f)
        c.drawCircle(p.x, p.y, r, nodePaint)
        nodePaint.style = Paint.Style.FILL
        val s = (r * 0.95f).toInt()
        icon.setBounds((p.x - s / 2).toInt(), (p.y - s / 2).toInt(), (p.x + s / 2).toInt(), (p.y + s / 2).toInt())
        DrawableCompat.setTint(icon, color)
        icon.draw(c)
    }

    private fun label(c: Canvas, x: Float, y: Float, title: String, sub: String, leftAlign: Boolean = false) {
        c.drawText(title, x, y, titlePaint)
        if (sub.isNotEmpty()) c.drawText(sub, x, y + dp(16f), subPaint)
    }
}
