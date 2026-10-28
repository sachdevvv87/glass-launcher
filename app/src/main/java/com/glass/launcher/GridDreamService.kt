package com.glass.launcher

import android.app.Activity
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.KeyEvent

/** The Glass Grid screensaver, chosen in Settings → Device Preferences → Screen saver (or by Glass). */
class GridDreamService : DreamService() {

    private var show: GridShow? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream_grid)
        show = GridShow(findViewById(R.id.dream_root))
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        show?.start()
    }

    override fun onDreamingStopped() {
        show?.stop()
        show = null
        super.onDreamingStopped()
    }
}

/** "Preview the screensaver" in Glass Launcher Settings: the same slideshow; any button closes it. */
class ScreensaverPreviewActivity : Activity() {

    private var show: GridShow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dream_grid)
        show = GridShow(findViewById(R.id.dream_root)).also { it.start() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun onDestroy() {
        show?.stop()
        super.onDestroy()
    }
}
