package com.glass.launcher

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Position 0 is an empty, full-width header that reserves room for the Top Shelf.
 * Positions 1..n are app icons (items[position - 1]).
 */
class AppAdapter(
    private val listener: Listener,
    private val m: Metrics,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onTileFocused(entry: AppEntry, position: Int)
        fun onTileClicked(entry: AppEntry)
        fun onTileLongClicked(entry: AppEntry, index: Int)

        /** The D-pad direction that moved focus here, so the icon can tilt the same way. */
        fun lastDirection(): Int
    }

    val items = mutableListOf<AppEntry>()
    private var movingKey: String? = null

    init {
        setHasStableIds(true)
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AppEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from + 1, to + 1)
    }

    fun setMoving(key: String?) {
        val old = movingKey
        movingKey = key
        listOfNotNull(old, key).forEach { k ->
            val i = items.indexOfFirst { it.key == k }
            if (i >= 0) notifyItemChanged(i + 1, PAYLOAD_MOVING)
        }
    }

    override fun getItemCount() = items.size + 1

    override fun getItemViewType(position: Int) = if (position == 0) TYPE_HEADER else TYPE_TILE

    override fun getItemId(position: Int): Long =
        if (position == 0) Long.MIN_VALUE else items[position - 1].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val spacer = View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.headerH)
                isFocusable = false
            }
            return object : RecyclerView.ViewHolder(spacer) {}
        }
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is TileHolder) holder.bind(items[position - 1])
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is TileHolder && payloads.contains(PAYLOAD_MOVING)) {
            holder.setWiggle(items[position - 1].key == movingKey)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TileHolder) holder.setWiggle(false)
    }

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: FrameLayout = view.findViewById(R.id.tile)
        private val layer: View = view.findViewById(R.id.layer)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val specular: View = view.findViewById(R.id.specular)
        private val rim: View = view.findViewById(R.id.rim)
        private val label: TextView = view.findViewById(R.id.label)

        private val ctx = view.context
        private val lift = ctx.dp(26).toFloat()
        private val labelShift = ctx.dp(6).toFloat()
        private val specSize = (m.tileW * 1.2f).toInt()
        private val specRestX = m.tileW * 0.25f - specSize / 2f
        private val specRestY = -specSize / 2f + m.tileH * 0.05f
        private var tilt: Animator? = null
        private var wiggle: ObjectAnimator? = null
        private var entry: AppEntry? = null

        init {
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.rowHeight)
            view.setPadding(m.gap / 2, 0, m.gap / 2, 0)
            tile.layoutParams = tile.layoutParams.apply { height = m.tileH }
            icon.layoutParams = icon.layoutParams.apply {
                height = (m.tileH * 0.62f).toInt()
                width = height
            }
            // Slightly oversized so the parallax drift never shows an edge.
            layer.scaleX = 1.05f
            layer.scaleY = 1.05f

            specular.layoutParams = specular.layoutParams.apply {
                width = specSize
                height = specSize
            }
            specular.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setGradientType(GradientDrawable.RADIAL_GRADIENT)
                setGradientRadius(specSize / 2f)
                setColors(intArrayOf(0x4DFFFFFF, 0x00FFFFFF))
            }
            specular.translationX = specRestX
            specular.translationY = specRestY

            rim.background = GlassRimDrawable(m.radius, ctx.dp(2) * 0.75f)
            (label.layoutParams as LinearLayout.LayoutParams).topMargin = m.labelGap
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, m.labelSize)

            tile.cameraDistance = 9000 * ctx.resources.displayMetrics.density
            tile.clipToOutline = true

            view.setOnFocusChangeListener { _, hasFocus -> applyFocus(hasFocus, animate = true) }
            view.setOnClickListener { entry?.let(listener::onTileClicked) }
            view.setOnLongClickListener {
                val e = entry
                val index = bindingAdapterPosition - 1
                if (e != null && index >= 0) listener.onTileLongClicked(e, index)
                true
            }
        }

        fun bind(e: AppEntry) {
            entry = e
            label.text = e.label
            tile.background = Graphics.tileBackground(e, m.radius)
            val res = ctx.resources
            if (e.banner != null) {
                banner.setImageDrawable(e.banner.copy(res))
                banner.visibility = View.VISIBLE
                icon.visibility = View.GONE
            } else {
                icon.setImageDrawable(e.icon?.copy(res))
                icon.visibility = View.VISIBLE
                banner.visibility = View.GONE
            }
            applyFocus(itemView.isFocused, animate = false)
            setWiggle(e.key == movingKey)
        }

        private fun applyFocus(focused: Boolean, animate: Boolean) {
            val scale = if (focused) FOCUS_SCALE else 1f
            // Like tvOS, apps in the dock row show no name; rows below show it under the focused icon.
            val showLabel = focused && bindingAdapterPosition > Metrics.COLUMNS
            // Raise the whole cell so the enlarged icon draws over its neighbours.
            itemView.translationZ = if (focused) 1f else 0f
            tile.animate().cancel()
            label.animate().cancel()
            specular.animate().cancel()
            rim.animate().cancel()
            if (animate) {
                tile.animate()
                    .scaleX(scale).scaleY(scale)
                    .translationZ(if (focused) lift else 0f)
                    .setInterpolator(if (focused) OvershootInterpolator(1.1f) else DecelerateInterpolator(2f))
                    .setDuration(if (focused) 320 else 200)
                    .start()
                label.animate()
                    .alpha(if (showLabel) 1f else 0f)
                    .translationY(if (showLabel) 0f else -labelShift)
                    .setDuration(220)
                    .start()
                specular.animate().alpha(if (focused) 1f else 0f).setDuration(260).start()
                rim.animate().alpha(if (focused) 1f else 0.6f).setDuration(260).start()
            } else {
                tile.scaleX = scale
                tile.scaleY = scale
                tile.translationZ = if (focused) lift else 0f
                label.alpha = if (showLabel) 1f else 0f
                label.translationY = if (showLabel) 0f else -labelShift
                specular.alpha = if (focused) 1f else 0f
                rim.alpha = if (focused) 1f else 0.6f
            }

            if (focused && animate) {
                runTilt(listener.lastDirection())
                entry?.let { listener.onTileFocused(it, bindingAdapterPosition) }
            } else if (!focused) {
                tilt?.cancel()
                tile.rotationX = 0f
                tile.rotationY = 0f
                layer.translationX = 0f
                layer.translationY = 0f
                specular.translationX = specRestX
                specular.translationY = specRestY
            }
        }

        /**
         * tvOS icons lean toward the way your thumb moves on the Siri Remote. A D-pad remote has no
         * touch surface, so lean in the direction focus arrived from, then settle back.
         */
        private fun runTilt(direction: Int) {
            tilt?.cancel()
            val deg = 7f
            var rx = 0f
            var ry = 0f
            var sx = 0f
            var sy = 0f
            when (direction) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { ry = deg; sx = 1f }
                KeyEvent.KEYCODE_DPAD_LEFT -> { ry = -deg; sx = -1f }
                KeyEvent.KEYCODE_DPAD_DOWN -> { rx = -deg * 0.8f; sy = 1f }
                KeyEvent.KEYCODE_DPAD_UP -> { rx = deg * 0.8f; sy = -1f }
            }
            val shift = m.tileW * 0.02f
            fun swing(peak: Float) = arrayOf(
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.22f, peak),
                Keyframe.ofFloat(1f, 0f),
            )
            val tileAnim = ObjectAnimator.ofPropertyValuesHolder(
                tile,
                PropertyValuesHolder.ofKeyframe(View.ROTATION_X, *swing(rx)),
                PropertyValuesHolder.ofKeyframe(View.ROTATION_Y, *swing(ry)),
            )
            val layerAnim = ObjectAnimator.ofPropertyValuesHolder(
                layer,
                PropertyValuesHolder.ofKeyframe(View.TRANSLATION_X, *swing(-sx * shift)),
                PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y, *swing(-sy * shift)),
            )
            val specAnim = ObjectAnimator.ofPropertyValuesHolder(
                specular,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, specRestX - sx * m.tileW * 0.5f, specRestX),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, specRestY - sy * m.tileH * 0.6f, specRestY),
            )
            tilt = AnimatorSet().apply {
                playTogether(tileAnim, layerAnim, specAnim)
                duration = 850
                interpolator = DecelerateInterpolator(1.5f)
                start()
            }
        }

        fun setWiggle(on: Boolean) {
            if (on && wiggle == null) {
                wiggle = ObjectAnimator.ofFloat(tile, View.ROTATION, -1.6f, 1.6f).apply {
                    duration = 120
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            } else if (!on) {
                wiggle?.cancel()
                wiggle = null
                tile.rotation = 0f
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TILE = 1
        private const val PAYLOAD_MOVING = "moving"
        private const val FOCUS_SCALE = 1.2f
    }
}
