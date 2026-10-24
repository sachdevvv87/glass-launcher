package com.glass.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Which preinstalled apps are offered for removal, and which are never touched. */
object Debloat {

    class Candidate(val pkg: String, val label: String, val note: String)

    /** Stock Home screens. Handled by "Lock Glass as the main launcher", not the bloat list. */
    val STOCK_LAUNCHERS = listOf(
        "com.google.android.apps.tv.launcherx",      // Google TV Home
        "com.google.android.tungsten.setupwraith",   // Google TV fallback Home
        "com.google.android.tvlauncher",             // Android TV Home
        "com.google.android.leanbacklauncher",       // older Android TV Home
    )

    /** Known preinstalled extras, with a warning where removing one turns off a feature. */
    private val KNOWN = linkedMapOf(
        "com.google.android.tvrecommendations" to "Recommendation rows for the stock Home screen",
        "com.google.android.apps.tv.dreamx" to "Google TV Ambient mode screensaver",
        "com.google.android.backdrop" to "Ambient mode screensaver",
        "com.google.android.play.games" to "",
        "com.google.android.youtube.tvmusic" to "",
        "com.google.android.youtube.tvkids" to "",
        "com.google.android.apps.youtube.unplugged" to "YouTube TV",
        "com.google.android.videos" to "Google TV / Play Movies",
        "com.google.android.feedback" to "Sends feedback reports to Google",
        "com.google.android.apps.mediashell" to "Warning: turns off casting from your phone (Chromecast built-in)",
        "com.google.android.katniss" to "Warning: turns off voice search and Google Assistant",
        "com.google.android.tv.remote.service" to "Warning: turns off using your phone as a remote",
        "com.google.android.marvin.talkback" to "Screen reader",
        "com.netflix.ninja" to "",
        "com.amazon.amazonvideo.livingroom" to "",
        "com.disney.disneyplus" to "",
        "com.hulu.livingroomplus" to "",
        "com.apple.atve.androidtv.appletv" to "",
        "com.spotify.tv.android" to "",
        "com.plexapp.android" to "",
        "com.sony.dtv.sonyselect" to "Sony Select",
        "com.sony.dtv.promos" to "Sony promotions",
    )

    /** System parts the TV can't work without. Never offered, whatever else matches. */
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.leanback.ime", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.shell", "com.android.providers.tv",
        "com.android.providers.settings", "com.google.android.tvsetup",
    )

    /** Installed, still-enabled apps worth offering, known extras first. */
    @Suppress("DEPRECATION")
    fun candidates(context: Context): List<Candidate> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, Candidate>()

        fun add(pkg: String, note: String) {
            if (pkg in out || pkg in NEVER || pkg in STOCK_LAUNCHERS || pkg == context.packageName) return
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return
            }
            if (!info.enabled) return
            out[pkg] = Candidate(pkg, info.loadLabel(pm).toString(), note)
        }

        KNOWN.forEach { (pkg, note) -> add(pkg, note) }

        // Any other preinstalled app that shows up on the Home screen.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val app = info.activityInfo?.applicationInfo ?: continue
                if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    add(app.packageName, context.getString(R.string.note_preinstalled))
                }
            }
        }
        return out.values.toList()
    }

    @Suppress("DEPRECATION")
    fun isEnabled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
