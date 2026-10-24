package com.glass.launcher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity(), AppAdapter.Listener {

    private lateinit var m: Metrics
    private lateinit var repo: AppRepository
    private lateinit var content: View
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: AppAdapter
    private lateinit var arts: Array<ImageView>
    private lateinit var shelfTitle: TextView
    private lateinit var shelfFocus: View
    private lateinit var shelfDots: LinearLayout
    private lateinit var shelfCredit: View
    private lateinit var timePill: View
    private lateinit var shelfContent: ShelfContent
    private lateinit var gridShade: View
    private lateinit var moveHint: View
    private lateinit var menuOverlay: View
    private lateinit var menuPanel: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val ease = DecelerateInterpolator(2f)
    /** Which show each streaming app's Top Shelf is currently on. */
    private val showIndex = HashMap<String, Int>()
    private val rotateShelf = Runnable { advanceShow(1) }
    private val artCache = LruCache<String, Bitmap>(8)
    private val artFades = arrayOfNulls<Animator>(2)
    private var frontArt = 0
    private var shownArtKey: String? = null
    private var loadGeneration = 0
    private var focusedKey: String? = null
    private var movingIndex = -1
    private var pendingFocus = -1
    private var lastDirection = 0
    private var shelfIndex = 0
    private var menuOpen = false
    private var menuReturnPosition = -1
    private val menuItems = mutableListOf<View>()

    private var pendingArt: AppEntry? = null
    private val artTask = Runnable { pendingArt?.let(::showArt) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = AppRepository(this)
        val dm = resources.displayMetrics
        m = Metrics(dm.widthPixels, dm.heightPixels)

        content = findViewById(R.id.content)
        grid = findViewById(R.id.grid)
        arts = arrayOf(findViewById(R.id.art_a), findViewById(R.id.art_b))
        shelfTitle = findViewById(R.id.shelf_title)
        shelfFocus = findViewById(R.id.shelf_focus)
        shelfDots = findViewById(R.id.shelf_dots)
        shelfCredit = findViewById(R.id.shelf_credit)
        timePill = findViewById(R.id.time_pill)
        shelfContent = ShelfContent(this)
        gridShade = findViewById(R.id.grid_shade)
        moveHint = findViewById(R.id.move_hint)
        menuOverlay = findViewById(R.id.menu_overlay)
        menuPanel = findViewById(R.id.menu_panel)

        val gridSide = m.side - m.gap / 2
        grid.setPadding(gridSide, 0, gridSide, m.rowGap)
        setUpShelf()
        menuPanel.layoutParams = menuPanel.layoutParams.apply { width = m.menuWidth }

        adapter = AppAdapter(this, m)
        layoutManager = object : GridLayoutManager(this@MainActivity, Metrics.COLUMNS) {
            // Scrolling is driven by alignRow() so rows settle where tvOS puts them,
            // instead of RecyclerView's default "scroll just enough".
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
            ) = false
        }
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) Metrics.COLUMNS else 1
        }
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.addItemDecoration(DockDecoration(this, m))
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = syncShelf()
        })
        grid.viewTreeObserver.addOnGlobalLayoutListener {
            applyPendingFocus()
            syncShelf()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        reload()

        // First launch: offer the setup screen (lock as launcher, debloat).
        if (!repo.setupShown()) {
            repo.markSetupShown()
            main.postDelayed({ startActivity(Intent(this, SetupActivity::class.java)) }, 900)
        }
    }

    override fun onResume() {
        super.onResume()
        // Fetch/refresh streaming artwork (cached; re-downloads at most every 12 hours).
        net.execute {
            shelfContent.refresh()
            main.post { if (!isDestroyed) refreshShelf() }
        }
    }

    override fun onPause() {
        main.removeCallbacks(rotateShelf)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    /** Pressing Home while already on Home: jump back to the first app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeMenu()
        endMove()
        goHome()
    }

    private fun setUpShelf() {
        // Padding gives the text shadow room, so it isn't clipped into a visible box.
        val pad = dp(24)
        shelfTitle.setPadding(pad, pad, pad, pad)
        shelfTitle.layoutParams = (shelfTitle.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = m.titleTop - pad
            leftMargin = m.side - pad
        }
        shelfTitle.maxWidth = (m.screenW * 0.6f).toInt()
        shelfTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, m.titleSize)

        timePill.layoutParams = (timePill.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = m.pillTop
            rightMargin = m.pillEnd
            height = m.pillHeight
        }
        val inset = (m.pillHeight - m.avatarSize) / 2
        timePill.setPadding(m.pillHeight / 2, 0, inset, 0)
        findViewById<TextClock>(R.id.clock).setTextSize(TypedValue.COMPLEX_UNIT_PX, m.pillTextSize)
        findViewById<View>(R.id.avatar).layoutParams =
            (findViewById<View>(R.id.avatar).layoutParams as LinearLayout.LayoutParams).apply {
                width = m.avatarSize
                height = m.avatarSize
                leftMargin = m.pillHeight / 4
            }

        shelfFocus.layoutParams = shelfFocus.layoutParams.apply { height = (m.headerH * 0.6f).toInt() }
        shelfFocus.setOnFocusChangeListener { _, focused -> setShelfMode(focused) }
        shelfFocus.setOnClickListener { adapter.items.getOrNull(shelfIndex)?.let(::onTileClicked) }
    }

    // ---- loading -------------------------------------------------------------------------

    private fun reload() {
        val generation = ++loadGeneration
        io.execute {
            val apps = repo.load()
            main.post {
                if (generation == loadGeneration && !isDestroyed) onAppsLoaded(apps)
            }
        }
    }

    private fun onAppsLoaded(apps: List<AppEntry>) {
        if (movingIndex >= 0) {
            // An app was installed/removed mid-move: keep what the user did, then refresh.
            endMove()
            reload()
            return
        }
        artCache.evictAll()
        shownArtKey = null
        adapter.submit(apps)
        buildDots()
        if (apps.isEmpty()) return
        val index = apps.indexOfFirst { it.key == focusedKey }.let { if (it < 0) 0 else it }
        focusPosition(index + 1)
    }

    // ---- focus and Top Shelf -------------------------------------------------------------

    override fun lastDirection() = lastDirection

    override fun onTileFocused(entry: AppEntry, position: Int) {
        focusedKey = entry.key
        main.removeCallbacks(artTask)
        main.removeCallbacks(rotateShelf)
        if (position in 1..Metrics.COLUMNS) {
            showShelf(position - 1)
        } else {
            // Deeper in the grid the artwork just tints the background; don't thrash while scrolling.
            pendingArt = entry
            main.postDelayed(artTask, 150)
        }
        if (movingIndex < 0) layoutManager.findViewByPosition(position)?.let { alignRow(it, position) }
    }

    /** Dock row: the Top Shelf is fully visible. Further down, the focused row settles at [Metrics.anchorTop]. */
    private fun alignRow(view: View, position: Int) {
        val row = (position - 1) / Metrics.COLUMNS
        val target = if (row == 0) m.headerH else m.anchorTop
        val dy = view.top - target
        if (dy != 0) grid.smoothScrollBy(0, dy, ease, 420)
    }

    /**
     * Shows a dock app's Top Shelf. Streaming apps get a real show backdrop and title (from TVmaze),
     * cycling through their current shows; other apps get their own artwork and name.
     */
    private fun showShelf(index: Int) {
        val entry = adapter.items.getOrNull(index) ?: return
        shelfIndex = index
        main.removeCallbacks(rotateShelf)
        val shows = shelfContent.itemsFor(entry.key)
        if (shows.isEmpty()) {
            shelfTitle.text = entry.label
            shelfCredit.visibility = View.GONE
            showArt(entry)
        } else {
            val show = shows[(showIndex[entry.key] ?: 0) % shows.size]
            shelfTitle.text = show.title
            shelfCredit.visibility = View.VISIBLE
            // App artwork first; the show's backdrop fades in once loaded (instant when cached).
            if (shownArtKey?.startsWith("${entry.key}#") != true) showArt(entry)
            val key = "${entry.key}#${show.showId}"
            net.execute {
                val bmp = shelfContent.bitmap(show, m.screenW)
                main.post {
                    val stillHere = adapter.items.getOrNull(shelfIndex)?.key == entry.key
                    if (bmp != null && stillHere && !isDestroyed) crossfadeArt(bmp, key)
                }
            }
            if (shows.size > 1) main.postDelayed(rotateShelf, ROTATE_MS)
        }
        buildDots()
    }

    /** Next/previous show in the focused streaming app's Top Shelf. */
    private fun advanceShow(delta: Int): Boolean {
        val entry = adapter.items.getOrNull(shelfIndex) ?: return false
        val count = shelfContent.itemsFor(entry.key).size
        if (count < 2) return false
        showIndex[entry.key] = ((showIndex[entry.key] ?: 0) + delta + count) % count
        showShelf(shelfIndex)
        return true
    }

    /** Re-applies the Top Shelf after artwork has been (re)loaded or switched off. */
    private fun refreshShelf() {
        val position = focusedTile()?.second?.plus(1) ?: if (shelfFocus.isFocused) shelfIndex + 1 else -1
        if (position in 1..Metrics.COLUMNS) {
            shownArtKey = null
            showShelf(position - 1)
        }
    }

    private fun showArt(entry: AppEntry) {
        if (entry.key == shownArtKey) return
        val art = artCache.get(entry.key) ?: Graphics.shelfArt(entry).also { artCache.put(entry.key, it) }
        crossfadeArt(art, entry.key)
    }

    private fun crossfadeArt(art: Bitmap, key: String) {
        if (key == shownArtKey) return
        shownArtKey = key
        val incoming = 1 - frontArt
        val inView = arts[incoming]
        val outView = arts[frontArt]
        artFades.forEach { it?.cancel() }
        inView.animate().cancel()
        inView.setImageBitmap(art)
        inView.scaleX = 1f
        inView.scaleY = 1f
        artFades[incoming] = ObjectAnimator.ofFloat(inView, View.ALPHA, 1f).setDuration(500).also { it.start() }
        artFades[frontArt] = ObjectAnimator.ofFloat(outView, View.ALPHA, 0f).setDuration(500).also { it.start() }
        // Slow push-in on the artwork, like the moving Top Shelf on tvOS.
        inView.animate().scaleX(1.08f).scaleY(1.08f)
            .setInterpolator(LinearInterpolator()).setDuration(16_000).start()
        frontArt = incoming
    }

    /** Title, clock and the shelf itself follow the header spacer as the grid scrolls. */
    private fun syncShelf() {
        val header = layoutManager.findViewByPosition(0)
        val visible = if (header == null) {
            0f
        } else {
            val h = header.height.coerceAtLeast(1)
            ((header.bottom - h * 0.55f) / (h * 0.45f)).coerceIn(0f, 1f)
        }
        shelfTitle.translationY = (header?.top ?: 0).toFloat()
        shelfTitle.alpha = visible
        shelfCredit.alpha = visible
        timePill.alpha = visible
        shelfFocus.isFocusable = visible > 0.9f
        gridShade.alpha = 1f - visible
    }

    /** Up from the dock "enters" the Top Shelf: the apps slide away to reveal the artwork. */
    private fun setShelfMode(on: Boolean) {
        grid.animate()
            .translationY(if (on) m.screenH * 0.3f else 0f)
            .alpha(if (on) 0f else 1f)
            .setInterpolator(ease).setDuration(380).start()
        shelfDots.animate().alpha(if (on) 1f else 0f).setDuration(300).start()
    }

    /** Page dots: one per show for streaming apps, otherwise one per dock app. */
    private fun buildDots() {
        val entry = adapter.items.getOrNull(shelfIndex)
        val shows = entry?.let { shelfContent.itemsFor(it.key) }.orEmpty()
        val count: Int
        val current: Int
        if (entry != null && shows.size > 1) {
            count = shows.size
            current = (showIndex[entry.key] ?: 0) % count
        } else {
            count = minOf(Metrics.COLUMNS, adapter.items.size)
            current = shelfIndex
        }
        val size = dp(8)
        if (shelfDots.childCount != count) {
            shelfDots.removeAllViews()
            repeat(count) {
                shelfDots.addView(View(this).apply {
                    setBackgroundResource(R.drawable.dot)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        leftMargin = size / 2
                        rightMargin = size / 2
                    }
                })
            }
        }
        for (i in 0 until shelfDots.childCount) {
            shelfDots.getChildAt(i).alpha = if (i == current) 1f else 0.35f
        }
    }

    private fun focusPosition(position: Int) {
        layoutManager.scrollToPosition(if (position <= Metrics.COLUMNS) 0 else position)
        requestFocusAt(position)
    }

    private fun goHome() {
        if (adapter.items.isEmpty()) return
        layoutManager.scrollToPositionWithOffset(0, 0)
        requestFocusAt(1)
    }

    /** Focus an icon once it has actually been laid out (right after a data change it may not be yet). */
    private fun requestFocusAt(position: Int) {
        pendingFocus = position
        grid.post { applyPendingFocus() }
    }

    private fun applyPendingFocus() {
        if (pendingFocus < 0) return
        val position = pendingFocus
        val view = layoutManager.findViewByPosition(position) ?: return
        pendingFocus = -1
        view.requestFocus()
        alignRow(view, position)
    }

    private fun focusedTile(): Pair<AppEntry, Int>? {
        val child = grid.focusedChild ?: return null
        val position = grid.getChildAdapterPosition(child)
        val entry = adapter.items.getOrNull(position - 1) ?: return null
        return entry to position - 1
    }

    // ---- actions -------------------------------------------------------------------------

    override fun onTileClicked(entry: AppEntry) {
        if (movingIndex >= 0) return
        val component = entry.component
        if (entry.isSettings || component == null) {
            safeStart(Intent(Settings.ACTION_SETTINGS))
        } else {
            safeStart(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        }
    }

    override fun onTileLongClicked(entry: AppEntry, index: Int) = openMenu(entry, index)

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- glass context menu --------------------------------------------------------------

    private fun openMenu(entry: AppEntry, index: Int) {
        if (menuOpen || movingIndex >= 0) return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.action_edit) to { startMove(index) }
        if (entry.isSettings) {
            actions += getString(R.string.action_glass_settings) to {
                startActivity(Intent(this, SetupActivity::class.java))
            }
            actions += getString(R.string.action_show_hidden, repo.hiddenCount()) to { repo.clearHidden(); reload() }
            actions += getString(R.string.action_reset) to { repo.resetLayout(); reload() }
        } else {
            val pkg = Uri.parse("package:${entry.key}")
            actions += getString(R.string.action_info) to {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }
            actions += getString(R.string.action_hide) to { repo.hide(entry.key); reload() }
            actions += getString(R.string.action_uninstall) to { safeStart(Intent(Intent.ACTION_DELETE, pkg)) }
        }

        menuPanel.removeAllViews()
        menuItems.clear()
        val title = layoutInflater.inflate(R.layout.menu_title, menuPanel, false) as TextView
        title.text = entry.label
        menuPanel.addView(title)
        for ((label, action) in actions) {
            val item = layoutInflater.inflate(R.layout.menu_item, menuPanel, false) as TextView
            item.text = label
            item.setOnClickListener {
                closeMenu()
                action()
            }
            menuPanel.addView(item)
            menuItems += item
        }

        menuReturnPosition = index + 1
        menuOpen = true
        menuOverlay.visibility = View.VISIBLE
        positionMenu(layoutManager.findViewByPosition(index + 1)?.findViewById(R.id.tile))
        menuOverlay.alpha = 0f
        menuOverlay.animate().alpha(1f).setDuration(180).start()
        menuPanel.scaleX = 0.9f
        menuPanel.scaleY = 0.9f
        menuPanel.animate().scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.1f)).setDuration(240).start()
        if (Build.VERSION.SDK_INT >= 31) {
            val r = dp(18).toFloat()
            content.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        }
        menuItems.firstOrNull()?.requestFocus()
    }

    /** Place the menu beside the icon, like tvOS, flipping to the left near the right edge. */
    private fun positionMenu(anchor: View?) {
        val pw = m.menuWidth
        menuPanel.measure(
            View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val ph = menuPanel.measuredHeight
        val margin = dp(20)
        var x = (m.screenW - pw) / 2f
        var y = (m.screenH - ph) / 2f
        var fromLeft = true
        if (anchor != null) {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            x = loc[0] + anchor.width * anchor.scaleX + margin
            if (x + pw > m.screenW - m.side / 2f) {
                x = (loc[0] - pw - margin).toFloat()
                fromLeft = false
            }
            y = loc[1].toFloat()
        }
        val minY = m.side / 2f
        y = y.coerceIn(minY, (m.screenH - ph - minY).coerceAtLeast(minY))
        menuPanel.translationX = x
        menuPanel.translationY = y
        menuPanel.pivotX = if (fromLeft) 0f else pw.toFloat()
        menuPanel.pivotY = 0f
    }

    private fun stepMenu(delta: Int) {
        if (menuItems.isEmpty()) return
        val current = menuItems.indexOfFirst { it.isFocused }.coerceAtLeast(0)
        menuItems[(current + delta).coerceIn(0, menuItems.size - 1)].requestFocus()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        menuOverlay.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= 31) content.setRenderEffect(null)
        if (menuReturnPosition > 0) requestFocusAt(menuReturnPosition)
    }

    // ---- edit ("jiggle") mode ------------------------------------------------------------

    private fun startMove(index: Int) {
        if (index !in adapter.items.indices) return
        movingIndex = index
        adapter.setMoving(adapter.items[index].key)
        moveHint.visibility = View.VISIBLE
        focusPosition(index + 1)
    }

    private fun moveBy(delta: Int) {
        val target = movingIndex + delta
        if (target !in adapter.items.indices) return
        adapter.move(movingIndex, target)
        movingIndex = target
        requestFocusAt(target + 1)
    }

    private fun endMove() {
        if (movingIndex < 0) return
        movingIndex = -1
        adapter.setMoving(null)
        moveHint.visibility = View.GONE
        repo.saveOrder(adapter.items.map { it.key })
    }

    // ---- keys ----------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode
        if (down && code in DIRECTIONS) lastDirection = code

        if (menuOpen) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> {
                    if (!down) closeMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) stepMenu(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) stepMenu(1); return true }
            }
            return super.dispatchKeyEvent(event)
        }

        if (movingIndex >= 0) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) moveBy(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) moveBy(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveBy(-Metrics.COLUMNS); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveBy(Metrics.COLUMNS); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (!down) endMove()
                    return true
                }
            }
        }

        // In the Top Shelf: left/right page through a streaming app's shows (or the dock apps);
        // down or Back returns to the dock.
        if (shelfFocus.isFocused) {
            val count = minOf(Metrics.COLUMNS, adapter.items.size)
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> {
                    if (!down) requestFocusAt(shelfIndex + 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down && !advanceShow(-1) && count > 0) showShelf((shelfIndex - 1 + count) % count)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && !advanceShow(1) && count > 0) showShelf((shelfIndex + 1) % count)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return true
            }
        }

        // Up from the dock enters the Top Shelf. RecyclerView would otherwise swallow it
        // (it "finds" the unfocusable header above the dock and keeps focus where it is).
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            val dockIndex = focusedTile()?.second
            if (dockIndex != null && dockIndex < Metrics.COLUMNS && shelfFocus.isFocusable) {
                if (down) {
                    shelfIndex = dockIndex
                    shelfFocus.requestFocus()
                }
                return true
            }
        }

        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) focusedTile()?.let { (entry, index) -> openMenu(entry, index) }
            return true
        }
        // A launcher has nowhere to go "back" to: return to the top instead.
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) goHome()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** How long each show stays in a streaming app's Top Shelf before the next one. */
        private const val ROTATE_MS = 9_000L
        private val DIRECTIONS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
