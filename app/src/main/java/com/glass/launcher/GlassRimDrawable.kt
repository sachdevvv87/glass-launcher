package com.glass.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The thin Liquid Glass edge: bright along the top where light catches it, faint below.
 * With [neon] it becomes a Tron light-line instead: an even cyan edge.
 */
class GlassRimDrawable(
    private val radius: Float,
    private val stroke: Float,
    private val neon: Boolean = false,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(stroke / 2f, stroke / 2f)
        val colors = if (neon) {
            intArrayOf(Themes.NEON, 0xCC2BE6FF.toInt(), Themes.NEON)
        } else {
            intArrayOf(0x99FFFFFF.toInt(), 0x14FFFFFF, 0x38FFFFFF)
        }
        paint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            colors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
