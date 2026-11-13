package com.nodevortex.aura.tv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Xml
import com.nodevortex.aura.Aura
import com.nodevortex.aura.util.Text
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Per-title data shared by every airing of the same programme (titles repeat a lot). */
class TitleInfo(val title: String) {
    /** Programme name without the "- Филм / US / 2004" style suffix. */
    val name: String = title.substringBefore(" - ").trim()
    val detail: String get() = title.substringAfter(" - ", "").trim()
    val tokens: List<String> by lazy(LazyThreadSafetyMode.NONE) { Text.tokens(name).map(Text::squash) }
}

/** One airing. Kept small: ~30 000 of these live in RAM on a 1.4 GB TV. */
class Prog(val start: Long, val stop: Long, val ch: String, val info: TitleInfo, val isMovie: Boolean) {
    val title: String get() = info.title
    val name: String get() = info.name
    val detail: String get() = info.detail
    val tokens: List<String> get() = info.tokens
    fun isLive(now: Long) = now in start until stop
    companion object {
        private val MOVIE = Regex("(?i)(филм|film\\b|игрален|/\\s*[A-Z]{2}\\s*/\\s*(19|20)\\d\\d|\\((САЩ|Великобритания|Франция|Германия|Италия|Канада|Испания|Австралия|Индия)[^)]*(19|20)\\d\\d)")
        fun movie(title: String, desc: String) = MOVIE.containsMatchIn(title) || MOVIE.containsMatchIn(desc.take(160))
    }
}

class EpgHit(val prog: Prog, val score: Float, val via: String?)

/**
 * TV guide for the COTV line-up. A public XMLTV feed only covers ~yesterday…+6 days, so the
 * device keeps every day it has seen for 7 days – that is what makes "was it on this week?" work.
 */
class Epg(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "epg").apply { mkdirs() }
    private val dayFmt get() = SimpleDateFormat("yyyyMMdd", Locale.US)
    @Volatile private var cache: List<Prog>? = null
    @Volatile private var cacheAt = 0L
    private val lock = Any()
    @Volatile var refreshing = false
        private set

    val lastUpdate: Long get() = Aura.prefs.getLong("epgUpdated", 0L)
    val daysStored: Int get() = dir.listFiles { f -> f.name.endsWith(".tsv") }?.size ?: 0

    fun all(): List<Prog> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val out = ArrayList<Prog>(32000)
            val titles = HashMap<String, TitleInfo>(8192)
            val chans = HashMap<String, String>(256)
            val cutoff = System.currentTimeMillis() - 8L * 24 * 3600_000
            dir.listFiles { f -> f.name.endsWith(".tsv") }?.sortedBy { it.name }?.forEach { f ->
                f.bufferedReader(Charsets.UTF_8, 64 * 1024).useLines { lines ->
                    lines.forEach { line -> parseLine(line, titles, chans)?.let { if (it.stop > cutoff) out.add(it) } }
                }
            }
            out.trimToSize()
            cache = out; cacheAt = System.currentTimeMillis(); nowCache = null
            return out
        }
    }

    /** Free the in-memory guide – only on real memory pressure (re-parsing costs ~1 s). */
    fun trim() { cache = null; nowCache = null }

    @Volatile private var nowCache: Pair<Long, Map<String, Prog>>? = null

    private fun parseLine(l: String, titles: HashMap<String, TitleInfo>, chans: HashMap<String, String>): Prog? {
        val a = l.indexOf('\t'); if (a < 0) return null
        val b = l.indexOf('\t', a + 1); if (b < 0) return null
        val c = l.indexOf('\t', b + 1); if (c < 0) return null
        val d = l.indexOf('\t', c + 1)
        return try {
            val start = l.substring(0, a).toLong() * 1000; val stop = l.substring(a + 1, b).toLong() * 1000
            val chRaw = l.substring(b + 1, c)
            val ch = chans.getOrPut(chRaw) { chRaw }
            val title = if (d < 0) l.substring(c + 1) else l.substring(c + 1, d)
            val info = titles.getOrPut(title) { TitleInfo(title) }
            val movie = Prog.movie(title, if (d < 0) "" else l.substring(d + 1, minOf(l.length, d + 1 + 160)))
            Prog(start, stop, ch, info, movie)
        } catch (_: NumberFormatException) { null }
    }

    fun nowOn(epgId: String, now: Long = System.currentTimeMillis()): Prog? = nowAll(now)[epgId]

    /** What is on every channel right now (cached for a minute). */
    fun nowAll(now: Long = System.currentTimeMillis()): Map<String, Prog> {
        nowCache?.let { (t, m) -> if (now - t < 60_000) return m }
        val m = HashMap<String, Prog>()
        for (p in all()) if (p.isLive(now)) m[p.ch] = p
        nowCache = now to m
        return m
    }

    fun search(query: String, translations: List<String>, limit: Int = 40): List<EpgHit> {
        val qt = Text.tokens(query).map(Text::squash)
        val alt = translations.map { it to Text.tokens(it).map(Text::squash) }
        if (qt.isEmpty() && alt.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 3600_000
        val hits = HashMap<String, EpgHit>()
        val memo = HashMap<TitleInfo, Pair<Float, String?>>(4096)
        for (p in all()) {
            if (p.stop < weekAgo) continue
            val (base, via) = memo.getOrPut(p.info) {
                var best = if (qt.isEmpty()) 0f else Text.score(qt, p.tokens)
                var v: String? = null
                for ((t, toks) in alt) {
                    val sc = Text.score(toks, p.tokens)
                    if (sc > best + 0.05f) { best = sc; v = t }
                }
                best to v
            }
            if (base < 0.68f) continue
            val score = if (p.isMovie) base + 0.08f else base
            val key = p.ch + p.start
            val prev = hits[key]
            if (prev == null || prev.score < score) hits[key] = EpgHit(p, score, via)
        }
        return hits.values.sortedWith(
            compareByDescending<EpgHit> { (it.score * 4).toInt() }
                .thenBy { if (it.prog.isLive(now)) 0 else if (it.prog.start > now) 1 else 2 }
                .thenBy { kotlin.math.abs(it.prog.start - now) }
        ).take(limit)
    }

    // ------------------------------------------------------------------ download

    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastUpdate > 10 * 3600_000L) Aura.net.execute { refresh() }
    }

    fun refresh(): Boolean {
        if (refreshing) return false
        refreshing = true
        try {
            val wanted = Aura.app.channels.list.mapNotNull { it.epg }.toHashSet()
            for (src in SOURCES) {
                val progs = try { download(src, wanted) } catch (t: Throwable) { null }
                if (progs != null && progs.size > 500) {
                    store(progs)
                    Aura.prefs.edit().putLong("epgUpdated", System.currentTimeMillis()).apply()
                    cache = null; nowCache = null
                    return true
                }
            }
            return false
        } finally { refreshing = false }
    }

    private fun download(url: String, wanted: Set<String>): List<Raw> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "VeloCinema/1.2 (Android TV)")
        c.setRequestProperty("Accept-Encoding", "identity")
        val raw = BufferedInputStream(c.inputStream, 64 * 1024)
        raw.mark(4)
        val b0 = raw.read(); val b1 = raw.read(); raw.reset()
        val input = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val out = ArrayList<Raw>(20000)
        input.use { ins ->
            val xp = Xml.newPullParser()
            xp.setInput(ins, "UTF-8")
            var ev = xp.eventType
            var start = 0L; var stop = 0L; var ch: String? = null
            var title = ""; var desc = ""; var field: String? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (xp.name) {
                        "programme" -> {
                            ch = xp.getAttributeValue(null, "channel")
                            if (ch != null && ch !in wanted) ch = null
                            if (ch != null) {
                                start = parseTime(fmt, xp.getAttributeValue(null, "start"))
                                stop = parseTime(fmt, xp.getAttributeValue(null, "stop"))
                            }
                            title = ""; desc = ""
                        }
                        "title" -> field = if (ch != null && title.isEmpty()) "t" else null
                        "desc" -> field = if (ch != null && desc.isEmpty()) "d" else null
                    }
                    XmlPullParser.TEXT -> when (field) {
                        "t" -> title += xp.text
                        "d" -> desc += xp.text
                    }
                    XmlPullParser.END_TAG -> {
                        if (xp.name == "title" || xp.name == "desc") field = null
                        if (xp.name == "programme" && ch != null && start > 0 && stop > start && title.isNotBlank()) {
                            out += Raw(start, stop, ch!!, clean(title), clean(desc).take(220))
                            ch = null
                        }
                    }
                }
                ev = xp.next()
            }
        }
        return out
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun parseTime(f: SimpleDateFormat, s: String?): Long = try { f.parse(s!!)!!.time } catch (_: Throwable) { 0L }

    /** A downloaded airing before it is written to disk (keeps the description for the file). */
    class Raw(val start: Long, val stop: Long, val ch: String, val title: String, val desc: String)

    private fun store(progs: List<Raw>) {
        val tz = TimeZone.getDefault()
        val df = dayFmt.apply { timeZone = tz }
        val byDay = progs.groupBy { df.format(it.start) }
        for ((day, list) in byDay) {
            val f = File(dir, "$day.tsv")
            // key = channel + start; existing lines are merged as raw text
            val merged = LinkedHashMap<String, Pair<Long, String>>()
            if (f.exists()) f.forEachLine { line ->
                val a = line.indexOf('\t'); val b = line.indexOf('\t', a + 1); val c = line.indexOf('\t', b + 1)
                if (a > 0 && b > a && c > b) line.substring(0, a).toLongOrNull()?.let { st -> merged[line.substring(b + 1, c) + st] = st to line }
            }
            list.forEach { merged[it.ch + (it.start / 1000)] = (it.start / 1000) to "${it.start / 1000}\t${it.stop / 1000}\t${it.ch}\t${it.title}\t${it.desc}" }
            val tmp = File(dir, "$day.tmp")
            tmp.bufferedWriter().use { w -> merged.values.sortedBy { it.first }.forEach { w.write(it.second); w.write("\n") } }
            tmp.renameTo(f)
        }
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -8) }
        val cutoff = df.format(cal.time)
        dir.listFiles { file -> file.name.endsWith(".tsv") && file.name.substringBefore('.') < cutoff }?.forEach { it.delete() }
    }

    companion object {
        val SOURCES = listOf(
            "https://iptv-epg.org/files/epg-bg.xml.gz",
            "https://epgshare01.online/epgshare01/epg_ripper_BG1.xml.gz",
        )
    }
}

object EpgJob {
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(42) == null) {
            js.schedule(JobInfo.Builder(42, ComponentName(ctx, EpgJobService::class.java))
                .setPeriodic(12 * 3600_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build())
        }
        Aura.app.epg.refreshIfStale()
    }
}

class EpgJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Aura.net.execute {
            val ok = Aura.app.epg.refresh()
            jobFinished(params, !ok)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
