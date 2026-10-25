package com.glass.launcher

import android.content.Context
import android.provider.Settings

/**
 * System-wide ad blocking through Android's built-in Private DNS (DNS-over-TLS, Android 9+).
 *
 * Pointing Private DNS at AdGuard DNS makes ad and tracker servers unresolvable for every app on
 * the TV, with no VPN. Most TVs hide the setting, so Glass writes it through [TvShell].
 * It can't block ads served from the same servers as the content (YouTube, Netflix in-video ads).
 */
object AdBlock {

    enum class Mode(val host: String?) {
        OFF(null),
        ADGUARD("dns.adguard-dns.com"),
        FAMILY("family.adguard-dns.com"),
    }

    /** Domains used to verify the result: one that must be blocked, one that must still work. */
    const val CHECK_BLOCKED = "ping -c 1 -W 3 doubleclick.net"
    const val CHECK_INTERNET = "ping -c 1 -W 3 www.google.com"

    fun commands(mode: Mode): List<String> = when (mode) {
        Mode.OFF -> listOf(
            "settings put global private_dns_mode opportunistic",
            "settings delete global private_dns_specifier",
        )
        else -> listOf(
            "settings put global private_dns_specifier ${mode.host}",
            "settings put global private_dns_mode hostname",
            // Give the TV a moment to connect to the new DNS server before checking.
            "sleep 4",
            CHECK_BLOCKED,
            CHECK_INTERNET,
        )
    }

    /**
     * True when a ping's output shows the host didn't resolve to a real address. Blocking DNS
     * servers answer with 0.0.0.0 or 127.0.0.1 (AdGuard does the latter on some networks).
     */
    fun looksBlocked(pingOutput: String): Boolean =
        listOf("(0.0.0.0)", "(127.0.0.1)", "(::)", "(::1)", "unknown host", "bad address").any { it in pingOutput }

    /** The mode the TV is actually in, or null if this Android version won't let apps read it. */
    fun current(context: Context): Mode? = try {
        val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
        val host = Settings.Global.getString(context.contentResolver, "private_dns_specifier")
        when {
            mode != "hostname" -> Mode.OFF
            host == Mode.FAMILY.host -> Mode.FAMILY
            host == Mode.ADGUARD.host -> Mode.ADGUARD
            else -> null // someone else's custom DNS
        }
    } catch (e: SecurityException) {
        null
    }

    fun customHost(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, "private_dns_specifier")
    } catch (e: SecurityException) {
        null
    }
}
