package com.shadow.injector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated background: a slow 3D starfield drifting towards the viewer with twinkling
 * stars, a faint nebula glow behind the header and the occasional shooting star.
 *
 * Pure Canvas — no OpenGL, no allocations inside onDraw.
 */
class StarfieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Star {
        var x = 0f
        var y = 0f
        var z = 1f
        var twinklePhase = 0f
        var twinkleSpeed = 1f
        var tint = 0f // 0 = icy white/cyan, 1 = violet
    }

    private class Comet {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var maxLife = 1f
    }

    private companion object {
        const val SPEED = 0.075f      // depth units per second (slow drift)
        const val NEAR_Z = 0.035f
        val NEBULA_CORE = Color.argb(58, 18, 52, 104)
        val NEBULA_EDGE = Color.TRANSPARENT
    }

    private val stars = ArrayList<Star>(180)
    private val comet = Comet()

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rnd = Random(System.nanoTime() xor 0x9E3779B9L)

    private var vw = 0f
    private var vh = 0f
    private var cx = 0f
    private var cy = 0f
    private var spread = 600f

    private var lastFrame = 0L
    private var elapsed = 0f
    private var nextCometIn = 3f
    private var running = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vw = w.toFloat()
        vh = h.toFloat()
        cx = vw * 0.5f
        cy = vh * 0.40f
        spread = max(vw, vh) * 0.72f

        nebulaPaint.shader = RadialGradient(
            cx, cy, max(vw, vh) * 0.85f,
            NEBULA_CORE, NEBULA_EDGE, Shader.TileMode.CLAMP
        )

        val wanted = ((w * h) / 9000).coerceIn(90, 240)
        while (stars.size < wanted) stars.add(randomStar(anywhere = true))
        while (stars.size > wanted) stars.removeAt(stars.size - 1)
    }

    private fun randomStar(anywhere: Boolean): Star {
        val s = Star()
        respawn(s, anywhere)
        return s
    }

    private fun respawn(s: Star, anywhere: Boolean) {
        val angle = rnd.nextDouble(0.0, Math.PI * 2.0).toFloat()
        val radius = if (anywhere) {
            rnd.nextDouble(0.04, 1.0).toFloat()
        } else {
            rnd.nextDouble(0.80, 1.0).toFloat()
        }
        s.x = cos(angle) * radius
        s.y = sin(angle) * radius
        s.z = if (anywhere) rnd.nextDouble(NEAR_Z.toDouble(), 1.0).toFloat() else 1f
        s.twinklePhase = rnd.nextDouble(0.0, Math.PI * 2.0).toFloat()
        s.twinkleSpeed = rnd.nextDouble(0.5, 2.3).toFloat()
        s.tint = rnd.nextFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        if (lastFrame == 0L) lastFrame = now
        var dt = (now - lastFrame) / 1_000_000_000f
        lastFrame = now
        if (dt > 0.05f) dt = 0.05f // clamp after a stall
        elapsed += dt

        canvas.drawColor(Color.BLACK)
        nebulaPaint.shader?.let { canvas.drawRect(0f, 0f, vw, vh, nebulaPaint) }

        for (i in stars.indices) {
            val s = stars[i]

            s.z -= dt * SPEED
            if (s.z <= NEAR_Z) respawn(s, anywhere = false)

            val depth = 1f - s.z
            val sx = cx + (s.x / s.z) * spread
            val sy = cy + (s.y / s.z) * spread
            if (sx < -60f || sx > vw + 60f || sy < -60f || sy > vh + 60f) continue

            val radius = 0.32f + depth * 2.0f
            var alpha = depth * depth * 240f
            alpha *= 0.62f + 0.38f * sin(elapsed * s.twinkleSpeed * 2f + s.twinklePhase)
            val a = alpha.toInt().coerceIn(0, 255)
            if (a <= 3) continue

            val red = (205 + 50 * (1f - s.tint)).toInt().coerceIn(0, 255)
            val green = (222 + 24 * s.tint).toInt().coerceIn(0, 255)
            starPaint.color = Color.argb(a, red, green, 255)

            // Bloom halo on the closest stars.
            if (radius > 1.45f) {
                starPaint.alpha = (a * 0.16f).toInt().coerceIn(0, 255)
                canvas.drawCircle(sx, sy, radius * 3.4f, starPaint)
                starPaint.alpha = 255
            }
            canvas.drawCircle(sx, sy, radius, starPaint)
        }

        drawComet(canvas, dt)

        if (running) postInvalidateOnAnimation()
    }

    private fun drawComet(canvas: Canvas, dt: Float) {
        if (!comet.active) {
            nextCometIn -= dt
            if (nextCometIn > 0f) return
            val fromLeft = rnd.nextBoolean()
            comet.x = if (fromLeft) -80f else vw + 80f
            comet.y = rnd.nextDouble(0.0, vh.toDouble() * 0.55).toFloat()
            val speed = rnd.nextDouble(430.0, 820.0).toFloat()
            val angle = rnd.nextDouble(0.22, 0.60) * if (rnd.nextBoolean()) 1 else -1
            comet.vx = (if (fromLeft) 1f else -1f) * speed * cos(angle).toFloat()
            comet.vy = speed * sin(angle).toFloat()
            comet.maxLife = rnd.nextDouble(1.1, 2.0).toFloat()
            comet.life = comet.maxLife
            comet.active = true
            return
        }

        comet.life -= dt
        if (comet.life <= 0f) {
            comet.active = false
            nextCometIn = rnd.nextDouble(4.0, 11.0).toFloat()
            return
        }

        val px = comet.x
        val py = comet.y
        comet.x += comet.vx * dt
        comet.y += comet.vy * dt

        val dx = comet.x - px
        val dy = comet.y - py
        val d = hypot(dx, dy)
        if (d < 0.001f) return

        val fade = comet.life / comet.maxLife
        val fadeIn = min(1f, (comet.maxLife - comet.life) * 4.5f)
        val alpha = (255f * min(1f, fade * 1.5f) * fadeIn).toInt().coerceIn(0, 255)
        if (alpha <= 4) return

        val ux = dx / d
        val uy = dy / d
        val tail = 96f

        cometPaint.strokeWidth = 3.4f
        cometPaint.color = Color.argb((alpha * 0.30f).toInt(), 130, 220, 255)
        canvas.drawLine(comet.x, comet.y, comet.x - ux * tail, comet.y - uy * tail, cometPaint)

        cometPaint.strokeWidth = 1.5f
        cometPaint.color = Color.argb(alpha, 190, 245, 255)
        canvas.drawLine(
            comet.x, comet.y,
            comet.x - ux * tail * 0.55f, comet.y - uy * tail * 0.55f,
            cometPaint
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resume() else running = false
    }

    private fun resume() {
        running = true
        lastFrame = 0L
        postInvalidateOnAnimation()
    }
}
