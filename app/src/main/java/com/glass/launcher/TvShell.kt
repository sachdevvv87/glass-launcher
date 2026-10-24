package com.glass.launcher

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Runs shell commands on this TV through the TV's own ADB daemon (127.0.0.1:5555), which gives
 * the same powers as `adb shell` from a PC: disabling system apps, changing the default Home app,
 * enabling accessibility services.
 *
 * Needs Developer options → USB debugging (or Network debugging) switched on. The first time,
 * the TV shows an "Allow debugging?" prompt that must be accepted.
 */
object TvShell {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    /** Blocks (up to a minute while the user answers the prompt). Never call on the main thread. */
    fun run(context: Context, commands: List<String>): List<Pair<String, String>> {
        Dadb.create(HOST, PORT, keyPair(context), connectTimeout = 4000, socketTimeout = 60_000).use { adb ->
            return commands.map { cmd -> cmd to adb.shell(cmd).allOutput.trim() }
        }
    }

    private fun keyPair(context: Context): AdbKeyPair {
        val private = File(context.filesDir, "adbkey")
        val public = File(context.filesDir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }
}
