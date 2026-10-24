package com.glass.launcher

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** "Glass Launcher settings": lock Glass as the Home screen, and disable bloatware. */
class SetupActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var bloatList: LinearLayout
    private lateinit var restoreAppsButton: TextView
    private val selected = linkedSetOf<String>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = getSharedPreferences("glass_setup", MODE_PRIVATE)
        status = findViewById(R.id.status_text)
        log = findViewById(R.id.log)
        bloatList = findViewById(R.id.bloat_list)
        restoreAppsButton = findViewById(R.id.btn_enable_all)

        findViewById<View>(R.id.btn_lock).setOnClickListener { lockAsLauncher() }
        findViewById<View>(R.id.btn_guard).setOnClickListener {
            startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.btn_restore).setOnClickListener { restoreLauncher() }
        findViewById<View>(R.id.btn_disable).setOnClickListener { disableSelected() }
        restoreAppsButton.setOnClickListener { restoreDisabledApps() }
        findViewById<View>(R.id.btn_artwork).setOnClickListener {
            ShelfContent.setEnabled(this, !ShelfContent.isEnabled(this))
            refresh()
        }
        findViewById<View>(R.id.btn_lock).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- status --------------------------------------------------------------------------

    private fun refresh() {
        val guard = getString(if (HomeGuardService.isEnabled(this)) R.string.state_on else R.string.state_off)
        val stock = Debloat.STOCK_LAUNCHERS.filter { Debloat.isEnabled(this, it) }
        val stockText = if (stock.isEmpty()) getString(R.string.state_disabled) else getString(R.string.state_active)
        status.text = getString(R.string.setup_status, guard, homeLabel(), stockText)
        restoreAppsButton.text = getString(R.string.btn_restore_apps, disabledByGlass().size)
        findViewById<TextView>(R.id.btn_artwork).text = getString(
            R.string.btn_artwork,
            getString(if (ShelfContent.isEnabled(this)) R.string.state_on else R.string.state_off),
        )
        buildBloatList()
    }

    private fun homeLabel(): String {
        val info = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val pkg = info?.activityInfo?.packageName
        return when (pkg) {
            null, "android" -> getString(R.string.home_not_set)
            packageName -> getString(R.string.app_name)
            else -> info?.activityInfo?.applicationInfo?.loadLabel(packageManager)?.toString() ?: pkg
        }
    }

    private fun buildBloatList() {
        bloatList.removeAllViews()
        val candidates = Debloat.candidates(this)
        selected.retainAll(candidates.map { it.pkg }.toSet())
        if (candidates.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.row_note, bloatList, false) as TextView
            empty.setText(R.string.no_bloat)
            bloatList.addView(empty)
            return
        }
        for (c in candidates) {
            val row = layoutInflater.inflate(R.layout.row_bloat, bloatList, false)
            val check = row.findViewById<View>(R.id.check)
            row.findViewById<TextView>(R.id.name).text = c.label
            row.findViewById<TextView>(R.id.detail).text =
                if (c.note.isEmpty()) c.pkg else "${c.note}  ·  ${c.pkg}"
            fun paint() = check.setBackgroundResource(
                if (c.pkg in selected) R.drawable.check_on else R.drawable.check_off
            )
            paint()
            row.setOnClickListener {
                if (!selected.remove(c.pkg)) selected.add(c.pkg)
                paint()
            }
            bloatList.addView(row)
        }
    }

    // ---- actions -------------------------------------------------------------------------

    private fun lockAsLauncher() {
        val home = "$packageName/${MainActivity::class.java.name}"
        val guard = HomeGuardService.component(this).flattenToString()
        val existing = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() && it != guard }
        val services = (existing + guard).joinToString(":")
        val stock = Debloat.STOCK_LAUNCHERS.filter { Debloat.isEnabled(this, it) }

        val commands = mutableListOf(
            // Android 13+ blocks accessibility for sideloaded apps unless this is allowed.
            "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow",
            "settings put secure enabled_accessibility_services $services",
            "settings put secure accessibility_enabled 1",
            "cmd package set-home-activity $home",
            "cmd role add-role-holder android.app.role.HOME $packageName",
        )
        stock.forEach { commands += "pm disable-user --user 0 $it" }

        runShell(commands) { results ->
            val disabled = results.filter { (cmd, out) -> cmd.startsWith("pm disable-user") && "disabled" in out }
                .map { it.first.substringAfterLast(' ') }
            rememberStockDisabled(disabled)
        }
    }

    private fun restoreLauncher() {
        val guard = HomeGuardService.component(this).flattenToString()
        val remaining = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() && it != guard }.joinToString(":")
        val stock = (stockDisabledByGlass() + Debloat.STOCK_LAUNCHERS)
            .distinct().filter { Debloat.isInstalled(this, it) }

        val commands = mutableListOf<String>()
        stock.forEach { commands += "pm enable $it" }
        commands += "cmd role remove-role-holder android.app.role.HOME $packageName"
        commands += if (remaining.isEmpty()) {
            "settings delete secure enabled_accessibility_services"
        } else {
            "settings put secure enabled_accessibility_services $remaining"
        }
        runShell(commands) { prefs.edit().remove(KEY_STOCK).apply() }
    }

    private fun disableSelected() {
        if (selected.isEmpty()) {
            appendLog(getString(R.string.log_nothing_selected))
            return
        }
        val commands = selected.map { "pm disable-user --user 0 $it" }
        runShell(commands) { results ->
            val done = results.filter { (_, out) -> "disabled" in out }.map { it.first.substringAfterLast(' ') }
            prefs.edit().putStringSet(KEY_DISABLED, disabledByGlass() + done).apply()
            selected.removeAll(done.toSet())
        }
    }

    private fun restoreDisabledApps() {
        val apps = disabledByGlass()
        if (apps.isEmpty()) {
            appendLog(getString(R.string.log_nothing_to_restore))
            return
        }
        runShell(apps.map { "pm enable $it" }) { prefs.edit().remove(KEY_DISABLED).apply() }
    }

    /** Runs commands through the TV's own ADB, then logs what happened. */
    private fun runShell(commands: List<String>, onDone: (List<Pair<String, String>>) -> Unit) {
        if (busy) return
        busy = true
        appendLog(getString(R.string.log_connecting))
        io.execute {
            val result = try {
                Result.success(TvShell.run(this, commands))
            } catch (t: Throwable) {
                Result.failure(t)
            }
            main.post {
                busy = false
                if (isDestroyed) return@post
                result.onSuccess { results ->
                    results.forEach { (cmd, out) -> appendLog("$ $cmd\n${out.ifEmpty { "OK" }}") }
                    onDone(results)
                    appendLog(getString(R.string.log_done))
                }.onFailure { t ->
                    appendLog(getString(R.string.log_failed, "${t.javaClass.simpleName}: ${t.message.orEmpty()}"))
                }
                refresh()
            }
        }
    }

    // ---- bookkeeping ---------------------------------------------------------------------

    private fun disabledByGlass(): Set<String> = prefs.getStringSet(KEY_DISABLED, null)?.toSet() ?: emptySet()

    private fun stockDisabledByGlass(): Set<String> = prefs.getStringSet(KEY_STOCK, null)?.toSet() ?: emptySet()

    private fun rememberStockDisabled(pkgs: List<String>) {
        prefs.edit().putStringSet(KEY_STOCK, stockDisabledByGlass() + pkgs).apply()
    }

    private fun appendLog(text: String) {
        log.visibility = View.VISIBLE
        log.append(text + "\n\n")
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            appendLog(getString(R.string.cannot_open))
        }
    }

    companion object {
        private const val KEY_DISABLED = "disabled_apps"
        private const val KEY_STOCK = "disabled_stock_launchers"
    }
}
