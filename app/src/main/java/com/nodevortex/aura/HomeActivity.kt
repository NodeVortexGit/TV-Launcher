package com.nodevortex.aura

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nodevortex.aura.data.AppEntry
import com.nodevortex.aura.data.HomeItem
import com.nodevortex.aura.lock.Lock
import com.nodevortex.aura.system.MediaWatcher
import com.nodevortex.aura.ui.AmbientBackground
import com.nodevortex.aura.ui.Chip
import com.nodevortex.aura.ui.FocusFx
import com.nodevortex.aura.ui.FolderPanel
import com.nodevortex.aura.ui.FormPanel
import com.nodevortex.aura.ui.ForYouPage
import com.nodevortex.aura.ui.HomeGrid
import com.nodevortex.aura.ui.MenuPanel
import com.nodevortex.aura.ui.PanelHost
import com.nodevortex.aura.ui.ProfilesPanel
import com.nodevortex.aura.ui.QuickSettingsPanel
import com.nodevortex.aura.ui.SearchPanel
import com.nodevortex.aura.ui.SpringScrollV
import com.nodevortex.aura.ui.TileView
import com.nodevortex.aura.ui.childIndexOf
import com.nodevortex.aura.ui.sectionTitle
import com.nodevortex.aura.ui.toast
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {

    private val app get() = Aura.app
    lateinit var root: FrameLayout
    lateinit var ambient: AmbientBackground
    private lateinit var home: FrameLayout
    lateinit var panels: PanelHost
    private lateinit var topBar: LinearLayout
    private lateinit var avatar: ImageView
    private lateinit var searchChip: Chip
    private lateinit var homeTab: Chip
    private lateinit var forYouTab: Chip
    private lateinit var gear: Chip
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var nowPill: NowPlayingPill
    private lateinit var pages: FrameLayout
    private lateinit var appsScroll: SpringScrollV
    lateinit var grid: HomeGrid
    private var forYou: ForYouPage? = null
    private var tab = 0
    private var tilePx = 0
    private var moving: TileView? = null
    private var lastTopFocus: View? = null
    private var firstBuild = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setBackgroundDrawable(null)
        root = FrameLayout(this)
        ambient = AmbientBackground(this)
        root.addView(ambient, flp(MATCH, MATCH))
        home = FrameLayout(this).apply { clipChildren = false }
        root.addView(home, flp(MATCH, MATCH))
        panels = PanelHost(root, home) { d, a -> dimHome(d, a) }
        buildPages()
        buildTopBar()
        setContentView(root)
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        ambient.setColor(0xFF4A5A7A.toInt(), immediate = true)

        app.apps.addListener(appsChanged)
        app.apps.reload()
        MediaWatcher.addListener(mediaChanged)
        mediaChanged(MediaWatcher.now)
        Lock.addListener(lockChanged)
        registerReceiver(tick, IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        updateClock()
        app.bridge.probe()
        handleIntent(intent)
    }

    override fun onDestroy() {
        app.apps.removeListener(appsChanged)
        MediaWatcher.removeListener(mediaChanged)
        Lock.removeListener(lockChanged)
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        if (Lock.required()) Lock.show(this)
        com.nodevortex.aura.system.StockLauncher.enforce(this)
        compileIfNew()
        if (tab == 1) forYou?.tick()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val open = i?.getStringExtra(EXTRA_OPEN)
        when {
            open == "bt-pair" -> root.post { panels.closeAll(); openQuickSettings("bt-pair") }
            open == "search" -> root.post { openSearch() }
            open == "qs" -> root.post {
                endMove()
                if (panels.top is QuickSettingsPanel) panels.top?.dismiss() else { panels.closeAll(); openQuickSettings(null) }
            }
            open == "learn-profiles" -> root.postDelayed({ Lock.switchTo(this, null) }, 600)
            open == "pin-test" -> root.post { panels.open(com.nodevortex.aura.ui.PinPanel(panels, com.nodevortex.aura.ui.PinPanel.Mode.VERIFY) { }) }
            open == "buds-test" -> com.nodevortex.aura.system.AuraService.instance?.budsPreview(i.getBooleanExtra("three", true))
            open == "real-home-on" -> Aura.prefs.edit().putBoolean("realHome", true).putBoolean("stockPending", false).apply()
            open == "real-home-off" -> Aura.prefs.edit().putBoolean("realHome", false).putBoolean("stockPending", false).apply()
            i?.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_HOME) || i?.getBooleanExtra(EXTRA_HOME_PRESSED, false) == true -> {
                // Home pressed while already home: behave like Google TV and reset to the top.
                if (hasWindowFocus() || panels.stack.isNotEmpty()) {
                    endMove()
                    panels.closeAll()
                    selectTab(0, focus = true)
                    appsScroll.scrollToTop()
                    grid.focusIndex(0)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- top bar

    private fun buildTopBar() {
        topBar = object : LinearLayout(this) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                if (direction == View.FOCUS_DOWN) { focusPage(); return null }
                if (direction == View.FOCUS_UP) return focused
                return super.focusSearch(focused, direction)
            }
            override fun requestChildFocus(child: View?, focused: View?) {
                super.requestChildFocus(child, focused); lastTopFocus = focused
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(58.dp, 0, 58.dp, 0)
        }
        avatar = ImageView(this).apply {
            setImageResource(R.drawable.avatar_default)
            isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(0, C.text) }
            foreground = ring
            FocusFx.attach(this, 1.12f, extra = { has -> ring.setStroke(if (has) 3.dp else 0, C.text) })
            setOnClickListener { panels.open(ProfilesPanel(panels, this@HomeActivity)) }
        }
        refreshAvatar()
        topBar.addView(avatar, lp(50.dp, 50.dp).margins(r = 18.dp))

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 100.dpf; setColor(0x1AFFFFFF) }
            setPadding(6.dp, 6.dp, 6.dp, 6.dp); clipChildren = false
        }
        searchChip = Chip(this, null, R.drawable.ic_search, square = true).apply { setOnClickListener { openSearch() } }
        homeTab = Chip(this, "Начало").apply { setOnFocusChangeListener2 { if (it) selectTabSoon(0) }; setOnClickListener { selectTab(0); focusPage() } }
        forYouTab = Chip(this, "За вас").apply { setOnFocusChangeListener2 { if (it) selectTabSoon(1) }; setOnClickListener { selectTab(1); focusPage() } }
        pill.addView(searchChip, lp(50.dp, 50.dp))
        pill.addView(homeTab, lp(WRAP, 50.dp).margins(l = 4.dp))
        pill.addView(forYouTab, lp(WRAP, 50.dp).margins(l = 4.dp))
        topBar.addView(pill, lp(WRAP, WRAP))
        topBar.addView(View(this), lp(0, 1, 1f))

        nowPill = NowPlayingPill(this).apply { visibility = View.GONE }
        topBar.addView(nowPill, lp(WRAP, 58.dp).margins(r = 26.dp))

        val clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clock = label(44f, 300, C.text).apply { letterSpacing = -0.01f }
        date = label(14.5f, 450, C.textDim)
        clockBox.addView(clock, lp(WRAP, WRAP))
        clockBox.addView(date, lp(WRAP, WRAP).margins(t = 2.dp))
        topBar.addView(clockBox, lp(WRAP, WRAP).margins(r = 22.dp))

        gear = Chip(this, null, R.drawable.ic_settings, 0x1AFFFFFF, square = true).apply { setOnClickListener { openQuickSettings(null) } }
        topBar.addView(gear, lp(56.dp, 56.dp))
        home.addView(topBar, flp(MATCH, 110.dp, Gravity.TOP).margins(t = 18.dp))
        homeTab.selectedTab = true
    }

    /** Panels open: freeze the home screen into one GPU layer (or hide it) instead of re-rendering it translucent every frame. */
    private fun dimHome(dim: Float, alpha: Float) {
        ambient.setDim(dim)
        home.animate().cancel()
        if (alpha >= 1f) {
            home.visibility = View.VISIBLE
            home.animate().alpha(1f).setDuration(200).withEndAction { home.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        } else {
            home.visibility = View.VISIBLE
            home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            home.animate().alpha(alpha).setDuration(220).withEndAction { if (alpha == 0f) home.visibility = View.INVISIBLE }.start()
        }
    }

    private fun Chip.setOnFocusChangeListener2(f: (Boolean) -> Unit) {
        val prev = onFocusChangeListener
        setOnFocusChangeListener { v, has -> prev?.onFocusChange(v, has); f(has) }
    }

    fun refreshAvatar() {
        val acc = Lock.active
        avatar.setImageBitmap(Lock.avatar(this, acc, 156))
        avatar.clipToOutline = true
        avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF) }
    }

    private fun updateClock() {
        clock.text = DateFormat.format(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", Date())
        val pattern = DateFormat.getBestDateTimePattern(Locale("bg"), "EEEEdMMMM")
        date.text = DateFormat.format(pattern, Date()).toString().replaceFirstChar { it.uppercase() }
    }

    private val tick = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = updateClock() }

    // ------------------------------------------------------------------------------- pages

    private fun buildPages() {
        pages = FrameLayout(this).apply { clipChildren = true }
        home.addView(pages, flp(MATCH, MATCH).margins(t = 128.dp))

        appsScroll = SpringScrollV(this).apply { marginTop = 30.dp; marginBottom = 60.dp; overlapping = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(58.dp, 14.dp, 58.dp, 60.dp) }
        col.addView(sectionTitle("Вашите приложения"), lp(WRAP, WRAP).margins(b = 18.dp))
        grid = HomeGrid(this)
        grid.onExitUp = { focusTop(); true }
        grid.onFocusIndex = { _, v -> onTileFocused(v as TileView) }
        col.addView(grid, lp(MATCH, WRAP))
        appsScroll.addView(col)
        pages.addView(appsScroll, flp(MATCH, MATCH))
        applyGridMetrics()
    }

    private fun applyGridMetrics() {
        val cols = Aura.prefs.getInt("columns", 6)
        val screenW = resources.displayMetrics.widthPixels
        val gap = (if (cols >= 7) 24 else 28).dp
        val cell = (screenW - 116.dp - (cols - 1) * gap) / cols
        grid.columns = cols; grid.cell = cell; grid.gapX = gap; grid.gapY = 16.dp
        tilePx = cell
    }

    fun focusTop() {
        val target = lastTopFocus?.takeIf { it.isShown } ?: if (tab == 0) homeTab else forYouTab
        target.requestFocus()
    }

    fun focusPage() {
        if (tab == 0) { if (grid.childCount > 0) grid.focusIndex(grid.lastFocused) } else forYou?.focusEntry()
    }

    private var pendingTab = -1
    private val tabRunnable = Runnable { if (pendingTab >= 0) selectTab(pendingTab) }

    /** Tabs switch on focus like Google TV, but only once focus rests (no churn when passing over). */
    private fun selectTabSoon(t: Int) {
        pendingTab = t
        root.removeCallbacks(tabRunnable)
        if (t != tab) root.postDelayed(tabRunnable, 160)
    }

    private fun selectTab(t: Int, focus: Boolean = false) {
        root.removeCallbacks(tabRunnable); pendingTab = -1
        if (focus) (if (t == 0) homeTab else forYouTab).requestFocus()
        if (t == tab) return
        tab = t
        homeTab.selectedTab = t == 0; forYouTab.selectedTab = t == 1
        val incoming: View
        val outgoing: View
        if (t == 1) {
            val fy = forYou ?: ForYouPage(this).also { forYou = it; pages.addView(it.view, flp(MATCH, MATCH)) }
            fy.refresh(delayMs = 340)
            incoming = fy.view; outgoing = appsScroll
        } else {
            incoming = appsScroll; outgoing = forYou!!.view
        }
        val dir = if (t == 1) 1 else -1
        outgoing.animate().cancel(); incoming.animate().cancel()
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f; incoming.translationX = 60.dpf * dir
        incoming.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(com.nodevortex.aura.util.Motion.decel).start()
        outgoing.animate().alpha(0f).translationX(-40.dpf * dir).setDuration(140).withEndAction { outgoing.visibility = View.INVISIBLE }.start()
        if (t == 0) grid.getChildAt(grid.lastFocused)?.let { onTileFocused(it as TileView) }
    }

    /** After an over-Wi-Fi update, ask ART (via the shell bridge) to compile Aura fully to native code. */
    private fun compileIfNew() {
        val v = try { packageManager.getPackageInfo(packageName, 0).longVersionCode } catch (_: Throwable) { return }
        if (Aura.prefs.getLong("compiledVersion", -1) == v || !app.bridge.ready) return
        Aura.prefs.edit().putLong("compiledVersion", v).apply()
        Aura.main.postDelayed({ app.bridge.shell("cmd package compile -m speed -f $packageName", 180_000) }, 20_000)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) app.epg.trim()
    }

    // ------------------------------------------------------------------------------- grid

    private val appsChanged: () -> Unit = { rebuildGrid() }

    fun rebuildGrid(focusId: String? = null) {
        val apps = app.apps.apps
        if (apps.isEmpty()) return
        app.layout.sync(apps)
        val hadFocus = grid.findFocus() != null
        val keepId = focusId ?: (grid.getChildAt(grid.lastFocused)?.tag as? String)
        val existing = HashMap<String, TileView>()
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as TileView).let { existing[it.tag as String] = it }
        val before = grid.snapshot()
        grid.removeAllViews()
        val always = Aura.prefs.getBoolean("alwaysLabels", false)
        for (item in app.layout.items) {
            val tile = existing.remove(item.id)?.takeIf { it.tileSize == tilePx } ?: TileView(this, tilePx)
            tile.tag = item.id
            tile.alwaysLabel = always
            bindTile(tile, item)
            grid.addView(tile)
        }
        grid.animateFrom(before)
        val idx = app.layout.items.indexOfFirst { it.id == keepId }.coerceAtLeast(0)
        if (firstBuild && panels.stack.isEmpty()) { firstBuild = false; grid.post { grid.focusIndex(0) } }
        else if (hadFocus || focusId != null) grid.post { grid.focusIndex(idx) }
        else if (findViewById<View>(android.R.id.content).findFocus() == null && panels.stack.isEmpty()) grid.post { grid.focusIndex(idx) }
    }

    private fun bindTile(tile: TileView, item: HomeItem) {
        when (item) {
            is HomeItem.App -> {
                val e = app.apps[item.key] ?: return
                tile.isFolder = false
                tile.label = e.label
                tile.badge = null
                app.apps.icon(e, tilePx) { b -> if (tile.tag == item.id) { tile.bitmap = b; tile.accent = e.accent; if (tile.isFocused) ambient.setColor(e.accent) } }
                tile.setOnClickListener { launch(e, tile) }
                tile.setOnLongClickListener { appMenu(e, tile, null); true }
            }
            is HomeItem.Folder -> {
                tile.isFolder = true
                tile.label = item.name
                tile.badge = item.apps.size.toString()
                val entries = item.apps.mapNotNull { app.apps[it] }
                val minis = arrayOfNulls<Bitmap>(minOf(4, entries.size))
                entries.take(4).forEachIndexed { i, e ->
                    app.apps.icon(e, tilePx) { b -> minis[i] = b; tile.minis = minis.toList(); if (i == 0) tile.accent = e.accent }
                }
                tile.setOnClickListener { openFolder(item) }
                tile.setOnLongClickListener { folderMenu(item, tile); true }
            }
        }
    }

    private fun onTileFocused(t: TileView) {
        if (t.accent != C.accent || t.bitmap != null || t.minis.isNotEmpty()) ambient.setColor(t.accent)
        val loc = IntArray(2); t.getLocationOnScreen(loc)
        ambient.setFocusX((loc[0] + t.width / 2f) / resources.displayMetrics.widthPixels)
    }

    fun launch(e: AppEntry, from: View?) {
        app.apps.noteLaunch(e)
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try { startActivity(app.apps.launchIntent(e), opts) } catch (t: Throwable) { toast(this, "${e.label} не може да се отвори") }
    }

    private fun openFolder(f: HomeItem.Folder) = panels.open(FolderPanel(panels, this, f))

    // ------------------------------------------------------------------------------- menus

    fun appMenu(e: AppEntry, anchor: View, inFolder: HomeItem.Folder?) {
        val b = app.bridge
        val keep = Aura.prefs.getBoolean("keep:${e.pkg}", false)
        val items = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_launch, "Отваряне") { launch(e, anchor) },
        )
        if (inFolder == null) items += Triple(R.drawable.ic_move, "Преместване") { startMove(anchor as TileView) }
        items += Triple(R.drawable.ic_folder, if (inFolder == null) "Добавяне в папка…" else "Преместване в друга папка…") { folderPicker(e) }
        if (inFolder != null) items += Triple(R.drawable.ic_apps, "Изваждане от папката") {
            app.layout.removeFromFolder(inFolder, e.key); panels.top?.dismiss(); rebuildGrid(e.key)
        }
        items += Triple(R.drawable.ic_sweep, "Изчистване на кеша") { shellAction(e, "pm clear --cache-only ${e.pkg}", "Кешът на ${e.label} е изчистен") }
        items += Triple(R.drawable.ic_stop, "Принудително спиране") { shellAction(e, "am force-stop ${e.pkg}", "${e.label} е спряно — паметта е освободена") }
        items += Triple(R.drawable.ic_bolt, if (keep) "Без работа във фонов режим" else "Работа във фонов режим") { toggleKeep(e, !keep) }
        items += Triple(R.drawable.ic_hide, "Скриване от началния екран") { app.layout.hide(e.key); if (inFolder != null) panels.top?.dismiss(); rebuildGrid() }
        items += Triple(R.drawable.ic_info, "Информация за приложението") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
        }
        if (!e.isSystem) items += Triple(R.drawable.ic_delete, "Деинсталиране") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
        }
        val heading = if (b.ready) e.label else "${e.label}"
        panels.open(MenuPanel(panels, anchor, heading, items))
    }

    private fun shellAction(e: AppEntry, cmd: String, done: String) {
        val b = app.bridge
        if (!b.ready) {
            toast(this, "Нужни са разширените функции (Настройки на Aura → Разширени функции)")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
            return
        }
        b.shell(cmd) { out -> toast(this, if (out != null) done else "Не се получи в момента") }
    }

    private fun toggleKeep(e: AppEntry, on: Boolean) {
        val b = app.bridge
        if (!b.ready) { toast(this, "Нужни са разширените функции (Настройки на Aura → Разширени функции)"); return }
        val cmd = if (on) "cmd deviceidle whitelist +${e.pkg}; cmd appops set ${e.pkg} RUN_ANY_IN_BACKGROUND allow; am set-standby-bucket ${e.pkg} active"
        else "cmd deviceidle whitelist -${e.pkg}; am set-standby-bucket ${e.pkg} working_set"
        b.shell(cmd) { out ->
            if (out != null) {
                Aura.prefs.edit().putBoolean("keep:${e.pkg}", on).apply()
                toast(this, if (on) "${e.label} няма да бъде ограничавано във фонов режим (при недостиг на памет телевизорът пак може да го затвори)" else "${e.label} работи нормално")
            }
        }
    }

    private fun folderPicker(e: AppEntry) {
        val folders = app.layout.items.filterIsInstance<HomeItem.Folder>().filter { e.key !in it.apps }
        val items = mutableListOf<Triple<Int, String, () -> Unit>>(Triple(R.drawable.ic_add, "Нова папка…") { newFolderFor(e) })
        folders.forEach { f -> items += Triple(R.drawable.ic_folder, f.name) {
            val wasIn = app.layout.folderOf(e.key)
            app.layout.addToFolder(f, e.key)
            if (wasIn != null) panels.top?.dismiss()
            rebuildGrid(f.id); toast(this, "Добавено в „${f.name}“")
        } }
        panels.open(MenuPanel(panels, null, "Добави ${e.label} в", items))
    }

    private fun newFolderFor(e: AppEntry) {
        val suggestion = when {
            e.pkg.contains("game", true) || e.label.contains("ball", true) -> "Игри"
            e.pkg.contains("anydesk") || e.pkg.contains("termux") || e.pkg.contains("localsend") -> "Инструменти"
            else -> "Папка"
        }
        panels.open(FormPanel(panels, "Нова папка", "Дайте име на папката", listOf(FormPanel.Field("Име", suggestion)), "Създаване") { v ->
            val name = v[0].trim().ifEmpty { suggestion }
            val wasIn = app.layout.folderOf(e.key)
            if (wasIn != null) { app.layout.removeFromFolder(wasIn, e.key, reinsert = true); panels.top?.dismiss() }
            val f = app.layout.newFolder(name, e.key)
            rebuildGrid(f.id)
        })
    }

    private fun folderMenu(f: HomeItem.Folder, anchor: TileView) {
        panels.open(MenuPanel(panels, anchor, f.name, listOf(
            Triple(R.drawable.ic_launch, "Отваряне") { openFolder(f) },
            Triple(R.drawable.ic_tune, "Преименуване") {
                panels.open(FormPanel(panels, "Преименуване на папката", null, listOf(FormPanel.Field("Име", f.name)), "Запазване") { v ->
                    f.name = v[0].trim().ifEmpty { f.name }; app.layout.save(); rebuildGrid(f.id)
                })
            },
            Triple(R.drawable.ic_move, "Преместване") { startMove(anchor) },
            Triple(R.drawable.ic_apps, "Разгрупиране") { app.layout.ungroup(f); rebuildGrid() },
        )))
    }

    // ------------------------------------------------------------------------------- move mode

    private fun startMove(t: TileView) {
        moving = t; t.lifted = true; t.requestFocus()
        toast(this, "Местете със стрелките · OK за край")
    }

    private fun endMove() {
        moving?.lifted = false; moving = null
        app.layout.save()
    }

    private fun moveBy(code: Int) {
        val t = moving ?: return
        val from = grid.indexOfChild(t)
        val cols = grid.columns
        val to = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> from - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> from + 1
            KeyEvent.KEYCODE_DPAD_UP -> from - cols
            KeyEvent.KEYCODE_DPAD_DOWN -> from + cols
            else -> from
        }.coerceIn(0, grid.childCount - 1)
        if (to == from) return
        val before = grid.snapshot()
        app.layout.items.add(to, app.layout.items.removeAt(from))
        grid.removeView(t); grid.addView(t, to)
        grid.animateFrom(before)
        t.post { t.requestFocus() }
    }

    // ------------------------------------------------------------------------------- panels

    fun openSearch(initial: String? = null) = panels.open(SearchPanel(panels, this, initial))

    fun openQuickSettings(page: String?) = panels.open(QuickSettingsPanel(panels, this, page))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (moving != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { moveBy(event.keyCode); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { endMove(); return true }
            }
        }
        if (moving != null && event.action == KeyEvent.ACTION_UP) return true
        panels.top?.let { if (it.onKey(event)) return true }
        if (event.action == KeyEvent.ACTION_DOWN && panels.stack.isEmpty()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SEARCH -> { openSearch(); return true }
                KeyEvent.KEYCODE_SETTINGS -> { openQuickSettings(null); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("TV back handling")
    override fun onBackPressed() {
        if (panels.back()) return
        when {
            tab == 1 -> selectTab(0, focus = true)
            grid.findFocus() != null && grid.lastFocused > 0 -> { appsScroll.scrollToTop(); grid.focusIndex(0) }
            else -> focusTop()
        }
    }

    @Deprecated("forwarded to panels")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        panels.top?.onActivityResult(requestCode, if (resultCode == RESULT_OK) data else null)
    }

    // ------------------------------------------------------------------------------- now playing

    private val mediaChanged: (MediaWatcher.Now?) -> Unit = { n -> nowPill.bind(n) }
    private val lockChanged: () -> Unit = { refreshAvatar() }

    companion object {
        const val EXTRA_OPEN = "aura.open"
        const val EXTRA_HOME_PRESSED = "aura.home"
    }
}

/** Top-right "now playing" pill: art, title, artist and animated equaliser bars. OK = play/pause. */
class NowPlayingPill(private val act: HomeActivity) : LinearLayout(act) {
    private val art = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = GradientDrawable().apply { cornerRadius = 12.dpf; setColor(0x22FFFFFF) } }
    private val title = act.label(15f, 560, C.text)
    private val sub = act.label(13f, 420, C.textDim)
    private val eq = Eq(act)
    private val bg = GradientDrawable().apply { cornerRadius = 100.dpf }
    private val spring = Spring(0f, 700f, 1f, 0.002f) { p ->
        bg.setColor(com.nodevortex.aura.util.blend(0x1AFFFFFF, C.text, p))
        title.setTextColor(com.nodevortex.aura.util.blend(C.text, C.onLight, p))
        sub.setTextColor(com.nodevortex.aura.util.blend(C.textDim, 0xFF4A505C.toInt(), p))
        eq.color = com.nodevortex.aura.util.blend(C.text, C.onLight, p)
        scaleX = 1f + 0.05f * p; scaleY = scaleX
    }
    private var now: MediaWatcher.Now? = null

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = bg; setPadding(7.dp, 7.dp, 20.dp, 7.dp)
        isFocusable = true; isClickable = true; isLongClickable = true; defaultFocusHighlightEnabled = false
        addView(art, lp(44.dp, 44.dp).margins(r = 12.dp))
        addView(LinearLayout(act).apply {
            orientation = VERTICAL
            addView(title, lp(WRAP, WRAP)); addView(sub, lp(WRAP, WRAP).margins(t = 2.dp))
        }, lp(WRAP, WRAP))
        addView(eq, lp(22.dp, 18.dp).margins(l = 14.dp))
        title.maxWidth = 230.dp; sub.maxWidth = 230.dp
        setOnFocusChangeListener { _, has -> spring.animateTo(if (has) 1f else 0f) }
        setOnClickListener { MediaWatcher.playPause() }
        setOnLongClickListener { now?.let { n -> act.packageManager.getLaunchIntentForPackage(n.pkg) ?: act.packageManager.getLeanbackLaunchIntentForPackage(n.pkg) }?.let { act.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; true }
        spring.snapTo(0f)
    }

    private var boundKey: String? = null

    fun bind(n: MediaWatcher.Now?) {
        now = n
        if (n == null) {
            boundKey = null
            if (visibility == VISIBLE) animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start()
            return
        }
        // Spotify reports state changes often; only touch text/art (and trigger a relayout) on a new track.
        val key = n.pkg + "|" + n.title + "|" + n.artist + "|" + System.identityHashCode(n.art)
        if (key != boundKey) {
            boundKey = key
            title.text = n.title; sub.text = n.artist.ifEmpty { appName(n.pkg) }
            if (n.art != null) art.setImageBitmap(n.art) else art.setImageResource(R.drawable.ic_music)
        }
        eq.playing = n.playing
        if (visibility != VISIBLE) { visibility = VISIBLE; alpha = 0f; translationX = 30.dpf; animate().alpha(1f).translationX(0f).setDuration(320).start() }
    }

    private fun appName(pkg: String) = try { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { "" }

    private class Eq(ctx: Context) : View(ctx) {
        var playing = false
            set(v) { if (field != v) { field = v; invalidate() } }
        var color = C.text
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t0 = System.nanoTime()
        override fun onDraw(c: Canvas) {
            p.color = color
            val t = (System.nanoTime() - t0) / 1e9f
            val bw = width / 5f
            for (i in 0 until 3) {
                val h = if (playing) (0.35f + 0.65f * ((kotlin.math.sin(t * (6.3f + i * 2.1f) + i * 1.7f) + 1f) / 2f)) else 0.25f
                val x = i * bw * 2f
                c.drawRoundRect(x, height * (1f - h), x + bw, height.toFloat(), bw / 2, bw / 2, p)
            }
            if (playing && isShown && hasWindowFocus()) postInvalidateDelayed(83)
        }
    }
}

@Suppress("unused") private fun keepGroup(v: ViewGroup) = v.childIndexOf(v)
@Suppress("unused") private val keepFonts = Fonts
