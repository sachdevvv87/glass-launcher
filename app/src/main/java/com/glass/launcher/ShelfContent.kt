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
 * last 10 days, ranks them newest-first (new series, new seasons, new episodes), and uses their
 * full-screen backgrounds.
 *
 * Everything here blocks on the network or disk: call it from a background thread.
 */
class ShelfContent(private val context: Context) {

    enum class Kind { SERIES, SEASON, EPISODE }

    class Item(
        val title: String,
        val imageUrl: String,
        val showId: Int,
        /** What makes it new: a brand-new series, a new season, or a new episode. */
        val kind: Kind,
        val season: Int,
        /** yyyy-MM-dd of the newest episode. */
        val airdate: String,
    ) {
        /** e.g. "New season · Season 4 · Yesterday". Built on demand so "Today" never goes stale. */
        fun subtitle(): String {
            val what = when (kind) {
                Kind.SERIES -> "New series"
                Kind.SEASON -> "New season · Season $season"
                Kind.EPISODE -> "New episode · Season $season"
            }
            return "$what · ${relativeDay(airdate)}"
        }
    }

    private val dir = File(context.cacheDir, "shelf").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val bitmaps = LruCache<Int, Bitmap>(3)

    @Volatile
    private var index: Map<String, List<Item>> = emptyMap()

    fun itemsFor(pkg: String): List<Item> = index[pkg].orEmpty()

    /**
     * Loads the cached list, re-downloading it when older than [MAX_AGE_MS] (3 hours).
     * Returns true when the shows on offer changed (so the screen should redraw).
     */
    fun refresh(): Boolean {
        val before = index
        if (!isEnabled(context)) {
            index = emptyMap()
            return before.isNotEmpty()
        }
        val cached = if (indexFile.exists()) readIndex() else null
        if (index.isEmpty() && cached != null) index = cached
        val fresh = cached != null && System.currentTimeMillis() - indexFile.lastModified() < MAX_AGE_MS
        if (!fresh) {
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
        return index !== before
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

    /** One show's newest release in the window, scored for "how new and how big". */
    private class Candidate(
        val showId: Int, val name: String, val weight: Int,
        val kind: Kind, val season: Int, val airdate: String, val score: Double,
    )

    private fun download(): Map<String, List<Item>> {
        // 1. Every episode each service released in the last DAYS days.
        val shows = HashMap<Int, MutableMap<Int, Candidate>>() // channel -> show id -> best candidate
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = fmt.parse(fmt.format(Calendar.getInstance().time))!!.time
        val day = Calendar.getInstance()
        repeat(DAYS) {
            val date = fmt.format(day.time)
            day.add(Calendar.DAY_OF_MONTH, -1)
            val episodes = JSONArray(get("$API/schedule/web?date=$date"))
            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                val show = ep.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                val channel = show.optJSONObject("webChannel")?.optInt("id") ?: continue
                if (channel !in CHANNELS.values) continue
                val airdate = ep.optString("airdate").ifEmpty { date }
                val premiered = show.optString("premiered")
                val daysOld = daysBetween(fmt, airdate, today)
                val kind = when {
                    premiered.isNotEmpty() && daysBetween(fmt, premiered, today) <= NEW_SERIES_DAYS -> Kind.SERIES
                    ep.optInt("number", 99) == 1 -> Kind.SEASON
                    else -> Kind.EPISODE
                }
                val weight = show.optInt("weight")
                // Newness first (new series > new season > new episode, and the more recent the
                // better), popularity second, so last week's premiere beats a long-running hit.
                val score = weight * 0.6 +
                    (when (kind) { Kind.SERIES -> 40; Kind.SEASON -> 25; Kind.EPISODE -> 0 }) +
                    (DAYS - daysOld).coerceAtLeast(0) * 3.0
                val id = show.getInt("id")
                val byShow = shows.getOrPut(channel) { HashMap() }
                val previous = byShow[id]
                if (previous == null || score > previous.score) {
                    byShow[id] = Candidate(
                        id, show.getString("name"), weight, kind, ep.optInt("season", 1), airdate, score,
                    )
                }
            }
        }

        // 2. Best-scoring shows that have a wide background image. Obscure shows only fill gaps.
        val perChannel = HashMap<Int, List<Item>>()
        for ((channel, found) in shows) {
            val (popular, rest) = found.values.sortedByDescending { it.score }.partition { it.weight >= MIN_WEIGHT }
            val items = ArrayList<Item>()
            for (c in (popular + rest).take(CANDIDATES)) {
                if (items.size >= PER_APP) break
                val url = background(c.showId) ?: continue
                items += Item(c.name, url, c.showId, c.kind, c.season, c.airdate)
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
                // An older cache without these fields fails here, which forces a fresh download.
                Item(
                    o.getString("t"), o.getString("u"), o.getInt("i"),
                    Kind.valueOf(o.getString("k")), o.getInt("s"), o.getString("d"),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeIndex(data: Map<String, List<Item>>) {
        val root = JSONObject()
        data.forEach { (pkg, items) ->
            root.put(pkg, JSONArray().apply {
                items.forEach {
                    put(
                        JSONObject().put("t", it.title).put("u", it.imageUrl).put("i", it.showId)
                            .put("k", it.kind.name).put("s", it.season).put("d", it.airdate)
                    )
                }
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
        /** How far back to look for new releases. */
        private const val DAYS = 10
        private const val PER_APP = 5
        private const val CANDIDATES = 12
        private const val MIN_WIDTH = 1000
        /** Shows below this TVmaze popularity (0-100) are only used when nothing better exists. */
        private const val MIN_WEIGHT = 70
        /** A show that premiered this recently counts as a brand-new series. */
        private const val NEW_SERIES_DAYS = 30
        /** Re-download every 3 hours so the Top Shelf keeps up with new releases. */
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L

        private fun daysBetween(fmt: SimpleDateFormat, date: String, todayMs: Long): Int = try {
            ((todayMs - fmt.parse(date)!!.time) / 86_400_000L).toInt()
        } catch (e: Exception) {
            Int.MAX_VALUE
        }

        /** "Today", "Yesterday", "3 days ago", or "21 Oct". */
        fun relativeDay(date: String): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val today = fmt.parse(fmt.format(Calendar.getInstance().time))!!.time
            return when (val d = daysBetween(fmt, date, today)) {
                Int.MAX_VALUE -> "Now streaming"
                in Int.MIN_VALUE..0 -> "Today"
                1 -> "Yesterday"
                in 2..6 -> "$d days ago"
                else -> try {
                    SimpleDateFormat("d MMM", Locale.getDefault()).format(fmt.parse(date)!!)
                } catch (e: Exception) {
                    "Now streaming"
                }
            }
        }
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
