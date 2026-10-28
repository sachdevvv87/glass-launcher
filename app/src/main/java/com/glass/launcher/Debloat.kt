package com.glass.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Which preinstalled apps are offered for removal, and which are never touched.
 *
 * The AI PONT / Homwee entries and the keep-list come from cleanyourtv's tested device lists
 * (github.com/Gathin23/cleanyourtv, MIT): examples/ok-ai-pont-ikebukuro and examples/chiq-ai-pont,
 * the same Changhong/MediaTek platform family as realme's AI PONT TVs.
 */
object Debloat {

    class Candidate(val pkg: String, val label: String, val note: String, val recommended: Boolean)

    private class Known(val pkg: String, val note: String, val recommended: Boolean)

    /** Stock Home screens. Handled by "Lock Glass as the main launcher", not the bloat list. */
    val STOCK_LAUNCHERS = listOf(
        "com.google.android.apps.tv.launcherx",      // Google TV Home
        "com.google.android.tungsten.setupwraith",   // Google TV fallback Home
        "com.google.android.tvlauncher",             // Android TV Home
        "com.google.android.leanbacklauncher",       // older Android TV Home
    )

    private fun rec(pkg: String, note: String) = Known(pkg, note, true)
    private fun opt(pkg: String, note: String = "") = Known(pkg, note, false)

    private val KNOWN = listOf(
        // Recommended: telemetry, ads, recommendation rows, vendor extras (cleanyourtv batches 1, 2, 5).
        rec("tv.anoki.acr.controller", "Anoki content recognition: watches what's on screen for ads"),
        rec("com.cltv.fast", "FAST ad-supported channels"),
        rec("com.homwee.aipont", "AI PONT portal and its recommendation channels"),
        rec("com.google.android.tvrecommendations", "Recommendation rows for the stock Home screen"),
        rec("android.autoinstalls.config.homwee", "Installs partner apps automatically"),
        rec("com.homwee.store", "Homwee app store"),
        rec("com.homwee.help", "Vendor help app"),
        rec("com.homwee.alexacontrol", "Alexa integration"),
        rec("com.google.android.feedback", "Sends feedback reports to Google"),
        rec("com.google.android.play.games", ""),
        rec("com.google.android.videos", "Google Play Movies & TV"),
        rec("com.google.android.syncadapters.calendar", "Calendar sync"),
        rec("com.google.android.onetimeinitializer", "Setup leftover"),
        rec("com.google.android.partnersetup", "Setup leftover"),
        rec("com.android.printspooler", "Printing"),
        rec("com.android.htmlviewer", "HTML viewer"),
        rec("com.android.dynsystem", "Dynamic system updates (developer)"),
        rec("com.android.wallpaperbackup", "Wallpaper backup"),
        rec("com.google.android.apps.tv.dreamx", "Google TV Ambient mode (replaced by the Glass Grid screensaver)"),
        rec("com.google.android.backdrop", "Ambient mode (replaced by the Glass Grid screensaver)"),
        // Your choice: things some people use.
        opt("com.google.android.youtube.tvmusic"),
        opt("com.google.android.youtube.tvkids"),
        opt("com.google.android.apps.youtube.unplugged", "YouTube TV"),
        opt("com.homwee.upgrade", "Vendor app updater (the Play Store still updates apps)"),
        opt("com.homwee.mmp.fileexplorer", "USB file browser"),
        opt("com.homwee.mmp.imageplayer", "USB photo viewer"),
        opt("com.homwee.howtocast", "Casting tutorial"),
        opt("com.homwee.multiscreenshare", "Warning: turns off screen mirroring (Miracast)"),
        opt("com.homwee.mmp.screenshare", "Warning: turns off screen mirroring"),
        opt("com.google.android.apps.mediashell", "Warning: turns off casting from your phone (Chromecast built-in)"),
        opt("com.google.android.katniss", "Warning: turns off voice search and Google Assistant (mic button)"),
        opt("com.google.android.tv.remote.service", "Warning: turns off using your phone as a remote"),
        opt("com.google.android.marvin.talkback", "Screen reader"),
        opt("com.netflix.ninja"),
        opt("com.amazon.amazonvideo.livingroom"),
        opt("com.disney.disneyplus"),
        opt("com.hulu.livingroomplus"),
        opt("com.apple.atve.androidtv.appletv"),
        opt("com.spotify.tv.android"),
        opt("com.plexapp.android"),
        opt("tv.pluto.android"),
        opt("com.tiktok.tv"),
        opt("com.sony.dtv.sonyselect", "Sony Select"),
        opt("com.sony.dtv.promos", "Sony promotions"),
    )

    /**
     * System parts the TV can't work without. Never offered, whatever else matches. From
     * cleanyourtv's keep-lists: e.g. disabling partnercustomizer makes com.mediatek.tv.service
     * crash-loop after the next reboot and breaks the Input button.
     */
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.leanback.ime", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.shell", "com.android.providers.tv",
        "com.android.providers.settings", "com.google.android.tvsetup",
        "com.google.android.leanbacklauncher.partnercustomizer",
        "com.homwee.dmt.upgrade", "com.homwee.synckey", "com.homwee.factory",
        "com.google.android.apps.nbu.smartconnect.tv",
    )

    /** Whole families kept: Live TV, HDMI inputs, remote pairing, picture settings, vendor firmware. */
    private val NEVER_PREFIXES = listOf("com.mediatek.", "com.mstar.", "com.realtek.", "com.amlogic.")

    private fun protected(pkg: String) = pkg in NEVER || NEVER_PREFIXES.any { pkg.startsWith(it) }

    /** Installed, still-enabled apps worth offering: recommended first, then your-choice, then other preinstalled apps. */
    @Suppress("DEPRECATION")
    fun candidates(context: Context): List<Candidate> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, Candidate>()

        fun add(pkg: String, note: String, recommended: Boolean) {
            if (pkg in out || protected(pkg) || pkg in STOCK_LAUNCHERS || pkg == context.packageName) return
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return
            }
            if (!info.enabled) return
            out[pkg] = Candidate(pkg, info.loadLabel(pm).toString(), note, recommended)
        }

        KNOWN.filter { it.recommended }.forEach { add(it.pkg, it.note, true) }
        KNOWN.filter { !it.recommended }.forEach { add(it.pkg, it.note, false) }

        // Any other preinstalled app that shows up on the Home screen.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val app = info.activityInfo?.applicationInfo ?: continue
                if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    add(app.packageName, context.getString(R.string.note_preinstalled), false)
                }
            }
        }
        return out.values.toList()
    }

    /** Known bloat that is currently disabled, however it was disabled (Glass, a PC, cleanyourtv). */
    fun disabledKnown(context: Context): Set<String> =
        KNOWN.map { it.pkg }.filter { isInstalled(context, it) && !isEnabled(context, it) }.toSet()

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
