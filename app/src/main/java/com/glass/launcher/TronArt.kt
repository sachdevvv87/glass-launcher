package com.glass.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Original Grid-style wallpapers, drawn on the TV: neon scenery over a perspective light-grid,
 * in the spirit of Tron: Legacy (no film imagery is used). Every seed gives a different picture;
 * the horizon matches [GridBackgroundView.HORIZON] so the animated Home floor continues it.
 */
object TronArt {

    enum class Scene(val title: String) {
        DUSK("The Grid at dusk"),
        CYCLES("Light cycles"),
        DISC("Identity disc"),
        CITY("Neon city"),
        PORTAL("The portal"),
        CYCLE("Light cycle"),
        DUEL("Cycle duel"),
        JET("Light jet"),
        SENTINEL("Patrol craft"),
    }

    private const val CYAN = 0xFF2BE6FF.toInt()
    private const val ICE = 0xFFBFF7FF.toInt()
    private const val ORANGE = 0xFFFF8A1F.toInt()

    /**
     * @param floorLines false for the Home background in the Grid theme, where the animated floor
     *   draws the grid lines itself.
     */
    fun render(scene: Scene, seed: Long, width: Int, floorLines: Boolean = true): Bitmap {
        val w = width
        val h = width * 9 / 16
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = Random(seed)
        val horizon = h * GridBackgroundView.HORIZON
        val accent = if (r.nextInt(3) == 0) ORANGE else CYAN

        sky(c, w, h, horizon, r)
        when (scene) {
            Scene.DUSK -> mountains(c, w, horizon, h, r)
            Scene.CITY -> city(c, w, horizon, h, r)
            Scene.DISC -> disc(c, w, horizon, h, r, accent)
            Scene.PORTAL -> portal(c, w, horizon, h)
            Scene.CYCLES, Scene.CYCLE, Scene.DUEL -> mountains(c, w, horizon, h, r, low = true)
            Scene.JET -> city(c, w, horizon, h, r)
            Scene.SENTINEL -> mountains(c, w, horizon, h, r)
        }
        floor(c, w, h, horizon, floorLines)
        horizonGlow(c, w, h, horizon)
        when (scene) {
            Scene.CYCLES -> cycles(c, w, h, horizon, r)
            Scene.PORTAL -> portalReflection(c, w, h, horizon)
            // Bikes stay above and right of the screensaver clock (bottom left).
            Scene.CYCLE -> {
                val facingRight = r.nextBoolean()
                val length = w * 0.34f
                lightCycle(c, w * 0.52f, horizon + h * 0.05f, length, accent, facingRight, wallTo = if (facingRight) 0f else w.toFloat(), h = h)
            }
            Scene.DUEL -> {
                val length = w * 0.22f
                val ground = horizon + h * 0.03f
                lightCycle(c, w * 0.24f, ground, length, CYAN, facingRight = true, wallTo = 0f, h = h)
                lightCycle(c, w * 0.56f, ground, length, ORANGE, facingRight = false, wallTo = w.toFloat(), h = h)
            }
            Scene.JET -> lightJet(c, w, h, horizon, r, accent)
            Scene.SENTINEL -> sentinel(c, w, h, horizon, r)
            else -> Unit
        }
        vignette(c, w, h)
        return bmp
    }

    // ---- vehicles --------------------------------------------------------------------------

    /**
     * A light cycle in side view: two glowing wheel rings under a low, sleek shell with a rider,
     * laying a solid light wall behind it, and a faint reflection on the floor.
     * [x0] is the left edge of the bike, [ground] where its wheels touch the floor.
     */
    private fun lightCycle(
        c: Canvas, x0: Float, ground: Float, length: Float, color: Int,
        facingRight: Boolean, wallTo: Float, h: Int,
    ) {
        val L = length
        // The light wall: a translucent ribbon from the rear wheel back to the edge of the screen.
        val rearX = if (facingRight) x0 + L * 0.02f else x0 + L * 0.98f
        val wallTop = ground - L * 0.24f
        val wall = Paint().apply {
            shader = LinearGradient(rearX, 0f, wallTo, 0f, color and 0x00FFFFFF or 0xAA000000.toInt(), color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRect(minOf(rearX, wallTo), wallTop, maxOf(rearX, wallTo), ground, wall)
        neon(c, rearX, wallTop, wallTo, wallTop, color, h * 0.003f, 0.9f)

        // Reflection first (underneath), then the bike.
        c.save()
        c.scale(1f, -0.35f, 0f, ground)
        val layer = c.saveLayerAlpha(null, 70)
        drawCycleBody(c, x0, ground, L, color, facingRight, h)
        c.restoreToCount(layer)
        c.restore()
        drawCycleBody(c, x0, ground, L, color, facingRight, h)
    }

    /**
     * Side view, facing right before any mirroring: a long, low wedge whose underside sits at axle
     * height, big hubless wheel rings drawn over it, and the rider lying flat inside the silhouette.
     */
    private fun drawCycleBody(c: Canvas, x0: Float, ground: Float, L: Float, color: Int, facingRight: Boolean, h: Int) {
        c.save()
        if (!facingRight) c.scale(-1f, 1f, x0 + L / 2f, ground)
        val r = L * 0.19f
        val rear = x0 + L * 0.21f
        val front = x0 + L * 0.79f
        val axle = ground - r
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF02090E.toInt() }

        // Wheel interiors first, so they stay dark under the body.
        for (x in listOf(rear, front)) c.drawCircle(x, axle, r, dark)

        // One-piece shell: a sharp tail, arching over the rear wheel, dipping where the rider lies
        // prone, rising at the helmet, arching over the front wheel to a sharp nose. It wraps the
        // upper two-thirds of both wheels; the underside runs just below the axles.
        val shell = Path().apply {
            moveTo(x0 - L * 0.07f, ground - L * 0.26f)                                       // tail tip
            cubicTo(x0 + L * 0.02f, ground - L * 0.44f, x0 + L * 0.12f, ground - L * 0.50f, x0 + L * 0.26f, ground - L * 0.50f)
            cubicTo(x0 + L * 0.36f, ground - L * 0.50f, x0 + L * 0.42f, ground - L * 0.46f, x0 + L * 0.48f, ground - L * 0.46f)
            cubicTo(x0 + L * 0.55f, ground - L * 0.46f, x0 + L * 0.58f, ground - L * 0.56f, x0 + L * 0.67f, ground - L * 0.55f) // helmet
            cubicTo(x0 + L * 0.80f, ground - L * 0.54f, x0 + L * 0.94f, ground - L * 0.46f, x0 + L * 1.09f, ground - L * 0.24f) // nose tip
            lineTo(x0 + L * 0.99f, ground - L * 0.17f)
            lineTo(x0 + L * 0.62f, ground - L * 0.17f)
            quadTo(x0 + L * 0.50f, ground - L * 0.24f, x0 + L * 0.38f, ground - L * 0.17f)
            lineTo(x0 + L * 0.02f, ground - L * 0.16f)
            close()
        }
        c.drawPath(shell, dark)
        c.drawPath(shell, glow(color, h * 0.006f, h * 0.012f).apply { style = Paint.Style.STROKE; alpha = 190 })
        c.drawPath(shell, line(ICE, h * 0.002f).apply { style = Paint.Style.STROKE })

        // Panel line following the shell, a light stripe low along the flank, and the visor.
        val panel = Path().apply {
            moveTo(x0 + L * 0.0f, ground - L * 0.30f)
            cubicTo(x0 + L * 0.2f, ground - L * 0.42f, x0 + L * 0.7f, ground - L * 0.44f, x0 + L * 1.0f, ground - L * 0.27f)
        }
        c.drawPath(panel, line(color, h * 0.0015f).apply { style = Paint.Style.STROKE; alpha = 140 })
        val stripe = Path().apply {
            moveTo(x0 - L * 0.03f, ground - L * 0.22f)
            lineTo(x0 + L * 1.02f, ground - L * 0.21f)
        }
        c.drawPath(stripe, glow(color, h * 0.006f, h * 0.012f).apply { style = Paint.Style.STROKE })
        c.drawPath(stripe, line(Color.WHITE, h * 0.0018f).apply { style = Paint.Style.STROKE; alpha = 230 })
        c.drawLine(x0 + L * 0.62f, ground - L * 0.53f, x0 + L * 0.76f, ground - L * 0.51f, line(color, h * 0.0035f))

        // Hubless wheels: a thick glowing outer ring over the body, a thin inner ring.
        for (x in listOf(rear, front)) {
            c.drawCircle(x, axle, r * 0.94f, glow(color, r * 0.14f, r * 0.22f).apply { style = Paint.Style.STROKE })
            c.drawCircle(x, axle, r * 0.94f, line(Color.WHITE, r * 0.035f).apply { style = Paint.Style.STROKE; alpha = 230 })
            c.drawCircle(x, axle, r * 0.70f, line(color, r * 0.03f).apply { style = Paint.Style.STROKE; alpha = 170 })
        }
        c.restore()
    }

    /** A delta-wing light jet streaking across the sky, twin light trails behind it. */
    private fun lightJet(c: Canvas, w: Int, h: Int, horizon: Float, r: Random, color: Int) {
        val cx = w * (0.55f + r.nextFloat() * 0.2f)
        val cy = horizon * (0.38f + r.nextFloat() * 0.2f)
        val s = w * 0.16f
        // Trails from the two wingtip engines back to the left edge, rising slightly.
        for (dy in listOf(-0.16f, 0.16f)) {
            val ex = cx - s * 0.55f
            val ey = cy + s * dy
            val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(ex, ey, 0f, ey + s * 0.4f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                strokeWidth = h * 0.006f
                maskFilter = BlurMaskFilter(h * 0.006f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawLine(ex, ey, 0f, ey + s * 0.4f, trail)
            c.drawLine(ex, ey, 0f, ey + s * 0.4f, line(Color.WHITE, h * 0.0015f).apply {
                shader = LinearGradient(ex, ey, w * 0.2f, ey + s * 0.3f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }
        val jet = Path().apply {
            moveTo(cx + s * 0.6f, cy)                    // nose
            lineTo(cx - s * 0.1f, cy - s * 0.08f)
            lineTo(cx - s * 0.55f, cy - s * 0.42f)      // upper wingtip
            lineTo(cx - s * 0.42f, cy - s * 0.06f)
            lineTo(cx - s * 0.6f, cy)                    // tail
            lineTo(cx - s * 0.42f, cy + s * 0.06f)
            lineTo(cx - s * 0.55f, cy + s * 0.42f)      // lower wingtip
            lineTo(cx - s * 0.1f, cy + s * 0.08f)
            close()
        }
        c.drawPath(jet, glow(color, 0f, h * 0.03f).apply { style = Paint.Style.FILL; alpha = 120 })
        c.drawPath(jet, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF02090E.toInt() })
        c.drawPath(jet, glow(color, h * 0.005f, h * 0.01f).apply { style = Paint.Style.STROKE })
        c.drawPath(jet, line(ICE, h * 0.002f).apply { style = Paint.Style.STROKE })
        // Cockpit streak and engine glows.
        c.drawLine(cx - s * 0.05f, cy, cx + s * 0.4f, cy, line(Color.WHITE, h * 0.003f))
        for (dy in listOf(-0.16f, 0.16f)) {
            c.drawCircle(cx - s * 0.5f, cy + s * dy, h * 0.008f, glow(Color.WHITE, 0f, h * 0.01f).apply { style = Paint.Style.FILL })
        }
    }

    /** A huge hovering arch-shaped patrol craft with orange light edges and a searchlight. */
    private fun sentinel(c: Canvas, w: Int, h: Int, horizon: Float, r: Random) {
        val cx = w * (0.35f + r.nextFloat() * 0.3f)
        val top = horizon * 0.22f
        val span = w * 0.3f
        val legH = horizon * 0.46f
        val beam = h * 0.05f
        // Searchlight cone sweeping the floor.
        val cone = Path().apply {
            moveTo(cx - span * 0.08f, top + beam)
            lineTo(cx + span * 0.08f, top + beam)
            lineTo(cx + span * 0.5f, horizon + h * 0.12f)
            lineTo(cx - span * 0.1f, horizon + h * 0.12f)
            close()
        }
        c.drawPath(cone, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, horizon + h * 0.12f, 0x44FF8A1F, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        val arch = Path().apply {
            // Top bar with angled ends, two slanted legs, open underneath.
            moveTo(cx - span / 2f, top + beam)
            lineTo(cx - span / 2f + beam, top)
            lineTo(cx + span / 2f - beam, top)
            lineTo(cx + span / 2f, top + beam)
            lineTo(cx + span * 0.42f, top + beam + legH)
            lineTo(cx + span * 0.30f, top + beam + legH)
            lineTo(cx + span * 0.34f, top + beam * 1.6f)
            lineTo(cx - span * 0.34f, top + beam * 1.6f)
            lineTo(cx - span * 0.30f, top + beam + legH)
            lineTo(cx - span * 0.42f, top + beam + legH)
            close()
        }
        c.drawPath(arch, glow(ORANGE, 0f, h * 0.04f).apply { style = Paint.Style.FILL; alpha = 90 })
        c.drawPath(arch, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050302.toInt() })
        c.drawPath(arch, glow(ORANGE, h * 0.006f, h * 0.012f).apply { style = Paint.Style.STROKE })
        c.drawPath(arch, line(0xFFFFD2A8.toInt(), h * 0.002f).apply { style = Paint.Style.STROKE })
        // Panel lines across the top bar.
        for (k in 1..5) {
            val x = cx - span * 0.4f + k * span * 0.8f / 6f
            c.drawLine(x, top + beam * 0.25f, x, top + beam * 1.3f, line(ORANGE, h * 0.0015f).apply { alpha = 150 })
        }
    }

    // ---- building blocks ---------------------------------------------------------------

    private fun sky(c: Canvas, w: Int, h: Int, horizon: Float, r: Random) {
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(0xFF000205.toInt(), 0xFF01080D.toInt(), 0xFF042431.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), horizon, p)
        // Thin glowing cloud streaks, like the Grid's light-sky.
        val streak = glow(CYAN, h * 0.002f, h * 0.012f)
        repeat(4 + r.nextInt(4)) {
            val y = horizon * (0.15f + r.nextFloat() * 0.7f)
            val x = r.nextFloat() * w
            val len = w * (0.15f + r.nextFloat() * 0.35f)
            streak.alpha = 20 + r.nextInt(40)
            c.drawLine(x - len / 2, y, x + len / 2, y, streak)
        }
    }

    private fun floor(c: Canvas, w: Int, h: Int, horizon: Float, lines: Boolean) {
        val p = Paint()
        p.shader = LinearGradient(
            0f, horizon, 0f, h.toFloat(),
            intArrayOf(0xFF021218.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), p)
        if (!lines) return
        val depth = h - horizon
        val vx = w / 2f
        val spacing = w / 9f
        for (i in -14..14) neon(c, vx, horizon, vx + i * spacing, h.toFloat(), CYAN, h * 0.0016f, 0.5f)
        for (k in 1..26) {
            val y = horizon + depth / k
            val near = ((y - horizon) / depth).coerceIn(0f, 1f)
            neon(c, 0f, y, w.toFloat(), y, CYAN, h * 0.0016f, 0.15f + 0.85f * near)
        }
    }

    private fun horizonGlow(c: Canvas, w: Int, h: Int, horizon: Float) {
        val band = h * 0.12f
        val p = Paint()
        p.shader = LinearGradient(
            0f, horizon - band, 0f, horizon + band * 0.5f,
            intArrayOf(Color.TRANSPARENT, 0x552BE6FF, 0xAA2BE6FF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.72f, 0.84f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, horizon - band, w.toFloat(), horizon + band * 0.5f, p)
        neon(c, 0f, horizon, w.toFloat(), horizon, ICE, h * 0.002f, 1f)
    }

    /** Jagged ridges in black, edged with light. */
    private fun mountains(c: Canvas, w: Int, horizon: Float, h: Int, r: Random, low: Boolean = false) {
        repeat(2) { layer ->
            val path = Path()
            path.moveTo(0f, horizon)
            var x = 0f
            val maxRise = h * (if (low) 0.08f else 0.2f) * (if (layer == 0) 0.6f else 1f)
            while (x < w) {
                x += w * (0.03f + r.nextFloat() * 0.07f)
                path.lineTo(x, horizon - maxRise * (0.2f + r.nextFloat() * 0.8f))
            }
            path.lineTo(w.toFloat(), horizon)
            path.close()
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (layer == 0) 0xFF021016.toInt() else 0xFF000408.toInt() })
            val edge = glow(if (layer == 0) CYAN else ICE, h * 0.0022f, h * 0.008f)
            edge.style = Paint.Style.STROKE
            edge.alpha = if (layer == 0) 90 else 200
            c.drawPath(path, edge)
            c.drawPath(path, line(ICE, h * 0.0012f).apply { style = Paint.Style.STROKE; alpha = if (layer == 0) 80 else 190 })
        }
    }

    /** Towers outlined in light, with window bands. */
    private fun city(c: Canvas, w: Int, horizon: Float, h: Int, r: Random) {
        var x = -w * 0.02f
        val body = Paint().apply { color = 0xFF01070B.toInt() }
        while (x < w) {
            val tw = w * (0.03f + r.nextFloat() * 0.06f)
            val th = h * (0.05f + r.nextFloat() * 0.33f) * (1f - 0.5f * kotlin.math.abs(x / w - 0.5f))
            val top = horizon - th
            c.drawRect(x, top, x + tw, horizon, body)
            neon(c, x, horizon, x, top, CYAN, h * 0.0015f, 0.8f)
            neon(c, x + tw, horizon, x + tw, top, CYAN, h * 0.0015f, 0.8f)
            neon(c, x, top, x + tw, top, ICE, h * 0.0018f, 1f)
            val bands = (th / (h * 0.03f)).toInt()
            for (b in 1 until bands) {
                if (r.nextInt(3) == 0) continue
                val y = top + b * h * 0.03f
                c.drawLine(x + tw * 0.15f, y, x + tw * 0.85f, y, line(CYAN, h * 0.0012f).apply { alpha = 60 + r.nextInt(80) })
            }
            x += tw + w * (0.004f + r.nextFloat() * 0.02f)
        }
    }

    /** A big identity disc: concentric rings and segmented arcs. */
    private fun disc(c: Canvas, w: Int, horizon: Float, h: Int, r: Random, accent: Int) {
        mountains(c, w, horizon, h, r, low = true)
        val cx = w * (0.35f + r.nextFloat() * 0.3f)
        val cy = horizon - h * 0.22f
        val radius = h * 0.17f
        c.drawCircle(cx, cy, radius * 1.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6000206.toInt() })
        for ((i, f) in listOf(1f, 0.82f, 0.55f, 0.2f).withIndex()) {
            val col = if (i == 1) accent else ICE
            val ring = glow(col, h * (if (i == 0) 0.006f else 0.004f), h * 0.02f).apply { style = Paint.Style.STROKE }
            c.drawCircle(cx, cy, radius * f, ring)
            c.drawCircle(cx, cy, radius * f, line(Color.WHITE, h * 0.0015f).apply { style = Paint.Style.STROKE; alpha = 200 })
        }
        // Segmented middle ring.
        val oval = RectF(cx - radius * 0.68f, cy - radius * 0.68f, cx + radius * 0.68f, cy + radius * 0.68f)
        val seg = glow(CYAN, h * 0.012f, h * 0.012f).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        val start = r.nextInt(360).toFloat()
        for (k in 0 until 8) c.drawArc(oval, start + k * 45f, 30f, false, seg)
    }

    /** A column of white light on the horizon, with rays. */
    private fun portal(c: Canvas, w: Int, horizon: Float, h: Int) {
        val cx = w / 2f
        val pw = h * 0.05f
        val ph = h * 0.32f
        val rays = Paint(Paint.ANTI_ALIAS_FLAG)
        for (k in 0 until 24) {
            val a = Math.toRadians((k * 15).toDouble())
            val len = h * 0.9f
            rays.shader = LinearGradient(
                cx, horizon - ph / 2, (cx + cos(a) * len).toFloat(), (horizon - ph / 2 + sin(a) * len).toFloat(),
                0x332BE6FF, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            rays.strokeWidth = h * 0.01f
            c.drawLine(cx, horizon - ph / 2, (cx + cos(a) * len).toFloat(), (horizon - ph / 2 + sin(a) * len).toFloat(), rays)
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, horizon - ph / 2, ph, intArrayOf(0x882BE6FF.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, horizon - ph / 2, ph, halo)
        c.drawRect(cx - pw / 2, horizon - ph, cx + pw / 2, horizon, glow(ICE, 0f, h * 0.03f).apply { style = Paint.Style.FILL })
        c.drawRect(cx - pw / 4, horizon - ph, cx + pw / 4, horizon, Paint().apply { color = Color.WHITE })
    }

    private fun portalReflection(c: Canvas, w: Int, h: Int, horizon: Float) {
        val cx = w / 2f
        val p = Paint().apply {
            shader = LinearGradient(0f, horizon, 0f, horizon + h * 0.3f, 0x66BFF7FF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(cx - h * 0.02f, horizon, cx + h * 0.02f, horizon + h * 0.3f, p)
    }

    /** Light cycles: trails that run straight, turn 90°, and run on, in perspective. */
    private fun cycles(c: Canvas, w: Int, h: Int, horizon: Float, r: Random) {
        val vx = w / 2f
        val depth = h - horizon
        fun project(x: Float, z: Float) = (vx + x * (w * 0.5f) / z) to (horizon + depth / z)
        val colors = listOf(CYAN, ORANGE, ICE).shuffled(r).take(2 + r.nextInt(2))
        for (col in colors) {
            val x0 = (r.nextFloat() - 0.5f) * 3f
            val x1 = x0 + (r.nextFloat() - 0.5f) * 3f
            val zTurn = 2f + r.nextFloat() * 4f
            val pts = listOf(project(x0, 18f), project(x0, zTurn), project(x1, zTurn), project(x1, 1.1f + r.nextFloat()))
            val path = Path().apply {
                moveTo(pts[0].first, pts[0].second)
                for (p in pts.drop(1)) lineTo(p.first, p.second)
            }
            c.drawPath(path, glow(col, h * 0.01f, h * 0.02f).apply { style = Paint.Style.STROKE; alpha = 170 })
            c.drawPath(path, line(Color.WHITE, h * 0.003f).apply { style = Paint.Style.STROKE; alpha = 220 })
            // The cycle: a bright wedge at the head of the trail.
            val (hx, hy) = pts.last()
            val size = h * 0.03f
            val bike = Path().apply {
                moveTo(hx - size, hy)
                lineTo(hx + size * 0.2f, hy - size * 0.7f)
                lineTo(hx + size, hy)
                close()
            }
            c.drawPath(bike, glow(col, 0f, h * 0.025f).apply { style = Paint.Style.FILL })
            c.drawPath(bike, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF02080C.toInt() })
            c.drawPath(bike, line(col, h * 0.003f).apply { style = Paint.Style.STROKE })
        }
    }

    private fun vignette(c: Canvas, w: Int, h: Int) {
        val p = Paint().apply {
            shader = RadialGradient(
                w / 2f, h * 0.5f, w * 0.75f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xCC000000.toInt()), floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    // ---- paints ----------------------------------------------------------------------------

    /** A neon line: soft wide glow under a thin bright core. */
    private fun neon(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, strength: Float) {
        val g = glow(color, width * 4f, width * 3f).apply { alpha = (90 * strength).toInt() }
        c.drawLine(x1, y1, x2, y2, g)
        c.drawLine(x1, y1, x2, y2, line(color, width).apply { alpha = (230 * strength).toInt() })
    }

    private fun glow(color: Int, width: Float, blur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }

    private fun line(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
    }
}
