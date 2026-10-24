package com.glass.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

/** Finds installed apps and remembers the user's layout (order + hidden apps). */
class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("glass_layout", Context.MODE_PRIVATE)

    /** Runs on a background thread. */
    fun load(): List<AppEntry> {
        val apps = LinkedHashMap<String, AppEntry>()
        // TV apps first so their banner-capable activity wins over a phone-style one.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, apps)
        collect(Intent.CATEGORY_LAUNCHER, apps)
        apps[SETTINGS_KEY] = settingsEntry()
        hiddenKeys().forEach { apps.remove(it) }

        val ordered = ArrayList<AppEntry>(apps.size)
        val saved = prefs.getString(KEY_ORDER, null)?.split(',')?.filter { it.isNotEmpty() }
        if (saved != null) {
            saved.forEach { key -> apps.remove(key)?.let(ordered::add) }
            // Newly installed apps go to the end, like on tvOS.
            ordered.addAll(apps.values.sortedBy { it.label.lowercase() })
        } else {
            PRIORITY.forEach { key -> apps.remove(key)?.let(ordered::add) }
            val settings = apps.remove(SETTINGS_KEY)
            ordered.addAll(apps.values.sortedBy { it.label.lowercase() })
            settings?.let(ordered::add)
        }
        return ordered
    }

    fun saveOrder(keys: List<String>) {
        prefs.edit().putString(KEY_ORDER, keys.joinToString(",")).apply()
    }

    fun hide(key: String) {
        if (key == SETTINGS_KEY) return
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenKeys() + key).apply()
    }

    fun hiddenCount(): Int = hiddenKeys().size

    fun clearHidden() {
        prefs.edit().remove(KEY_HIDDEN).apply()
    }

    fun resetLayout() {
        prefs.edit().remove(KEY_ORDER).apply()
    }

    fun setupShown(): Boolean = prefs.getBoolean(KEY_SETUP_SHOWN, false)

    fun markSetupShown() {
        prefs.edit().putBoolean(KEY_SETUP_SHOWN, true).apply()
    }

    private fun hiddenKeys(): Set<String> = prefs.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()

    @Suppress("DEPRECATION")
    private fun collect(category: String, into: MutableMap<String, AppEntry>) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        for (info in pm.queryIntentActivities(intent, 0)) {
            val activity = info.activityInfo ?: continue
            val pkg = activity.packageName
            if (pkg == context.packageName || into.containsKey(pkg)) continue
            try {
                into[pkg] = buildEntry(info, activity)
            } catch (e: Exception) {
                // A broken app shouldn't take the whole Home screen down with it.
            }
        }
    }

    private fun buildEntry(info: ResolveInfo, activity: ActivityInfo): AppEntry {
        val banner: Drawable? = activity.loadBanner(pm)
        val icon = loadHiResIcon(activity)
        val color = Graphics.tileColor(icon)
        return AppEntry(
            key = activity.packageName,
            label = info.loadLabel(pm).toString(),
            component = ComponentName(activity.packageName, activity.name),
            banner = banner,
            icon = icon,
            color = color,
        )
    }

    /** Tiles are much bigger than phone icons, so ask for the sharpest density available. */
    private fun loadHiResIcon(activity: ActivityInfo): Drawable? {
        val id = activity.iconResource
        if (id != 0) {
            try {
                val res = pm.getResourcesForApplication(activity.applicationInfo)
                val d = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
                if (d != null) return d
            } catch (e: Exception) {
                // fall through to the default icon
            }
        }
        return activity.loadIcon(pm)
    }

    private fun settingsEntry(): AppEntry {
        val icon = context.getDrawable(R.drawable.ic_settings)
        // tvOS Settings: dark gear on a light grey tile.
        val color = 0xFFD6D6DB.toInt()
        return AppEntry(
            key = SETTINGS_KEY,
            label = context.getString(R.string.settings),
            component = null,
            banner = null,
            icon = icon,
            color = color,
        )
    }

    companion object {
        const val SETTINGS_KEY = "__settings__"
        private const val KEY_ORDER = "order"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_SETUP_SHOWN = "setup_shown"

        /** Default top row on first launch, when these apps are installed. */
        private val PRIORITY = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "in.startv.hotstar.dplus.tv",
            "com.jio.media.stb.ondemand",
            "com.spotify.tv.android",
            "com.plexapp.android",
            "com.google.android.youtube.tvmusic",
        )
    }
}
