package com.glass.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * Tron: Legacy's Grid: a neon floor in perspective that slowly flows toward the viewer, a glowing
 * horizon, and light cycles racing down the lanes leaving a fading trail.
 * Transparent above the horizon so whatever is behind it (the wallpaper) shows through.
 */
class GridBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = Themes.NEON
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        color = Themes.NEON
    }
    private val floor = Paint()
    private val horizonGlow = Paint()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 0..1, how far the floor has flowed toward the viewer within one row. */
    private var phase = 0f
    private val flow = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3_200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            advanceCycles()
            invalidate()
        }
    }

    /** A light cycle: a lane, how far along it is (0 = horizon, 1 = past the viewer), its colour. */
    private class Cycle(var lane: Int, var t: Float, var speed: Float, var color: Int)

    private val cycles = listOf(
        Cycle(-3, 0.1f, 0.0040f, Themes.NEON),
        Cycle(4, 0.6f, 0.0032f, Themes.NEON_ORANGE),
    )

    private var horizon = 0f

    /**
     * A wallpaper is behind this view: keep the floor see-through so its scenery (vehicles on the
     * floor) shows, while the lines and light cycles still run on top.
     */
    var wallpaperBehind = false
        set(value) {
            if (field == value) return
            field = value
            if (height > 0) updateFloor(height)
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        horizon = h * HORIZON
        updateFloor(h)
        val band = h * 0.10f
        horizonGlow.shader = LinearGradient(
            0f, horizon - band, 0f, horizon + band * 0.6f,
            intArrayOf(Color.TRANSPARENT, 0x552BE6FF, 0x992BE6FF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 0.82f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun updateFloor(h: Int) {
        val colors = if (wallpaperBehind) {
            intArrayOf(0x14020A10, 0x99000000.toInt())
        } else {
            intArrayOf(0xB3020A10.toInt(), 0xE6000000.toInt())
        }
        floor.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), colors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val vx = w / 2f
        val depth = h - horizon

        canvas.drawRect(0f, horizon, w, h, floor)
        canvas.drawRect(0f, horizon - h * 0.10f, w, horizon + h * 0.06f, horizonGlow)

        // Lanes: lines from the vanishing point fanning out across (and past) the screen.
        val laneSpacing = w / 9f
        for (i in -LANES..LANES) {
            val xBottom = vx + i * laneSpacing
            drawNeon(canvas, vx, horizon, xBottom, h, 0.55f)
        }

        // Rows: spacing grows toward the viewer (1/z), flowing forward with `phase`.
        for (k in 0..ROWS) {
            val z = 1f + k - phase
            if (z <= 0.05f) continue
            val y = horizon + depth / z
            if (y > h) continue
            val nearness = ((y - horizon) / depth).coerceIn(0f, 1f)
            drawNeon(canvas, 0f, y, w, y, 0.15f + 0.85f * nearness)
        }

        // Light cycles: a bright head with a fading trail along a lane.
        for (c in cycles) {
            val xBottom = vx + c.lane * laneSpacing
            val head = laneAt(c.t, vx, xBottom, depth)
            val tail = laneAt((c.t - 0.18f).coerceAtLeast(0f), vx, xBottom, depth)
            trailPaint.shader = LinearGradient(
                tail.first, tail.second, head.first, head.second,
                Color.TRANSPARENT, c.color, Shader.TileMode.CLAMP,
            )
            trailPaint.strokeWidth = (2f + 6f * c.t) * density
            canvas.drawLine(tail.first, tail.second, head.first, head.second, trailPaint)
        }
    }

    /** Point on a lane, t from 0 (horizon) to 1 (bottom of the screen), with perspective. */
    private fun laneAt(t: Float, vx: Float, xBottom: Float, depth: Float): Pair<Float, Float> {
        val p = t * t // accelerate toward the viewer, like perspective
        return (vx + (xBottom - vx) * p) to (horizon + depth * p)
    }

    private fun drawNeon(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, strength: Float) {
        glow.alpha = (40 * strength).toInt()
        line.alpha = (210 * strength).toInt()
        canvas.drawLine(x1, y1, x2, y2, glow)
        canvas.drawLine(x1, y1, x2, y2, line)
    }

    private fun advanceCycles() {
        for (c in cycles) {
            c.t += c.speed
            if (c.t > 1.25f) {
                // New run on a random lane, sometimes swapping to the other colour.
                c.t = 0f
                c.lane = Random.nextInt(-LANES + 1, LANES)
                c.speed = Random.nextDouble(0.0028, 0.0048).toFloat()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) flow.start()
    }

    override fun onDetachedFromWindow() {
        flow.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (visibility == VISIBLE && isShown) {
            if (!flow.isStarted) flow.start()
        } else {
            flow.cancel()
        }
    }

    companion object {
        /** Horizon height as a fraction of the view: just above the dock. */
        const val HORIZON = 0.56f
        private const val LANES = 14
        private const val ROWS = 26
    }
}
