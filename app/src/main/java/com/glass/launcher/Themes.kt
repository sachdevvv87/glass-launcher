package com.glass.launcher

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/** The look of the Home screen. */
enum class GlassTheme {
    /** tvOS 26 Liquid Glass. */
    GLASS,

    /** Tron: Legacy's Grid: black, neon cyan edges, an animated grid floor and light cycles. */
    GRID,
}

object Themes {
    private const val PREFS = "glass_setup"
    private const val KEY_THEME = "theme"
    private const val KEY_DAILY_WALL = "daily_wallpaper"

    /** Tron cyan, plus the softer and the Clu-orange variants used for glows and light trails. */
    const val NEON = 0xFF2BE6FF.toInt()
    const val NEON_DIM = 0x662BE6FF
    const val NEON_ORANGE = 0xFFFF8A1F.toInt()

    fun current(context: Context): GlassTheme = try {
        GlassTheme.valueOf(prefs(context).getString(KEY_THEME, null) ?: GlassTheme.GLASS.name)
    } catch (e: IllegalArgumentException) {
        GlassTheme.GLASS
    }

    fun set(context: Context, theme: GlassTheme) {
        prefs(context).edit().putString(KEY_THEME, theme.name).apply()
    }

    /** Today's Tron wallpaper as the Home background (otherwise each app's own colours). */
    fun dailyWallpaper(context: Context): Boolean = prefs(context).getBoolean(KEY_DAILY_WALL, true)

    fun setDailyWallpaper(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_DAILY_WALL, on).apply()
    }

    /** Orbitron, the Tron-style face. Null where the system can't load it (very old Android). */
    fun gridTypeface(context: Context): Typeface? = try {
        ResourcesCompat.getFont(context, R.font.orbitron)
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
