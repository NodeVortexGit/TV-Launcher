package com.nodevortex.aura.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.nodevortex.aura.Aura
import com.nodevortex.aura.util.Text
import org.json.JSONArray

class Channel(val n: Int, val name: String, val epg: String?, val hasLogo: Boolean) {
    val tokens: List<String> by lazy { Text.tokens(name).map(Text::squash) }
}

/** COTV (Comnet IPTV) channel line-up, captured from the app's own channel list. */
class Channels(private val ctx: Context) {

    val list: List<Channel> by lazy {
        try {
            val arr = JSONArray(ctx.assets.open("channels.json").bufferedReader().readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Channel(o.getInt("n"), o.getString("name"), o.optString("epg").ifEmpty { null }, o.optBoolean("logo"))
            }
        } catch (t: Throwable) { emptyList() }
    }
    private val logos = object : LruCache<Int, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun byNumber(n: Int) = list.firstOrNull { it.n == n }

    /** The channel number to show for an EPG id (SD and HD share one guide). */
    fun primaryFor(epgId: String): Channel? = list.filter { it.epg == epgId }.minByOrNull { it.n }
    fun allFor(epgId: String) = list.filter { it.epg == epgId }

    fun logo(ch: Channel, cb: (Bitmap?) -> Unit) {
        if (!ch.hasLogo) { cb(null); return }
        logos.get(ch.n)?.let { cb(it); return }
        Aura.io.execute {
            val b = try { ctx.assets.open("logos/${ch.n}.png").use { BitmapFactory.decodeStream(it) } } catch (_: Throwable) { null }
            if (b != null) logos.put(ch.n, b)
            Aura.ui { cb(b) }
        }
    }

    fun search(q: String, limit: Int = 30): List<Channel> {
        val qt = Text.tokens(q).map(Text::squash)
        if (qt.isEmpty()) return emptyList()
        val num = q.trim().toIntOrNull()
        return list.mapNotNull { ch ->
            val s = when {
                num != null && ch.n == num -> 3f
                num != null && ch.n.toString().startsWith(q.trim()) -> 1.2f
                else -> Text.score(qt, ch.tokens)
            }
            if (s > 0.6f) ch to s else null
        }.sortedWith(compareByDescending<Pair<Channel, Float>> { it.second }.thenBy { it.first.n }).take(limit).map { it.first }
    }

    // ------------------------------------------------------------------ tuning

    fun noteTuned(ch: Channel) {
        val prefs = Aura.prefs
        val list = prefs.getString("tuned", "")!!.split(',').filter { it.isNotEmpty() }.toMutableList()
        list.remove(ch.n.toString()); list.add(0, ch.n.toString())
        val counts = prefs.getString("tunedCount", "")!!
        val m = counts.split(',').filter { ':' in it }.associate { it.substringBefore(':') to (it.substringAfter(':').toIntOrNull() ?: 0) }.toMutableMap()
        m[ch.n.toString()] = (m[ch.n.toString()] ?: 0) + 1
        prefs.edit().putString("tuned", list.take(20).joinToString(","))
            .putString("tunedCount", m.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun favourites(): List<Channel> {
        val m = Aura.prefs.getString("tunedCount", "")!!.split(',').filter { ':' in it }
            .associate { it.substringBefore(':').toInt() to (it.substringAfter(':').toIntOrNull() ?: 0) }
        return list.sortedWith(compareByDescending<Channel> { m[it.n] ?: 0 }.thenBy { it.n })
    }

    /**
     * Opens COTV on a channel. COTV has no deep links, so with the shell bridge we start it and
     * type the channel number exactly like a remote with digits would; otherwise we just open it.
     */
    fun tune(context: Context, ch: Channel): Boolean {
        noteTuned(ch)
        com.nodevortex.aura.data.Signals.noteLaunchHour(PKG)
        val bridge = Aura.app.bridge
        if (bridge.ready) {
            val digits = ch.n.toString().map { "KEYCODE_$it" }.joinToString(" ")
            val script = "if pidof $PKG >/dev/null; then W=0.9; else W=7; fi; " +
                "am start -n $PKG/$ACT >/dev/null 2>&1; i=0; " +
                "while [ \$i -lt 48 ]; do dumpsys activity activities | grep -q 'topResumedActivity.*$PKG' && break; sleep 0.25; i=\$((i+1)); done; " +
                "sleep \$W; input keyevent $digits"
            bridge.shell(script, 30_000)
            return true
        }
        open(context)
        return false
    }

    fun open(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PKG, ACT))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (_: Throwable) { }
    }

    companion object {
        const val PKG = "bg.comnet.cotv"
        const val ACT = "tv.tvip.app.TvipNativeActivity"
    }
}
