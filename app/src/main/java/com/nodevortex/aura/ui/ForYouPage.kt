package com.nodevortex.aura.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.nodevortex.aura.Aura
import com.nodevortex.aura.HomeActivity
import com.nodevortex.aura.R
import com.nodevortex.aura.system.AuraService
import com.nodevortex.aura.system.MediaWatcher
import com.nodevortex.aura.tv.Prog
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.ColorKit
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Vertical stack of card rows with column-preserving up/down navigation. */
open class RowsColumn(ctx: Context, private val onExitUp: () -> Unit) : LinearLayout(ctx) {
    private val lastIndex = HashMap<CardRow, Int>()
    init { orientation = VERTICAL; clipChildren = false; clipToPadding = false }

    private fun rows() = (0 until childCount).map { getChildAt(it) }.filterIsInstance<CardRow>().filter { it.visibility == View.VISIBLE && it.count > 0 }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused == null || (direction != FOCUS_UP && direction != FOCUS_DOWN)) return super.focusSearch(focused, direction)
        val rs = rows()
        val cur = rs.indexOfFirst { it.strip.childIndexOf(focused) >= 0 }
        if (cur < 0) return super.focusSearch(focused, direction)
        lastIndex[rs[cur]] = rs[cur].strip.childIndexOf(focused)
        val next = cur + if (direction == FOCUS_DOWN) 1 else -1
        if (next < 0) { onExitUp(); return null }
        if (next >= rs.size) return focused
        val r = rs[next]
        return r.strip.getChildAt((lastIndex[r] ?: 0).coerceIn(0, r.count - 1))
    }

    fun focusFirst(): Boolean {
        val r = rows().firstOrNull() ?: return false
        return r.strip.getChildAt((lastIndex[r] ?: 0).coerceIn(0, r.count - 1)).requestFocus()
    }
}

/**
 * „За вас“: what's worth watching right now, from what this TV can actually see — the COTV guide
 * (live, tonight's films, last week's films for catch-up), what's playing and recent apps.
 * Built once and refreshed lazily, one row per frame, so switching tabs never stutters.
 */
class ForYouPage(private val act: HomeActivity) {
    private val ctx: Context = act
    private val scroll = SpringScrollV(ctx).apply { marginTop = 24.dp; marginBottom = 80.dp; overlapping = false }
    private val col = RowsColumn(ctx) { act.focusTop() }
    val view: View get() = scroll
    private val playing = CardRow(ctx, "Сега върви")
    private val live = CardRow(ctx, "Сега по телевизията")
    private val tonight = CardRow(ctx, "Филми по телевизията днес")
    private val catchUp = CardRow(ctx, "Филми от последните 7 дни · гледайте ги на запис в COTV")
    private val recent = CardRow(ctx, "Наскоро използвани")
    private val more = CardRow(ctx, "Още")
    private var builtAt = 0L
    private var building = false
    private val time = SimpleDateFormat("HH:mm", Locale("bg"))
    private val day = SimpleDateFormat("EEE, d MMM", Locale("bg"))
    private val mediaListener: (MediaWatcher.Now?) -> Unit = { if (scroll.isShown && builtAt > 0) buildPlaying() }
    private var playingKey: String? = null
    private var playingCard: Card? = null

    init {
        col.setPadding(0, 14.dp, 0, 60.dp)
        listOf(playing, live, tonight, catchUp, recent, more).forEach { it.visibility = View.GONE; col.addView(it, lp(MATCH, WRAP).margins(b = 30.dp)) }
        scroll.addView(col)
        MediaWatcher.addListener(mediaListener)
    }

    fun focusEntry() { if (!col.focusFirst()) act.focusTop() }

    fun isStale() = System.currentTimeMillis() - builtAt > 5 * 60_000

    /** Rebuild if stale. [delayMs] lets the tab transition finish first. */
    fun refresh(delayMs: Long = 0, force: Boolean = false) {
        if (building || (!force && !isStale())) return
        building = true
        builtAt = System.currentTimeMillis()
        Aura.io.execute {
            val epg = Aura.app.epg
            val now = System.currentTimeMillis()
            val all = try { epg.all() } catch (_: Throwable) { emptyList() }
            val nowMap = try { epg.nowAll(now) } catch (_: Throwable) { emptyMap() }
            val endOfDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis + 3 * 3600_000L
            val weekAgo = now - 7L * 24 * 3600_000
            val upcoming = ArrayList<Prog>(); val past = ArrayList<Prog>()
            for (p in all) {
                if (!p.isMovie) continue
                if (p.stop > now && p.start < endOfDay) upcoming += p else if (p.stop < now && p.stop > weekAgo) past += p
            }
            val up = upcoming.sortedBy { it.start }.distinctBy { it.name + it.ch }.take(14)
            val pa = past.sortedByDescending { it.start }.distinctBy { it.name }.take(14)
            // Stage the UI work: one row per frame after the transition.
            val steps = listOf<() -> Unit>(
                { buildPlaying() }, { buildLive(nowMap, all.isEmpty()) }, { buildProgs(tonight, up, now, false) },
                { buildProgs(catchUp, pa, now, true) }, { buildRecent() }, { buildMore() },
            )
            fun run(i: Int) {
                if (i >= steps.size) { building = false; return }
                steps[i](); scroll.postOnAnimation { run(i + 1) }
            }
            Aura.main.postDelayed({ run(0) }, delayMs)
        }
    }

    /** Cheap periodic update: only progress bars and "min left", no new views. */
    fun tick() {
        if (isStale()) refresh() else buildPlaying()
    }

    private fun buildPlaying() {
        val n = MediaWatcher.now
        val key = n?.let { it.pkg + "|" + it.title + "|" + it.artist }
        val existing = playingCard
        if (n != null && key == playingKey && existing != null) {
            // same track: just refresh state, no new views
            existing.setBadge(if (n.playing) "ВЪРВИ" else "ПАУЗА")
            if (n.duration > 0) existing.setProgress(n.positionNow().toFloat() / n.duration)
            return
        }
        playingKey = key; playingCard = null
        playing.clear()
        playing.visibility = if (n == null) View.GONE else View.VISIBLE
        if (n == null) return
        val c = Card(ctx, 380.dp, 200.dp, false)
        playingCard = c
        c.setImage(n.art)
        c.title.text = n.title; c.subtitle.text = n.artist
        c.setBadge(if (n.playing) "ВЪРВИ" else "ПАУЗА")
        if (n.duration > 0) c.setProgress(n.positionNow().toFloat() / n.duration)
        n.art?.let { c.accent = ColorKit.accentOf(it) }
        c.onFocused = { act.ambient.setColor(it.accent) }
        c.setOnClickListener { MediaWatcher.playPause(); c.postDelayed({ buildPlaying() }, 400) }
        c.setOnLongClickListener { openPkg(n.pkg); true }
        playing.add(c)
    }

    private fun buildLive(now: Map<String, Prog>, empty: Boolean) {
        live.clear()
        if (empty) {
            live.visibility = View.VISIBLE
            live.add(Card(ctx, 360.dp, 150.dp, false).apply {
                title.text = "Телевизионната програма се изтегля…"; subtitle.text = "След малко тук ще се появят каналите и филмите"
                image.setImageResource(R.drawable.ic_live); image.scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { Aura.net.execute { Aura.app.epg.refresh(); Aura.ui { refresh(force = true) } } }
            })
            return
        }
        val chans = Aura.app.channels
        val shown = HashSet<String>()
        val t = System.currentTimeMillis()
        for (ch in chans.favourites()) {
            val id = ch.epg ?: continue
            val p = now[id] ?: continue
            if (!shown.add(id)) continue
            val c = Card(ctx, 250.dp, 140.dp, true)
            chans.logo(ch) { b -> c.setImage(b); b?.let { c.accent = ColorKit.accentOf(it) } }
            c.title.text = p.name
            c.subtitle.text = "${ch.n}  ${ch.name}  ·  остават ${((p.stop - t) / 60000).coerceAtLeast(1)} мин"
            c.setBadge("НА ЖИВО", live = true)
            c.setProgress((t - p.start).toFloat() / (p.stop - p.start))
            c.onFocused = { act.ambient.setColor(it.accent) }
            c.setOnClickListener { chans.tune(act, ch) }
            live.add(c)
            if (live.count >= 16) break
        }
        live.visibility = if (live.count > 0) View.VISIBLE else View.GONE
    }

    private fun buildProgs(row: CardRow, list: List<Prog>, now: Long, past: Boolean) {
        row.clear()
        row.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val chans = Aura.app.channels
        for (p in list) {
            val ch = chans.primaryFor(p.ch) ?: continue
            val c = Card(ctx, 250.dp, 140.dp, true)
            chans.logo(ch) { b -> c.setImage(b); b?.let { c.accent = ColorKit.accentOf(it) } }
            c.title.text = p.name
            val whenTxt = when { p.isLive(now) -> "Сега"; past -> relativeDay(p.start) + " " + time.format(p.start); else -> time.format(p.start) }
            c.subtitle.text = "$whenTxt  ·  ${ch.n} ${ch.name}"
            if (p.isLive(now)) { c.setBadge("НА ЖИВО", true); c.setProgress((now - p.start).toFloat() / (p.stop - p.start)) }
            else c.setBadge(if (past) relativeDay(p.start).uppercase() else time.format(p.start))
            c.onFocused = { act.ambient.setColor(it.accent) }
            c.setOnClickListener {
                chans.tune(act, ch)
                if (past) toast(act, "Пускам ${ch.name} — намерете „${p.name}“ (${day.format(p.start)}, ${time.format(p.start)}) в архива на COTV")
            }
            row.add(c)
        }
    }

    private fun relativeDay(t: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = t
        return when (today - c.get(Calendar.DAY_OF_YEAR)) { 0 -> "Днес"; 1 -> "Вчера"; else -> day.format(t) }
    }

    private fun buildRecent() {
        recent.clear()
        val repo = Aura.app.apps
        val list = repo.recent(10)
        recent.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (e in list) {
            val t = TileView(ctx, 110.dp).apply { label = e.label; alwaysLabel = true }
            repo.icon(e, 110.dp) { b -> t.bitmap = b; t.accent = e.accent }
            t.setOnFocusChangeListener { _, has -> t.fx.set(has); if (has) act.ambient.setColor(t.accent) }
            t.setOnClickListener { act.launch(e, t) }
            recent.add(t, 26.dp)
        }
    }

    private fun buildMore() {
        if (more.count > 0) return
        more.visibility = View.VISIBLE
        if (!com.nodevortex.aura.system.StockLauncher.disabled(ctx)) more.add(Card(ctx, 300.dp, 150.dp, false).apply {
            image.setImageResource(R.drawable.ic_star); image.scaleType = ImageView.ScaleType.CENTER
            image.setColorFilter(C.text)
            title.text = "Препоръки от Google TV"; subtitle.text = "Предложения от YouTube и Spotify за профила ви"
            accent = 0xFF4285F4.toInt()
            onFocused = { act.ambient.setColor(it.accent) }
            setOnClickListener {
                AuraService.instance?.allowStockHome(60_000)
                try {
                    act.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) { toast(act, "Началният екран на Google TV не е наличен") }
            }
        })
        more.add(Card(ctx, 300.dp, 150.dp, false).apply {
            image.setImageResource(R.drawable.ic_live); image.scaleType = ImageView.ScaleType.CENTER; image.setColorFilter(C.text)
            title.text = "Отвори COTV"; subtitle.text = "Цялата програма, архив и записи"
            accent = 0xFFE53935.toInt()
            onFocused = { act.ambient.setColor(it.accent) }
            setOnClickListener { Aura.app.channels.open(act) }
        })
    }

    private fun openPkg(pkg: String) {
        val pm = act.packageManager
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.let { act.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
