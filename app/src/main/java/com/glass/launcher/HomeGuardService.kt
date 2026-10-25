package com.glass.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps Glass as the Home screen even on TVs that hide the default-Home setting (Google TV):
 *  - catches the remote's Home button and opens Glass instead of the stock launcher;
 *  - if the stock launcher still comes to the front (after boot, from a shortcut, from an app),
 *    Glass immediately replaces it.
 */
class HomeGuardService : AccessibilityService() {

    private var lastLaunch = 0L
    private var cachedDefaultHome: String? = null
    private var cachedAt = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) bringGlassHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in Debloat.STOCK_LAUNCHERS || pkg == defaultHome()) bringGlassHome()
    }

    override fun onInterrupt() = Unit

    /** Runs when the guard starts, including right after Glass is updated: repair the default Home. */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Thread { TvShell.ensureDefaultHome(applicationContext) }.start()
    }

    private fun bringGlassHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < 400) return
        lastLaunch = now
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Nothing sensible to do; the next Home press will try again.
        }
    }

    /** Whatever other app the system currently treats as Home (vendor launchers included). */
    private fun defaultHome(): String? {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt > 10_000) {
            val info = packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            val pkg = info?.activityInfo?.packageName
            // "android" is the "choose an app" dialog, not a launcher.
            cachedDefaultHome = pkg?.takeIf { it != packageName && it != "android" }
            cachedAt = now
        }
        return cachedDefaultHome
    }

    companion object {
        fun component(context: Context) = ComponentName(context, HomeGuardService::class.java)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = component(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
