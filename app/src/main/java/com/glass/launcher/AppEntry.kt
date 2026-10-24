package com.glass.launcher

import android.content.ComponentName
import android.graphics.drawable.Drawable

/** One app on the Home screen. */
class AppEntry(
    /** Package name, or [AppRepository.SETTINGS_KEY] for the built-in Settings icon. */
    val key: String,
    val label: String,
    val component: ComponentName?,
    /** 16:9 TV banner, when the app ships one. */
    val banner: Drawable?,
    val icon: Drawable?,
    /** Icon background colour used behind icon-only apps. */
    val color: Int,
) {
    val isSettings: Boolean get() = key == AppRepository.SETTINGS_KEY
}
