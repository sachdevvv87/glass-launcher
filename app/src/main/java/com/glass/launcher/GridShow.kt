package com.glass.launcher

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The Tron slideshow behind the screensaver (and its preview): today's wallpapers, each held for
 * [HOLD_MS] with a slow drift, cross-fading to the next. The clock nudges a little with every
 * wallpaper so nothing burns into the panel. If nothing can be shown, the live Grid runs instead.
 */
class GridShow(private val root: View) {

    private val context = root.context
    private val wallpapers = Wallpapers(context)
    private val photos = arrayOf<ImageView>(root.findViewById(R.id.dream_photo_a), root.findViewById(R.id.dream_photo_b))
    private val grid: GridBackgroundView = root.findViewById(R.id.dream_grid)
    private val info: View = root.findViewById(R.id.dream_info)
    private val title: TextView = root.findViewById(R.id.dream_title)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var list: List<Wallpapers.Photo> = emptyList()
    private var index = 0
    private var front = 0
    private var running = false
    private var motion: Animator? = null
    private val next = Runnable { showNext() }

    init {
        // Orbitron lettering for the clock, to match the Grid.
        Themes.gridTypeface(context)?.let { face ->
            root.findViewById<TextClock>(R.id.dream_clock).typeface = face
            root.findViewById<TextClock>(R.id.dream_date).typeface = face
        }
    }

    fun start() {
        running = true
        io.execute {
            val found = wallpapers.today()
            main.post {
                if (!running) return@post
                list = found
                if (list.isEmpty()) {
                    grid.visibility = View.VISIBLE
                } else {
                    index = Random.nextInt(list.size)
                    showNext()
                }
            }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        motion?.cancel()
        io.shutdownNow()
    }

    private fun showNext() {
        if (!running || list.isEmpty()) return
        val photo = list[index % list.size]
        index++
        io.execute {
            val bmp = wallpapers.bitmap(photo, root.resources.displayMetrics.widthPixels)
            main.post {
                if (!running) return@post
                if (bmp == null) {
                    main.postDelayed(next, 1_000) // skip one that wouldn't load
                } else {
                    present(bmp, photo)
                    main.postDelayed(next, HOLD_MS)
                }
            }
        }
    }

    private fun present(bmp: Bitmap, photo: Wallpapers.Photo) {
        val incoming = photos[1 - front]
        val outgoing = photos[front]
        incoming.setImageBitmap(bmp)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.translationX = 0f
        incoming.translationY = 0f
        title.text = photo.title

        // Ken Burns: a slow push-in drifting in a random direction over the whole hold.
        val dx = Random.nextInt(-40, 41).toFloat()
        val dy = Random.nextInt(-24, 25).toFloat()
        motion?.cancel()
        motion = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(FADE_MS),
                ObjectAnimator.ofFloat(outgoing, View.ALPHA, outgoing.alpha, 0f).setDuration(FADE_MS),
                ObjectAnimator.ofFloat(incoming, View.SCALE_X, 1f, 1.1f).setDuration(HOLD_MS + FADE_MS),
                ObjectAnimator.ofFloat(incoming, View.SCALE_Y, 1f, 1.1f).setDuration(HOLD_MS + FADE_MS),
                ObjectAnimator.ofFloat(incoming, View.TRANSLATION_X, 0f, dx).setDuration(HOLD_MS + FADE_MS),
                ObjectAnimator.ofFloat(incoming, View.TRANSLATION_Y, 0f, dy).setDuration(HOLD_MS + FADE_MS),
            )
            interpolator = LinearInterpolator()
            start()
        }
        front = 1 - front

        // Burn-in protection: move the clock a few pixels each wallpaper.
        info.animate().translationX(Random.nextInt(0, 60).toFloat()).translationY(-Random.nextInt(0, 40).toFloat())
            .setDuration(FADE_MS).start()
    }

    companion object {
        private const val HOLD_MS = 40_000L
        private const val FADE_MS = 1_800L
    }
}
