package com.glass.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Real show artwork for streaming apps' Top Shelf.
 *
 * Android only lets the built-in launcher read the poster "channels" apps publish, so instead this
 * asks TVmaze (tvmaze.com, free API, CC BY-SA) which shows each streaming service released in the
 * last two weeks, and uses the most popular ones' full-screen backgrounds.
 *
 * Everything here blocks on the network or disk: call it from a background thread.
 */
class ShelfContent(private val context: Context) {

    class Item(val title: String, val imageUrl: String, val showId: Int)

    private val dir = File(context.cacheDir, "shelf").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val bitmaps = LruCache<Int, Bitmap>(3)

    @Volatile
    private var index: Map<String, List<Item>> = emptyMap()

    fun itemsFor(pkg: String): List<Item> = index[pkg].orEmpty()

    /** Loads the cached list, re-downloading it when older than 12 hours. */
    fun refresh() {
        if (!isEnabled(context)) {
            index = emptyMap()
            return
        }
        val cached = readIndex()
        if (cached != null) index = cached
        val fresh = cached != null && System.currentTimeMillis() - indexFile.lastModified() < MAX_AGE_MS
        if (fresh) return
        val downloaded = try {
            download()
        } catch (e: Exception) {
            null
        }
        if (downloaded != null && downloaded.isNotEmpty()) {
            index = downloaded
            writeIndex(downloaded)
            pruneImages()
        }
    }

    /** The backdrop for [item], downloaded once and then served from disk, scaled to [maxWidth]. */
    fun bitmap(item: Item, maxWidth: Int): Bitmap? {
        bitmaps.get(item.showId)?.let { return it }
        val file = File(dir, "${item.showId}.jpg")
        if (!file.exists()) {
            try {
                val tmp = File(dir, "${item.showId}.part")
                open(item.imageUrl).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            } catch (e: Exception) {
                return null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // half the memory; photos don't need alpha
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        bitmaps.put(item.showId, bmp)
        return bmp
    }

    // ---- TVmaze --------------------------------------------------------------------------

    private fun download(): Map<String, List<Item>> {
        // 1. Shows each service streamed new episodes of in the last two weeks.
        val shows = HashMap<Int, MutableMap<Int, Pair<String, Int>>>() // channel -> show id -> (name, weight)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val day = Calendar.getInstance()
        repeat(DAYS) {
            val date = fmt.format(day.time)
            day.add(Calendar.DAY_OF_MONTH, -1)
            val episodes = JSONArray(get("$API/schedule/web?date=$date"))
            for (i in 0 until episodes.length()) {
                val show = episodes.getJSONObject(i).optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                val channel = show.optJSONObject("webChannel")?.optInt("id") ?: continue
                if (channel !in CHANNELS.values) continue
                shows.getOrPut(channel) { HashMap() }[show.getInt("id")] =
                    show.getString("name") to show.optInt("weight")
            }
        }

        // 2. The most popular ones that have a wide background image.
        val perChannel = HashMap<Int, List<Item>>()
        for ((channel, found) in shows) {
            val items = ArrayList<Item>()
            for ((id, info) in found.entries.sortedByDescending { it.value.second }.take(CANDIDATES)) {
                if (items.size >= PER_APP) break
                val url = background(id) ?: continue
                items += Item(info.first, url, id)
            }
            if (items.isNotEmpty()) perChannel[channel] = items
        }
        return CHANNELS.mapNotNull { (pkg, channel) -> perChannel[channel]?.let { pkg to it } }.toMap()
    }

    /** The widest landscape background TVmaze has for a show, if any. */
    private fun background(showId: Int): String? {
        val images = JSONArray(get("$API/shows/$showId/images"))
        var best: String? = null
        var bestWidth = 0
        for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            if (image.optString("type") != "background") continue
            val original = image.optJSONObject("resolutions")?.optJSONObject("original") ?: continue
            val w = original.optInt("width")
            val h = original.optInt("height")
            if (w > bestWidth && w >= MIN_WIDTH && w > h) {
                bestWidth = w
                best = original.optString("url").takeIf { it.startsWith("https://") }
            }
        }
        return best
    }

    private var lastCall = 0L

    /** GET with a pause between calls; TVmaze allows about 20 requests per 10 seconds. */
    private fun get(url: String): String {
        repeat(3) { attempt ->
            val wait = lastCall + 600 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
            val conn = open(url)
            try {
                if (conn.responseCode == 429) {
                    Thread.sleep(2000L * (attempt + 1))
                    return@repeat
                }
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        error("TVmaze rate limit")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "GlassLauncher/3 (Android TV)")
        }

    // ---- cache ---------------------------------------------------------------------------

    private fun readIndex(): Map<String, List<Item>>? = try {
        val root = JSONObject(indexFile.readText())
        root.keys().asSequence().associateWith { pkg ->
            val arr = root.getJSONArray(pkg)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.getString("t"), o.getString("u"), o.getInt("i"))
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeIndex(data: Map<String, List<Item>>) {
        val root = JSONObject()
        data.forEach { (pkg, items) ->
            root.put(pkg, JSONArray().apply {
                items.forEach { put(JSONObject().put("t", it.title).put("u", it.imageUrl).put("i", it.showId)) }
            })
        }
        indexFile.writeText(root.toString())
    }

    /** Drop images of shows that are no longer featured. */
    private fun pruneImages() {
        val keep = index.values.flatten().map { "${it.showId}.jpg" }.toSet()
        dir.listFiles()?.forEach { f -> if (f.name.endsWith(".jpg") && f.name !in keep) f.delete() }
    }

    companion object {
        private const val API = "https://api.tvmaze.com"
        private const val DAYS = 14
        private const val PER_APP = 5
        private const val CANDIDATES = 10
        private const val MIN_WIDTH = 1000
        private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
        private const val PREFS = "glass_setup"
        private const val KEY_ENABLED = "shelf_artwork"

        /** App package → TVmaze streaming-service ("web channel") id. */
        val CHANNELS = mapOf(
            "com.netflix.ninja" to 1,
            "com.amazon.amazonvideo.livingroom" to 3,
            "com.google.android.youtube.tv" to 21,
            "com.apple.atve.androidtv.appletv" to 310,
            "com.disney.disneyplus" to 287,
            "in.startv.hotstar" to 164,
            "in.startv.hotstar.dplus.tv" to 164,
            "com.hulu.livingroomplus" to 2,
            "com.wbd.stream" to 329,
            "com.hbo.hbonow" to 329,
            "com.cbs.ott" to 107,
            "com.peacocktv.peacockandroid" to 347,
        )

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }
    }
}
