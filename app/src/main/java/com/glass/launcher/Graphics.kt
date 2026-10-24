package com.glass.launcher

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.abs

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** A fresh drawable instance so the same image can sit in two views at once. */
fun Drawable.copy(res: Resources): Drawable = constantState?.newDrawable(res) ?: this

object Graphics {
    private const val FALLBACK = 0xFF3A3A3C.toInt()
    private const val BANNER_TOP = 0xFF2C2C2E.toInt()
    private const val BANNER_BOTTOM = 0xFF1C1C1E.toInt()

    /** Rounded tile background with a soft top-to-bottom sheen, like tvOS app icons. */
    fun tileBackground(entry: AppEntry, radius: Float): GradientDrawable {
        val colors = if (entry.banner != null) {
            intArrayOf(BANNER_TOP, BANNER_BOTTOM)
        } else {
            intArrayOf(
                ColorUtils.blendARGB(entry.color, Color.WHITE, 0.14f),
                ColorUtils.blendARGB(entry.color, Color.BLACK, 0.18f),
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radius
        }
    }

    /**
     * Picks a tile colour for an icon-only app. Samples the icon's own background first so the
     * icon blends into the tile; falls back to the icon's dominant colour.
     */
    fun tileColor(icon: Drawable?): Int {
        if (icon == null) return FALLBACK
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawInto(icon, Canvas(bmp), Rect(0, 0, size, size))

        val probes = listOf(0.12f to 0.5f, 0.88f to 0.5f, 0.5f to 0.12f, 0.5f to 0.88f)
        val samples = probes
            .map { (x, y) -> bmp.getPixel((x * size).toInt(), (y * size).toInt()) }
            .filter { Color.alpha(it) > 220 }
        if (samples.size >= 3 && samples.all { distance(it, samples[0]) < 48 }) {
            return Color.rgb(
                samples.sumOf { Color.red(it) } / samples.size,
                samples.sumOf { Color.green(it) } / samples.size,
                samples.sumOf { Color.blue(it) } / samples.size,
            )
        }
        val palette = Palette.from(bmp).maximumColorCount(12).generate()
        return palette.getDominantColor(FALLBACK)
    }

    /**
     * Full-screen Top Shelf artwork. Android apps don't publish tvOS-style shelf images, so this
     * uses the app's own banner (or icon), softly blurred so it reads as cinematic rather than
     * as an upscaled logo, with a gentle vignette.
     */
    fun shelfArt(entry: AppEntry): Bitmap {
        val w = 384
        val h = 216
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ColorUtils.blendARGB(entry.color or 0xFF000000.toInt(), Color.BLACK, 0.35f))
        val banner = entry.banner
        val icon = entry.icon
        if (banner != null) {
            drawInto(banner, canvas, Rect(0, 0, w, h))
        } else if (icon != null) {
            val s = (h * 0.7f).toInt()
            drawInto(icon, canvas, Rect((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2))
        }
        // Heavy blur: a smooth wash of the app's colours, not a fuzzy upscaled logo.
        val art = blur(bmp, radius = 8, passes = 3)
        val out = Canvas(art)
        // Dim bright banners (many are white) so white text and icons always read.
        out.drawColor(0x4D000000)
        // Darker top-left corner behind the title, like tvOS's Top Shelf.
        val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w * 0.7f, h * 0.55f,
                intArrayOf(0x99000000.toInt(), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        out.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
        return art
    }

    private fun drawInto(d: Drawable, canvas: Canvas, bounds: Rect) {
        val old = d.copyBounds()
        d.bounds = bounds
        d.draw(canvas)
        d.bounds = old
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** Repeated box blur (≈ gaussian). Only ever run on very small bitmaps. */
    private fun blur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxPass(a, b, w, h, radius, horizontal = true)
            boxPass(b, a, w, h, radius, horizontal = false)
        }
        // Mutable, so callers can draw on top (e.g. the Top Shelf vignette).
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(a, 0, w, 0, 0, w, h) }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + c] else src[c * w + line]
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = at(i)
                sa += p ushr 24; sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
            }
            for (i in 0 until len) {
                val out = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val pOut = at(i - r)
                val pIn = at(i + r + 1)
                sa += (pIn ushr 24) - (pOut ushr 24)
                sr += ((pIn shr 16) and 0xff) - ((pOut shr 16) and 0xff)
                sg += ((pIn shr 8) and 0xff) - ((pOut shr 8) and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }
}
