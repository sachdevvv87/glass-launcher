package com.glass.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.recyclerview.widget.RecyclerView

/**
 * The panel the top row of apps sits on: a frosted Liquid Glass dock, or in the Grid theme a dark
 * Tron panel edged with a cyan light-line and corner brackets.
 */
class DockDecoration(context: Context, private val m: Metrics, private val theme: GlassTheme) :
    RecyclerView.ItemDecoration() {

    private val grid = theme == GlassTheme.GRID
    private val density = context.resources.displayMetrics.density
    private val panel = GradientDrawable().apply {
        cornerRadius = if (grid) m.dockRadius * 0.4f else m.dockRadius
        setColor(if (grid) 0xB3000A12.toInt() else 0x2EFFFFFF)
    }
    private val rim = GlassRimDrawable(if (grid) m.dockRadius * 0.4f else m.dockRadius, 1.5f * density, neon = grid)
    private val glow = GradientDrawable().apply {
        cornerRadius = m.dockRadius * 0.4f
        setColor(0x00000000)
        setStroke((6 * density).toInt(), 0x332BE6FF)
    }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Themes.NEON
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val first = parent.layoutManager?.findViewByPosition(1) ?: return
        // Layout position (not translation) so the dock stays still while icons animate.
        val left = m.side - m.dockInsetX
        val right = parent.width - m.side + m.dockInsetX
        val top = first.top - m.dockPadTop
        val bottom = first.top + m.tileH + m.dockPadBottom
        if (grid) {
            glow.setBounds(left, top, right, bottom)
            glow.draw(c)
        }
        panel.setBounds(left, top, right, bottom)
        panel.draw(c)
        rim.setBounds(left, top, right, bottom)
        rim.draw(c)
        if (grid) drawBrackets(c, left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** Short bright L-shapes at the four corners, like Tron interface frames. */
    private fun drawBrackets(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val len = 22f * density
        val o = 5f * density
        c.drawLine(l - o, t - o, l - o + len, t - o, bracket)
        c.drawLine(l - o, t - o, l - o, t - o + len, bracket)
        c.drawLine(r + o, t - o, r + o - len, t - o, bracket)
        c.drawLine(r + o, t - o, r + o, t - o + len, bracket)
        c.drawLine(l - o, b + o, l - o + len, b + o, bracket)
        c.drawLine(l - o, b + o, l - o, b + o - len, bracket)
        c.drawLine(r + o, b + o, r + o - len, b + o, bracket)
        c.drawLine(r + o, b + o, r + o, b + o - len, bracket)
    }
}
