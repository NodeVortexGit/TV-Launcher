package com.nodevortex.aura.tv

import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Bulgarian TV guides list films under their Bulgarian titles. This maps an English (or
 * half-remembered) title to Bulgarian/Russian titles via Wikipedia + Wikidata, e.g.
 * "The Nutty Professor" → "Смахнатият професор".
 */
object TitleTranslator {
    private val cache = LruCache<String, List<String>>(64)
    private val latin = Regex("[a-zA-Z]{3,}")
    private const val UA = "AuraLauncher/1.0 (Android TV launcher)"

    fun lookup(query: String): List<String> {
        val q = query.trim()
        if (q.length < 4 || !latin.containsMatchIn(q)) return emptyList()
        cache.get(q.lowercase())?.let { return it }
        val out = LinkedHashSet<String>()
        try { wikipedia(q, out) } catch (_: Throwable) { }
        try { wikidata(q, out) } catch (_: Throwable) { }
        val res = out.filter { it.isNotBlank() }.take(6)
        cache.put(q.lowercase(), res)
        return res
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun strip(t: String) = t.replace(Regex("\\s*\\(.*?\\)\\s*$"), "").trim()
    private fun isScreenWork(desc: String) = Regex("(?i)\\b(film|movie|series|sitcom|television|animated|documentary|miniseries)\\b").containsMatchIn(desc)

    private fun wikipedia(q: String, out: MutableSet<String>) {
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search" +
            "&gsrlimit=6&prop=langlinks|pageprops|description&lllang=bg&ppprop=wikibase_item&gsrsearch=" + enc("$q film")
        val pages = get(url).optJSONObject("query")?.optJSONArray("pages") ?: return
        val items = ArrayList<Pair<Int, JSONObject>>()
        for (i in 0 until pages.length()) pages.getJSONObject(i).let { items += it.optInt("index") to it }
        val needLabels = ArrayList<String>()
        for ((_, p) in items.sortedBy { it.first }) {
            if (!isScreenWork(p.optString("description"))) continue
            val ll = p.optJSONArray("langlinks")
            if (ll != null && ll.length() > 0) out += strip(ll.getJSONObject(0).optString("title"))
            p.optJSONObject("pageprops")?.optString("wikibase_item")?.takeIf { it.isNotEmpty() }?.let(needLabels::add)
            out += strip(p.optString("title"))
        }
        if (needLabels.isNotEmpty()) labels(needLabels.take(5), out)
    }

    private fun wikidata(q: String, out: MutableSet<String>) {
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=6&search=" + enc(q)
        val arr = get(url).optJSONArray("search") ?: return
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (isScreenWork(o.optString("description"))) ids += o.optString("id")
        }
        if (ids.isNotEmpty()) labels(ids.take(5), out)
    }

    private fun labels(ids: List<String>, out: MutableSet<String>) {
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels|aliases&languages=bg|ru&ids=" + ids.joinToString("|")
        val ents = get(url).optJSONObject("entities") ?: return
        for (k in ents.keys()) {
            val e = ents.getJSONObject(k)
            val l = e.optJSONObject("labels")
            l?.optJSONObject("bg")?.optString("value")?.let { out += strip(it) }
            l?.optJSONObject("ru")?.optString("value")?.let { out += strip(it) }
            e.optJSONObject("aliases")?.optJSONArray("bg")?.let { a -> for (i in 0 until a.length()) out += strip(a.getJSONObject(i).optString("value")) }
        }
    }
}
