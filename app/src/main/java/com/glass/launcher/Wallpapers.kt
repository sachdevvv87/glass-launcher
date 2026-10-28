package com.glass.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Tron wallpapers for the screensaver and the Home background: a new set every day.
 *
 * Sources, in order: your own pictures (any folder named "Tron" or "Wallpapers" in Pictures or
 * Download, e.g. sent with LocalSend), then Grid scenes drawn on the TV by [TronArt]. Nothing is
 * downloaded. Rendering and disk work block: call off the main thread.
 */
class Wallpapers(private val context: Context) {

    class Photo(val key: String, val uri: String, val title: String, val local: Boolean)

    private val dir = File(context.cacheDir, "grid").apply { mkdirs() }
    private val decoded = LruCache<String, Bitmap>(3)

    /** Today's wallpapers: your own first, then today's Grid scenes (same all day, new tomorrow). */
    fun today(): List<Photo> = localPhotos() + generatedToday()

    /** The wallpaper used as the Home background today. */
    fun photoOfTheDay(): Photo? = today().firstOrNull()

    /**
     * The wallpaper at [maxWidth]: your picture scaled, or the Grid scene rendered (cached on disk).
     * [underLiveGrid]: drawn without floor lines, for the Home screen where the animated Grid
     * floor sits on top and supplies them.
     */
    fun bitmap(photo: Photo, maxWidth: Int, underLiveGrid: Boolean = false): Bitmap? {
        val key = photo.key + if (underLiveGrid) "-home" else ""
        decoded.get(key)?.let { return it }
        val bmp = if (photo.local) decodeUri(Uri.parse(photo.uri), maxWidth) else renderCached(photo, maxWidth, underLiveGrid)
        bmp?.let { decoded.put(key, it) }
        return bmp
    }

    // ---- generated Grid scenes -----------------------------------------------------------

    private fun generatedToday(): List<Photo> {
        val day = dayKey()
        val scenes = TronArt.Scene.values()
        // Every scene once a day, in a different order with different layouts each day.
        return scenes.toList().shuffled(Random(day)).mapIndexed { i, scene ->
            val seed = day * 31L + i
            // The key names the scene and the art version, so a cached render can never be
            // reused for a different scene (or for an older drawing of the same one).
            Photo(key = "tron-$day-$i-${scene.name}-v$ART_VERSION", uri = "tron:${scene.name}:$seed", title = scene.title, local = false)
        }
    }

    private fun renderCached(photo: Photo, width: Int, underLiveGrid: Boolean): Bitmap? {
        val file = File(dir, "${photo.key}-$width${if (underLiveGrid) "-home" else ""}.png")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        val parts = photo.uri.split(':')
        val scene = runCatching { TronArt.Scene.valueOf(parts[1]) }.getOrNull() ?: return null
        val bmp = TronArt.render(scene, parts[2].toLong(), width, floorLines = !underLiveGrid)
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pruneOldRenders()
        } catch (e: Exception) {
            // Out of space: render again next time.
        }
        return bmp
    }

    /** Keep today's and yesterday's scenes only. */
    private fun pruneOldRenders() {
        val keep = setOf("tron-${dayKey()}-", "tron-${dayKey(-1)}-")
        dir.listFiles()?.filter { f -> keep.none { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    // ---- your own pictures ---------------------------------------------------------------

    fun hasPhotoPermission(): Boolean =
        context.checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun localPhotos(): List<Photo> {
        if (!hasPhotoPermission()) return emptyList()
        val out = ArrayList<Photo>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, pathColumn)
        val where = FOLDERS.joinToString(" OR ") { "$pathColumn LIKE ?" }
        val args = FOLDERS.map { "%/$it/%" }.toTypedArray()
        try {
            context.contentResolver.query(uri, projection, where, args, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < 60) {
                    val id = c.getLong(0)
                    out += Photo(
                        key = "local-$id",
                        uri = ContentUris.withAppendedId(uri, id).toString(),
                        title = c.getString(1).substringBeforeLast('.'),
                        local = true,
                    )
                }
            }
        } catch (e: Exception) {
            // Storage unavailable: just use the generated scenes.
        }
        // Your pictures rotate too: a different order each day.
        return out.shuffled(Random(dayKey()))
    }

    private fun decodeUri(uri: Uri, maxWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Bump when [TronArt] drawings change, so cached renders are redrawn. */
        private const val ART_VERSION = 4

        /** Folder names searched for your own wallpapers (in Pictures, Download, DCIM…). */
        private val FOLDERS = listOf("Tron", "Wallpapers", "GlassScreensaver")

        fun photoPermission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        /** Changes once a day, so the daily selection is stable all day and new tomorrow. */
        fun dayKey(offsetDays: Int = 0): Int {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, offsetDays) }
            return SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time).toInt()
        }
    }
}
