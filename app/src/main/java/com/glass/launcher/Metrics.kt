package com.glass.launcher

/**
 * Screen geometry as fractions of the display, measured from the tvOS 26 Home screen
 * (1920x1080), so the same proportions hold on any TV resolution.
 */
class Metrics(width: Int, height: Int) {
    val screenW = width
    val screenH = height

    /** tvOS keeps ~90px of a 1920px screen clear on each side. */
    val side = (width * 0.047f).toInt()
    val gap = (width * 0.025f).toInt()

    /** Six 5:3 icons per row. */
    val tileW = (width - 2 * side - (COLUMNS - 1) * gap) / COLUMNS
    val tileH = tileW * 3 / 5
    val radius = tileH * 0.11f

    /** Rows repeat every ~21% of the screen height. */
    val rowGap = (height * 0.073f).toInt()
    val rowHeight = tileH + rowGap
    val labelGap = (height * 0.022f).toInt()
    val labelSize = height * 0.023f

    /** The top row's icons start 76% down, so the next row only just peeks in at the bottom. */
    val headerH = (height * 0.762f).toInt()

    /** Inside the grid, the focused row settles here, leaving the row above in view. */
    val anchorTop = (height * 0.30f).toInt()

    /** Liquid Glass dock behind the top row. */
    val dockInsetX = (width * 0.025f).toInt()
    val dockPadTop = (height * 0.037f).toInt()
    val dockPadBottom = (height * 0.036f).toInt()
    val dockRadius = height * 0.035f

    /** Top Shelf title (top left) and the clock + profile pill (top right). */
    val titleTop = (height * 0.06f).toInt()
    val titleSize = height * 0.075f
    val pillTop = (height * 0.05f).toInt()
    val pillEnd = (width * 0.028f).toInt()
    val pillHeight = (height * 0.05f).toInt()
    val pillTextSize = height * 0.022f
    val avatarSize = (height * 0.04f).toInt()

    val menuWidth = (width * 0.22f).toInt()

    companion object {
        const val COLUMNS = 6
    }
}
