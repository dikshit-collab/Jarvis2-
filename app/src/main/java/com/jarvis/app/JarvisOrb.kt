package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class JarvisOrb(context: Context) : View(context) {

    // 0 = idle, 1 = listening, 2 = thinking
    var state = 0

    private var angle = 0f
    private var pulse = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f
        val color = if (state == 1) Color.parseColor("#FF5252") else Color.parseColor("#00E5FF")
        val speed = when (state) { 0 -> 0.6f; 1 -> 1.6f; else -> 5f }
        angle += speed
        pulse += if (state == 1) 0.15f else 0.06f
        val beat = (sin(pulse.toDouble()).toFloat() + 1f) / 2f

        // glowing core
        val coreR = r * (0.30f + 0.05f * beat)
        fill.shader = RadialGradient(
            cx, cy, coreR * 1.8f,
            intArrayOf(Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, coreR * 1.8f, fill)
        fill.shader = null
        fill.color = Color.WHITE
        fill.alpha = 200
        c.drawCircle(cx, cy, coreR * 0.25f, fill)

        ring.color = color

        // outer thin ring
        ring.strokeWidth = 3f
        ring.alpha = 200
        c.drawCircle(cx, cy, r, ring)

        // tick marks
        ring.strokeWidth = 3f
        ring.alpha = 130
        c.save()
        c.rotate(-angle * 0.3f, cx, cy)
        for (i in 0 until 60) {
            val len = if (i % 5 == 0) r * 0.07f else r * 0.035f
            c.drawLine(cx, cy - r * 0.92f, cx, cy - r * 0.92f + len, ring)
            c.rotate(6f, cx, cy)
        }
        c.restore()

        // thick rotating arcs
        ring.strokeWidth = 10f
        ring.alpha = 230
        rect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        for (i in 0 until 3) c.drawArc(rect, angle + i * 120f, 70f, false, ring)

        // counter-rotating arcs
        ring.strokeWidth = 5f
        ring.alpha = 180
        rect.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f)
        for (i in 0 until 4) c.drawArc(rect, -angle * 1.5f + i * 90f, 45f, false, ring)

        // inner ring
        ring.strokeWidth = 2f
        ring.alpha = 160
        c.drawCircle(cx, cy, r * 0.5f, ring)

        postInvalidateOnAnimation()
    }
}
