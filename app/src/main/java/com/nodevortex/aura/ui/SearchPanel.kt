package com.nodevortex.aura.ui

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nodevortex.aura.Aura
import com.nodevortex.aura.HomeActivity
import com.nodevortex.aura.R
import com.nodevortex.aura.data.AppEntry
import com.nodevortex.aura.data.Signals
import com.nodevortex.aura.system.MediaWatcher
import com.nodevortex.aura.system.SpotifyLink
import com.nodevortex.aura.tv.Channel
import com.nodevortex.aura.tv.EpgHit
import com.nodevortex.aura.tv.TitleTranslator
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.ColorKit
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Motion
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.Text
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.blend
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Pill showing an app icon + text ("„x“ в Spotify"). */
@SuppressLint("ViewConstructor")
class AppChip(ctx: Context, text: String, icon: Bitmap?, iconRes: Int = 0) : LinearLayout(ctx) {
    private val bg = GradientDrawable().apply { cornerRadius = 100.dpf }
    private val tv = ctx.label(16f, 520, C.text, text = text)
    private var tinted = icon == null
    private val img = ImageView(ctx).apply {
        if (icon != null) setImageBitmap(icon) else if (iconRes != 0) setImageResource(iconRes)
        clipToOutline = true; background = GradientDrawable().apply { cornerRadius = 10.dpf }
    }
    private val spring = Spring(0f, 700f, 1f, 0.002f) { p ->
        bg.setColor(blend(0x1FFFFFFF, C.text, p)); tv.setTextColor(blend(C.text, C.onLight, p))
        scaleX = 1f + 0.05f * p; scaleY = scaleX
        if (tinted) img.setColorFilter(blend(C.text, C.onLight, p))
    }
    fun setIcon(b: Bitmap) { tinted = false; img.clearColorFilter(); img.setImageBitmap(b) }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = bg; setPadding(8.dp, 8.dp, 22.dp, 8.dp)
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        addView(img, lp(36.dp, 36.dp).margins(r = 12.dp))
        addView(tv)
        setOnFocusChangeListener { _, has -> spring.animateTo(if (has) 1f else 0f) }
        spring.snapTo(0f)
    }
}

/** The big "most likely" result at the top of search. */
@SuppressLint("ViewConstructor")
class HeroCard(ctx: Context) : LinearLayout(ctx) {
    val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = GradientDrawable().apply { cornerRadius = 18.dpf; setColor(0x22FFFFFF) } }
    val kicker: TextView = ctx.label(12.5f, 700, C.textDim, text = "НАЙ-ВЕРОЯТНО").apply { letterSpacing = 0.08f }
    val title: TextView = ctx.label(24f, 520, C.text, lines = 2)
    val sub: TextView = ctx.label(15f, 450, C.textDim)
    private val play = ImageView(ctx).apply { setImageResource(R.drawable.ic_play) }
    private val bg = GradientDrawable().apply { cornerRadius = 28.dpf }
    var accent = C.accent
        set(v) { field = v; paint(spring.value) }
    var onFocused: (() -> Unit)? = null
    private val spring = Spring(0f, 600f, 0.85f, 0.002f) { paint(it) }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = bg; setPadding(16.dp, 16.dp, 28.dp, 16.dp)
        isFocusable = true; isClickable = true; isLongClickable = true; defaultFocusHighlightEnabled = false
        addView(art, lp(124.dp, 124.dp).margins(r = 22.dp))
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(kicker); addView(title, lp(WRAP, WRAP).margins(t = 6.dp)); addView(sub, lp(WRAP, WRAP).margins(t = 6.dp))
        }, lp(0, WRAP, 1f))
        addView(play, lp(40.dp, 40.dp).margins(l = 20.dp))
        setOnFocusChangeListener { _, has -> spring.animateTo(if (has) 1f else 0f); if (has) onFocused?.invoke() }
        paint(0f)
    }

    private fun paint(p: Float) {
        bg.setColor(blend(ColorKit.ambient(accent, 0.32f, 0.08f), C.text, p))
        title.setTextColor(blend(C.text, C.onLight, p)); sub.setTextColor(blend(C.textDim, 0xFF4A505C.toInt(), p))
        kicker.setTextColor(blend(C.textDim, 0xFF4A505C.toInt(), p))
        play.setColorFilter(blend(C.text, C.onLight, p))
        scaleX = 1f + 0.035f * p; scaleY = scaleX
    }
}

class SearchPanel(host: PanelHost, private val act: HomeActivity, initial: String?) : Panel(host) {
    override val homeAlpha = 0f

    private val edit = EditText(ctx).apply {
        hint = "Търсете приложения, канали, филми, песни…"
        setSingleLine(true); textSize = 24f
        setTextColor(C.text); setHintTextColor(C.textFaint)
        Fonts.apply(this, 420)
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setText(initial ?: "")
    }
    private val pillBg = GradientDrawable().apply { cornerRadius = 100.dpf; setColor(0x24FFFFFF) }
    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = pillBg; setPadding(26.dp, 0, 10.dp, 0)
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search); setColorFilter(C.textDim) }, lp(28.dp, 28.dp).margins(r = 16.dp))
        addView(edit, lp(0, MATCH, 1f))
    }
    private val mic = Chip(ctx, null, R.drawable.ic_mic, 0x24FFFFFF, square = true).apply { setOnClickListener { voice() } }
    private val status = ctx.label(14.5f, 450, C.textDim)
    private val scroll = SpringScrollV(ctx).apply { marginTop = 20.dp; marginBottom = 80.dp }
    private val col = RowsColumn(ctx) { edit.requestFocus() }
    private val top = CardRow(ctx, "Най-добро съвпадение")
    private val apps = CardRow(ctx, "Приложения")
    private val channels = CardRow(ctx, "Телевизионни канали · COTV")
    private val onTv = CardRow(ctx, "По телевизията · тази седмица")
    private val searchIn = CardRow(ctx, "Търсене в приложенията")
    private val settings = CardRow(ctx, "Настройки")
    private val recents = CardRow(ctx, "Скорошни")
    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(mic, lp(64.dp, 64.dp).margins(r = 16.dp))
        addView(pill, lp(0, 64.dp, 1f))
    }
    override val view: View = FrameLayout(ctx).apply {
        setBackgroundColor(0x99090B0F.toInt())
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, lp(MATCH, WRAP).margins(l = 58.dp, r = 58.dp, t = 40.dp))
            addView(status, lp(WRAP, WRAP).margins(l = 140.dp, t = 10.dp))
            addView(scroll, lp(MATCH, 0, 1f).margins(t = 8.dp))
        }
        addView(v, flp(MATCH, MATCH))
    }
    private var seq = 0
    private var query = ""
    private val time = SimpleDateFormat("HH:mm", Locale("bg"))
    private val day = SimpleDateFormat("EEE, d MMM", Locale("bg"))
    private val run = Runnable { search(edit.text.toString()) }
    /** kind → likelihood for the current query; drives the order of everything. */
    private var odds: Map<String, Float> = emptyMap()

    override fun animateIn() {
        header.alpha = 0f; header.translationY = -24.dpf
        header.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(Motion.decel).start()
        scroll.alpha = 0f; scroll.animate().alpha(1f).setStartDelay(80).setDuration(260).start()
        view.alpha = 0f; view.animate().alpha(1f).setDuration(200).start()
    }

    override fun focusFirst() {
        edit.requestFocus()
        edit.setSelection(edit.text.length)
        edit.postDelayed({ ctx.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }, 150)
    }

    override fun onBack(): Boolean { hideIme(); dismiss(); return true }

    private fun hideIme() = ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(edit.windowToken, 0)

    private fun voice() {
        try {
            act.startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Кажете какво търсите…"), 77)
        } catch (_: Throwable) { toast(ctx, "Гласовото търсене не е налично") }
    }

    override fun onActivityResult(requestCode: Int, data: Intent?) {
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        edit.setText(text); edit.setSelection(text.length); remember(text)
        search(text)
        edit.postDelayed({ col.focusFirst() }, 300)
    }

    private fun remember(q: String) {
        val t = q.trim(); if (t.length < 2) return
        val list = Aura.prefs.getString("recentSearches", "")!!.split('\n').filter { it.isNotBlank() && !it.equals(t, true) }
        Aura.prefs.edit().putString("recentSearches", (listOf(t) + list).take(8).joinToString("\n")).apply()
    }

    private fun chose(kind: String, target: String) { remember(query); Signals.noteChoice(query, kind, target) }

    // ------------------------------------------------------------------------------ search

    private fun search(raw: String) {
        val q = raw.trim()
        query = q
        val my = ++seq
        recents.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        if (q.isEmpty()) {
            buildRecents()
            listOf(top, apps, channels, onTv, searchIn, settings).forEach { it.clear(); it.visibility = View.GONE }
            status.text = "Подсказка: напишете филм на английски или български — VeloCinema проверява програмата на COTV за последните 7 дни"
            return
        }
        val qt = Text.tokens(q).map(Text::squash)
        val foundApps = buildApps(q, qt)
        val foundChannels = buildChannels(q)
        odds = likelihoods(q, qt, foundApps, foundChannels)
        buildTop(q, qt, foundApps, foundChannels)
        buildSearchIn(q)
        buildSettings(qt)
        onTv.clear(); onTv.visibility = View.GONE
        order()
        status.text = "Търся в телевизионната програма…"
        Aura.io.execute {
            val direct = try { Aura.app.epg.search(q, emptyList()) } catch (_: Throwable) { emptyList() }
            Aura.ui { if (my == seq) showEpg(direct, null) }
            Aura.net.execute {
                val tr = try { TitleTranslator.lookup(q) } catch (_: Throwable) { emptyList() }
                if (tr.isEmpty() || my != seq) { Aura.ui { if (my == seq && direct.isEmpty()) status.text = noResultText() }; return@execute }
                val more = try { Aura.app.epg.search(q, tr) } catch (_: Throwable) { emptyList() }
                Aura.ui { if (my == seq) showEpg(more, tr) }
            }
        }
    }

    /**
     * The "probability" part: combine what was played, what was chosen before for similar
     * searches, how much each app is used (and at this hour) and how well things match.
     */
    private fun likelihoods(q: String, qt: List<String>, foundApps: List<Pair<AppEntry, Float>>, foundCh: List<Channel>): Map<String, Float> {
        val aff = Signals.choiceAffinity(qt)
        val m = HashMap<String, Float>()
        val musicWords = Regex("(?i)\\b(song|music|album|feat|ft|remix|песен|песента|музика|албум)\\b").containsMatchIn(q)
        val sp = Signals.bestPlay(qt, MediaWatcher.SPOTIFY)?.second ?: 0f
        val yt = Signals.bestPlay(qt, YT)?.second ?: 0f
        m["spotify"] = 1.6f * sp + Signals.prior(MediaWatcher.SPOTIFY) + 0.8f * (aff["spotify"] ?: 0f) + (if (musicWords) 0.6f else 0f)
        m["youtube"] = 1.6f * yt + Signals.prior(YT) + 0.8f * (aff["youtube"] ?: 0f) + 0.05f
        m["twitch"] = Signals.prior("tv.twitch.android.app") * 0.6f + 0.8f * (aff["twitch"] ?: 0f)
        m["app"] = (foundApps.firstOrNull()?.second ?: 0f) * 1.4f + 0.8f * (aff["app"] ?: 0f)
        m["channel"] = (if (foundCh.isNotEmpty()) 1.2f else 0f) + Signals.prior("bg.comnet.cotv") * 0.5f + 0.8f * (aff["channel"] ?: 0f) +
            (if (q.trim().toIntOrNull() != null) 1f else 0f)
        m["tv"] = Signals.prior("bg.comnet.cotv") * 0.4f + 0.8f * (aff["tv"] ?: 0f)
        return m
    }

    private fun order() {
        val sections = listOf(apps to "app", channels to "channel", onTv to "tv", searchIn to "inapp")
        val inapp = maxOf(odds["spotify"] ?: 0f, odds["youtube"] ?: 0f)
        val sorted = sections.sortedByDescending { (_, k) -> if (k == "inapp") inapp else odds[k] ?: 0f }.map { it.first }
        val wanted = listOf(recents, top) + sorted + settings
        if ((0 until col.childCount).map { col.getChildAt(it) } == wanted) return
        col.removeAllViews()
        wanted.forEach { col.addView(it, lp(MATCH, WRAP).margins(b = 26.dp)) }
    }

    private fun buildTop(q: String, qt: List<String>, foundApps: List<Pair<AppEntry, Float>>, foundCh: List<Channel>) {
        top.clear()
        val best = odds.maxByOrNull { it.value } ?: return
        val hero = HeroCard(ctx)
        hero.onFocused = { act.ambient.setColor(hero.accent) }
        val repo = Aura.app.apps
        val strongApp = foundApps.firstOrNull()?.takeIf { it.second >= 1.2f }
        when {
            strongApp != null && best.key == "app" -> {
                val e = strongApp.first
                hero.title.text = e.label; hero.sub.text = "Отвори приложението"
                repo.icon(e, 124.dp) { b -> hero.art.setImageBitmap(b); hero.accent = e.accent }
                hero.setOnClickListener { chose("app", e.pkg); act.launch(e, hero) }
            }
            best.key == "channel" && foundCh.isNotEmpty() -> {
                val ch = foundCh.first()
                hero.title.text = "${ch.n}  ${ch.name}"; hero.sub.text = "Пусни канала в COTV"
                hero.art.scaleType = ImageView.ScaleType.FIT_CENTER; hero.art.setPadding(14.dp, 14.dp, 14.dp, 14.dp)
                (hero.art.background as GradientDrawable).setColor(0xFFE9EDF3.toInt())
                Aura.app.channels.logo(ch) { b -> if (b != null) { hero.art.setImageBitmap(b); hero.accent = ColorKit.accentOf(b) } }
                hero.setOnClickListener { chose("channel", ch.n.toString()); Aura.app.channels.tune(act, ch) }
            }
            best.key == "youtube" -> {
                val play = Signals.bestPlay(qt, YT)?.first
                hero.title.text = play?.title ?: "„$q“ в YouTube"
                hero.sub.text = if (play != null) "${play.artist} · YouTube · гледано скоро" else "Търсене в YouTube"
                hero.accent = 0xFFFF0033.toInt()
                setHeroArt(hero, play, YT)
                hero.setOnClickListener { chose("youtube", play?.title ?: q); openYouTube(play?.title ?: q) }
            }
            else -> { // Spotify is the default guess for anything that sounds like music
                if (repo.byPackage(MediaWatcher.SPOTIFY) == null) { top.visibility = View.GONE; return }
                val play = Signals.bestPlay(qt, MediaWatcher.SPOTIFY)?.first
                hero.title.text = play?.title ?: "„$q“"
                hero.sub.text = if (play != null) "${play.artist} · Spotify · слушано скоро" else "Пусни най-добрия резултат в Spotify"
                hero.accent = 0xFF1DB954.toInt()
                setHeroArt(hero, play, MediaWatcher.SPOTIFY)
                val searchFor = if (play != null) "${play.title} ${play.artist}" else q
                hero.setOnClickListener { chose("spotify", searchFor); playSpotify(searchFor, play) }
                hero.setOnLongClickListener { openSpotifySearch(searchFor); true }
            }
        }
        top.add(hero)
        top.visibility = View.VISIBLE
    }

    private fun setHeroArt(hero: HeroCard, play: Signals.Play?, pkg: String) {
        val repo = Aura.app.apps
        if (play != null) Aura.io.execute { val b = Signals.art(play); if (b != null) Aura.ui { hero.art.setImageBitmap(b); hero.accent = ColorKit.accentOf(b) } }
        repo.byPackage(pkg)?.let { e -> repo.icon(e, 124.dp) { b -> if (hero.art.drawable == null) hero.art.setImageBitmap(b) } }
    }

    /** Remembered song: straight from its Spotify id. Anything else: Google's assistant plays it on Spotify. */
    private fun playSpotify(q: String, play: Signals.Play? = null) {
        hideIme()
        val uri = play?.uri
        val ok = if (SpotifyLink.isTrack(uri)) SpotifyLink.playTrack(act, uri!!) else SpotifyLink.playQuery(act, q)
        if (!ok) { openSpotifySearch(q); return }
        toast(ctx, "Пускам „${play?.title ?: q}“ в Spotify")
        dismiss()
    }

    private fun openSpotifySearch(q: String) {
        val enc = Uri.encode(q)
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$enc")).setPackage(MediaWatcher.SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { Aura.app.apps.byPackage(MediaWatcher.SPOTIFY)?.let { act.launch(it, null) } }
    }

    private fun openYouTube(q: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))).setPackage(YT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { Aura.app.apps.byPackage(YT)?.let { act.launch(it, null) } }
    }

    private fun noResultText(): String {
        val days = Aura.app.epg.daysStored
        return if (days == 0) "Телевизионната програма още не е изтеглена" else "Нищо в програмата не съвпада (търсено в $days дни)"
    }

    private fun showEpg(hits: List<EpgHit>, translations: List<String>?) {
        onTv.clear()
        val now = System.currentTimeMillis()
        val chans = Aura.app.channels
        for (h in hits) {
            val p = h.prog
            val ch = chans.primaryFor(p.ch) ?: continue
            val c = Card(ctx, 250.dp, 140.dp, true)
            chans.logo(ch) { b -> c.setImage(b); b?.let { c.accent = ColorKit.accentOf(it) } }
            c.title.text = p.name
            val live = p.isLive(now)
            val whenTxt = when { live -> "Сега"; p.start > now -> rel(p.start) + " " + time.format(p.start); else -> "Излъчен " + rel(p.start).lowercase() + " " + time.format(p.start) }
            c.subtitle.text = "$whenTxt · ${ch.n} ${ch.name}"
            when {
                live -> { c.setBadge("НА ЖИВО", true); c.setProgress((now - p.start).toFloat() / (p.stop - p.start)) }
                p.start > now -> c.setBadge("ПРЕДСТОИ")
                else -> c.setBadge("НА ЗАПИС")
            }
            c.onFocused = { act.ambient.setColor(it.accent) }
            c.setOnClickListener {
                chose("tv", p.name)
                chans.tune(act, ch)
                if (!live) toast(ctx, "${ch.name}: „${p.name}“ ${if (p.start > now) "започва" else "беше излъчен"} ${rel(p.start).lowercase()} в ${time.format(p.start)}" +
                    if (p.start < now) " — пуснете го от архива на COTV" else "")
            }
            onTv.add(c)
        }
        onTv.visibility = if (onTv.count > 0) View.VISIBLE else View.GONE
        if (hits.isNotEmpty() && (hits.first().score >= 1.3f)) { odds = odds + ("tv" to (odds["tv"] ?: 0f) + 1.2f); order() }
        val via = hits.mapNotNull { it.via }.firstOrNull()
        status.text = when {
            hits.isEmpty() && translations == null -> "Търся в телевизионната програма…"
            hits.isEmpty() -> noResultText() + if (!translations.isNullOrEmpty()) " (опитах и „${translations.first()}“)" else ""
            via != null -> "Намерено като „$via“ в българската програма"
            else -> "${hits.size} ${if (hits.size == 1) "съвпадение" else "съвпадения"} в програмата за тази седмица"
        }
    }

    private fun rel(t: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = t
        return when (c.get(Calendar.DAY_OF_YEAR) - today) { 0 -> "Днес"; -1 -> "Вчера"; 1 -> "Утре"; else -> day.format(t) }
    }

    private fun buildRecents() {
        recents.clear()
        Aura.prefs.getString("recentSearches", "")!!.split('\n').filter { it.isNotBlank() }.forEach { q ->
            recents.add(AppChip(ctx, q, null, R.drawable.ic_history).apply { setOnClickListener { edit.setText(q); edit.setSelection(q.length); search(q) } }, 12.dp)
        }
        Signals.recentPlays(4, MediaWatcher.SPOTIFY).forEach { p ->
            val chip = AppChip(ctx, "${p.title} · ${p.artist}", null, R.drawable.ic_music)
            Aura.io.execute { Signals.art(p)?.let { b -> Aura.ui { chip.setIcon(b) } } }
            chip.setOnClickListener { query = p.title; chose("spotify", "${p.title} ${p.artist}"); playSpotify("${p.title} ${p.artist}", p) }
            recents.add(chip, 12.dp)
        }
        val repo = Aura.app.apps
        repo.recent(6).forEach { e ->
            val chip = AppChip(ctx, e.label, repo.cachedIcon(e, 104.dp))
            if (repo.cachedIcon(e, 104.dp) == null) repo.icon(e, 104.dp) { b -> chip.setIcon(b) }
            chip.setOnClickListener { act.launch(e, chip) }
            recents.add(chip, 12.dp)
        }
        recents.visibility = if (recents.count > 0) View.VISIBLE else View.GONE
    }

    private fun buildApps(q: String, qt: List<String>): List<Pair<AppEntry, Float>> {
        apps.clear()
        val repo = Aura.app.apps
        val nq = Text.norm(q)
        val found = repo.apps.map { it to Text.score(qt, Text.tokens(it.label).map(Text::squash)) }
            .filter { it.second > 0.6f || Text.norm(it.first.label).contains(nq) }
            .sortedByDescending { it.second + Signals.prior(it.first.pkg) * 0.3f }.take(12)
        for ((e, _) in found) {
            val t = TileView(ctx, 104.dp).apply { label = e.label; alwaysLabel = true }
            repo.icon(e, 104.dp) { b -> t.bitmap = b; t.accent = e.accent }
            t.setOnFocusChangeListener { _, has -> t.fx.set(has); if (has) act.ambient.setColor(t.accent) }
            t.setOnClickListener { chose("app", e.pkg); act.launch(e, t) }
            t.setOnLongClickListener { act.appMenu(e, t, null); true }
            apps.add(t, 24.dp)
        }
        apps.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
        return found
    }

    private fun buildChannels(q: String): List<Channel> {
        channels.clear()
        val chans = Aura.app.channels
        val found = chans.search(q, 20)
        val cards = ArrayList<Pair<Channel, Card>>()
        for (ch in found) {
            val c = Card(ctx, 220.dp, 124.dp, true)
            chans.logo(ch) { b -> c.setImage(b); b?.let { c.accent = ColorKit.accentOf(it) } }
            c.title.text = "${ch.n}  ${ch.name}"
            c.subtitle.text = "COTV"
            c.setBadge(ch.n.toString())
            c.onFocused = { act.ambient.setColor(it.accent) }
            c.setOnClickListener { chose("channel", ch.n.toString()); chans.tune(act, ch) }
            channels.add(c); cards += ch to c
        }
        if (cards.isNotEmpty()) Aura.io.execute {
            val now = System.currentTimeMillis()
            val m = try { Aura.app.epg.nowAll(now) } catch (_: Throwable) { emptyMap() }
            Aura.ui {
                for ((ch, c) in cards) {
                    val p = ch.epg?.let { m[it] } ?: continue
                    c.subtitle.text = "Сега: ${p.name}"; c.setProgress((now - p.start).toFloat() / (p.stop - p.start))
                }
            }
        }
        channels.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
        return found
    }

    private fun buildSearchIn(q: String) {
        searchIn.clear()
        val enc = URLEncoder.encode(q, "UTF-8")
        val repo = Aura.app.apps
        val targets = listOf(
            Triple(MediaWatcher.SPOTIFY, "spotify", "Spotify"),
            Triple(YT, "youtube", "YouTube"),
            Triple("tv.twitch.android.app", "twitch", "Twitch"),
            Triple("com.android.vending", "play", "Play Store"),
        ).sortedByDescending { odds[it.second] ?: 0f }
        for ((pkg, kind, name) in targets) {
            val e = repo.byPackage(pkg) ?: continue
            val chip = AppChip(ctx, "„$q“ в $name", repo.cachedIcon(e, 104.dp))
            if (repo.cachedIcon(e, 104.dp) == null) repo.icon(e, 104.dp) { b -> chip.setIcon(b) }
            chip.setOnClickListener {
                chose(kind, q)
                when (kind) {
                    "spotify" -> openSpotifySearch(q)
                    "youtube" -> openYouTube(q)
                    "twitch" -> try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("twitch://search?query=$enc")).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { act.launch(e, chip) }
                    else -> try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$enc&c=apps")).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { act.launch(e, chip) }
                }
            }
            searchIn.add(chip, 14.dp)
        }
        searchIn.add(AppChip(ctx, "Търсене в Google TV", null, R.drawable.ic_search).apply {
            setOnClickListener {
                chose("google", q)
                try { act.startActivity(Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Throwable) { toast(ctx, "Търсенето на Google не е налично") }
            }
        }, 14.dp)
        searchIn.visibility = View.VISIBLE
    }

    private class S(val title: String, val words: String, val icon: Int, val go: (HomeActivity) -> Unit)

    private val allSettings = listOf(
        S("Wi-Fi", "wifi wi-fi internet network wlan password интернет мрежа парола уайфай", R.drawable.ic_wifi) { it.openQuickSettings("wifi") },
        S("Тест на скоростта", "speed test internet fast скорост тест", R.drawable.ic_speed) { it.openQuickSettings("speed") },
        S("Bluetooth", "bluetooth pair headphones buds speaker remote gamepad блутут слушалки", R.drawable.ic_bluetooth) { it.openQuickSettings("bt") },
        S("Таймер за сън", "sleep timer off night таймер сън изключване", R.drawable.ic_moon) { it.openQuickSettings("sleep") },
        S("Аудиоизход", "audio output sound speakers headphones hdmi звук изход", R.drawable.ic_output) { com.nodevortex.aura.system.AudioOutputs.openSystemPicker(it) },
        S("Дисплей и звук", "display sound picture resolution hdr audio дисплей картина звук", R.drawable.ic_tv) { it.startActivity(Intent("android.settings.SOUND_SETTINGS")) },
        S("Скрийнсейвър", "screensaver ambient photos скрийнсейвър снимки", R.drawable.ic_screensaver) { it.startActivity(Intent("com.google.android.tv.settings.ambient")) },
        S("Профили и акаунти", "account profile google sign профил акаунт", R.drawable.ic_person) { it.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) },
        S("Приложения", "apps applications storage uninstall приложения", R.drawable.ic_apps) { it.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) },
        S("Опции за програмисти", "developer usb debugging adb разработчици програмисти", R.drawable.ic_tune) { it.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
        S("Дата и час", "date time clock час дата", R.drawable.ic_history) { it.startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) },
        S("Клавиатура", "keyboard input ime gboard клавиатура", R.drawable.ic_keyboard) { it.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        S("Информация за устройството", "about device info version update system информация версия", R.drawable.ic_info) { it.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
        S("Настройки на VeloCinema", "velocinema launcher settings columns lock pin volume настройки заключване", R.drawable.ic_settings) { it.panels.open(SettingsPanel(it.panels, it)) },
        S("Всички настройки", "settings настройки", R.drawable.ic_settings) { it.startActivity(Intent(Settings.ACTION_SETTINGS)) },
    )

    private fun buildSettings(qt: List<String>) {
        settings.clear()
        val found = allSettings.filter { s -> Text.score(qt, Text.tokens(s.title + " " + s.words).map(Text::squash)) > 0.6f }
        for (s in found) settings.add(AppChip(ctx, s.title, null, s.icon).apply { setOnClickListener { chose("setting", s.title); hideIme(); dismiss(); s.go(act) } }, 14.dp)
        settings.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
    }

    init {
        col.setPadding(0, 10.dp, 0, 40.dp)
        listOf(recents, top, apps, channels, onTv, searchIn, settings).forEach { it.visibility = View.GONE; col.addView(it, lp(MATCH, WRAP).margins(b = 26.dp)) }
        scroll.addView(col)
        edit.setOnFocusChangeListener { _, has -> pillBg.setStroke(if (has) 2.dp else 0, C.text) }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { edit.removeCallbacks(run); edit.postDelayed(run, 220) }
        })
        edit.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE) { remember(edit.text.toString()); hideIme(); col.focusFirst(); true } else false
        }
        edit.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) { hideIme(); col.focusFirst() } else false
        }
        search(initial ?: "")
    }

    companion object { const val YT = "com.google.android.youtube.tv" }
}
