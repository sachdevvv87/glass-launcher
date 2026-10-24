package com.glass.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import androidx.recyclerview.widget.RecyclerView

/** The rounded Liquid Glass dock that the top row of apps sits on. */
class DockDecoration(context: Context, private val m: Metrics) : RecyclerView.ItemDecoration() {

    private val panel = GradientDrawable().apply {
        cornerRadius = m.dockRadius
        setColor(0x2EFFFFFF)
    }
    private val rim = GlassRimDrawable(m.dockRadius, context.dp(2) * 0.75f)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val first = parent.layoutManager?.findViewByPosition(1) ?: return
        // Layout position (not translation) so the dock stays still while icons animate.
        val left = m.side - m.dockInsetX
        val right = parent.width - m.side + m.dockInsetX
        val top = first.top - m.dockPadTop
        val bottom = first.top + m.tileH + m.dockPadBottom
        panel.setBounds(left, top, right, bottom)
        panel.draw(c)
        rim.setBounds(left, top, right, bottom)
        rim.draw(c)
    }
}
