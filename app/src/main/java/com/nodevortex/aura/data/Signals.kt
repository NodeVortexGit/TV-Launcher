package com.nodevortex.aura.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nodevortex.aura.Aura
import com.nodevortex.aura.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Aura's own, on-device-only usage memory ("personal telemetry"). Nothing here ever leaves the TV.
 * It remembers what was played (Spotify/YouTube media sessions), what was picked in search and
 * when apps are used, so search can guess what you most likely mean.
 */
object Signals {
    class Play(val title: String, val artist: String, val pkg: String, var count: Int, var last: Long, val artKey: String?) {
        val tokens: List<String> by lazy { Text.tokens("$title $artist").map(Text::squash) }
        val titleTokens: List<String> by lazy { Text.tokens(title).map(Text::squash) }
    }
    class Choice(val query: String, val kind: String, val target: String, val time: Long) {
        val tokens: List<String> by lazy { Text.tokens(query).map(Text::squash) }
    }

    private val dir by lazy { File(Aura.app.filesDir, "signals").apply { mkdirs() } }
    private val plays = ArrayList<Play>()
    private val choices = ArrayList<Choice>()
    private var loaded = false
    private var lastKey: String? = null

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(File(dir, "plays.json").readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                plays += Play(it.getString("t"), it.optString("a"), it.getString("p"), it.optInt("c", 1), it.optLong("l"), it.optString("k").ifEmpty { null })
            }
        } catch (_: Throwable) { }
        try {
            val a = JSONArray(File(dir, "choices.json").readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { choices += Choice(it.getString("q"), it.getString("k"), it.getString("t"), it.optLong("w")) }
        } catch (_: Throwable) { }
    }

    @Synchronized private fun save() {
        val pa = JSONArray()
        plays.sortedByDescending { it.last }.take(400).forEach {
            pa.put(JSONObject().put("t", it.title).put("a", it.artist).put("p", it.pkg).put("c", it.count).put("l", it.last).put("k", it.artKey ?: ""))
        }
        val ca = JSONArray()
        choices.takeLast(300).forEach { ca.put(JSONObject().put("q", it.query).put("k", it.kind).put("t", it.target).put("w", it.time)) }
        Aura.io.execute {
            try { File(dir, "plays.json").writeText(pa.toString()); File(dir, "choices.json").writeText(ca.toString()) } catch (_: Throwable) { }
        }
    }

    /** Called for every track/video that starts playing. */
    @Synchronized fun notePlay(title: String, artist: String, pkg: String, art: Bitmap?) {
        load()
        val key = "$pkg|$title|$artist"
        if (key == lastKey) return
        lastKey = key
        val existing = plays.firstOrNull { it.pkg == pkg && it.title == title && it.artist == artist }
        if (existing != null) { existing.count++; existing.last = System.currentTimeMillis() }
        else {
            val artKey = art?.let { "a" + (key.hashCode().toLong() and 0xffffffffL).toString(36) }
            if (art != null && artKey != null) Aura.io.execute {
                try { File(dir, "$artKey.jpg").outputStream().use { Bitmap.createScaledBitmap(art, 160, 160, true).compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (_: Throwable) { }
            }
            plays += Play(title, artist, pkg, 1, System.currentTimeMillis(), artKey)
        }
        save()
    }

    fun art(p: Play): Bitmap? = p.artKey?.let { try { BitmapFactory.decodeFile(File(dir, "$it.jpg").path) } catch (_: Throwable) { null } }

    @Synchronized fun noteChoice(query: String, kind: String, target: String) {
        load()
        if (query.isBlank()) return
        choices += Choice(query.trim(), kind, target, System.currentTimeMillis())
        save()
    }

    /** Hour-of-day histogram per package (who is used when), fed by app launches. */
    fun noteLaunchHour(pkg: String) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val arr = hours(pkg); arr[h]++
        Aura.prefs.edit().putString("hours:$pkg", arr.joinToString(",")).apply()
    }

    fun hours(pkg: String): IntArray {
        val s = Aura.prefs.getString("hours:$pkg", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        return if (s != null && s.size == 24) s.toIntArray() else IntArray(24)
    }

    // ------------------------------------------------------------------ queries

    /** Best matching thing you have played for these tokens, with its match score. */
    @Synchronized fun bestPlay(q: List<String>, pkg: String? = null): Pair<Play, Float>? {
        load()
        val now = System.currentTimeMillis()
        var best: Play? = null; var bestS = 0f
        for (p in plays) {
            if (pkg != null && p.pkg != pkg) continue
            val m = maxOf(Text.score(q, p.tokens), Text.score(q, p.titleTokens) + 0.1f)
            if (m < 0.65f) continue
            val days = (now - p.last) / 86_400_000f
            val s = m + 0.08f * kotlin.math.ln(1f + p.count) + 0.25f / (1f + days)
            if (s > bestS) { bestS = s; best = p }
        }
        return best?.let { it to bestS }
    }

    @Synchronized fun recentPlays(limit: Int, pkg: String? = null): List<Play> {
        load(); return plays.filter { pkg == null || it.pkg == pkg }.sortedByDescending { it.last }.take(limit)
    }

    /** How often a kind/target was chosen for similar queries (0..~3). */
    @Synchronized fun choiceAffinity(q: List<String>): Map<String, Float> {
        load()
        val m = HashMap<String, Float>()
        val now = System.currentTimeMillis()
        for (c in choices) {
            val s = Text.score(q, c.tokens)
            if (s < 0.6f) continue
            val decay = 1f / (1f + (now - c.time) / (14 * 86_400_000f))
            val k = c.kind + ":" + c.target
            m[k] = (m[k] ?: 0f) + s * decay
            m[c.kind] = (m[c.kind] ?: 0f) + s * decay * 0.5f
        }
        return m
    }

    /** Overall preference for a package right now: launches, plays and this hour of day. */
    @Synchronized fun prior(pkg: String): Float {
        load()
        val (launches, last) = Aura.app.apps.usage(Aura.app.apps.byPackage(pkg)?.key ?: "")
        val playCount = plays.filter { it.pkg == pkg }.sumOf { it.count }
        val h = hours(pkg); val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val total = h.sum().coerceAtLeast(1)
        val hourShare = (h[hour] + h[(hour + 23) % 24] * 0.5f + h[(hour + 1) % 24] * 0.5f) / total
        val recency = if (last > 0) 0.3f / (1f + (System.currentTimeMillis() - last) / 86_400_000f) else 0f
        return 0.1f * kotlin.math.ln(1f + launches) + 0.12f * kotlin.math.ln(1f + playCount) + 0.4f * hourShare + recency
    }
}
