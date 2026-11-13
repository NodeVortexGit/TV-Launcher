package com.nodevortex.aura.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.nodevortex.aura.Aura
import com.nodevortex.aura.HomeActivity
import com.nodevortex.aura.R
import com.nodevortex.aura.bridge.AdbBridge
import com.nodevortex.aura.lock.Lock
import com.nodevortex.aura.lock.RowPinPad
import com.nodevortex.aura.system.ShareServer
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Motion
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins

/** Aura's own settings (Bulgarian UI). */
class SettingsPanel(host: PanelHost, act: HomeActivity) : SheetPanel(host, act, 540.dp) {
    private val prefs = Aura.prefs
    private val bridgeListener: (AdbBridge.State) -> Unit = { renderBridge() }
    private var bridgeRow: ListRow? = null

    init {
        push(main())
        Aura.app.bridge.addListener(bridgeListener)
    }

    override fun onClosed() { Aura.app.bridge.removeListener(bridgeListener) }

    private fun toggle(title: String, sub: String?, key: String, def: Boolean, icon: Int, after: ((Boolean) -> Unit)? = null) =
        ListRow(ctx, title, sub, icon).apply {
            trailingToggle(prefs.getBoolean(key, def))
            setOnClickListener {
                val v = !prefs.getBoolean(key, def)
                prefs.edit().putBoolean(key, v).apply(); trailingToggle(v); after?.invoke(v)
            }
        }

    private fun cycle(title: String, icon: Int, values: List<Int>, current: () -> Int, label: (Int) -> String, set: (Int) -> Unit) =
        ListRow(ctx, title, "◀ ▶ за промяна", icon).apply {
            trailingText(label(current()))
            val step = { d: Int -> val i = (values.indexOf(current()) + d + values.size) % values.size; set(values[i]); trailingText(label(values[i])) }
            setOnClickListener { step(1) }
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }; KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }; else -> false }
            }
        }

    private fun main() = page {
        header("Настройки на Aura", back = false)

        section("Начален екран")
        row(cycle("Приложения на ред", R.drawable.ic_apps, listOf(5, 6, 7), { prefs.getInt("columns", 6) }, { "$it" }) {
            prefs.edit().putInt("columns", it).apply(); act.recreate()
        })
        row(toggle("Имената винаги да се виждат", "Иначе името се показва при избор, като в Google TV", "alwaysLabels", false, R.drawable.ic_tune) { act.rebuildGrid() })
        val hidden = Aura.app.layout.hidden
        row(ListRow(ctx, "Скрити приложения", if (hidden.isEmpty()) "Няма" else "${hidden.size} скрити — отворете, за да ги върнете", R.drawable.ic_hide).apply {
            setOnClickListener { if (hidden.isNotEmpty()) push(hiddenPage()) }
        })
        row(toggle("Бутонът „Начало“ води към Aura", "Задържане на „Начало“ отваря бързите настройки", "homeOverride", true, R.drawable.ic_home))
        val stock = com.nodevortex.aura.system.StockLauncher
        row(ListRow(ctx, "Aura е началният екран по подразбиране",
            if (stock.auraIsDefault(ctx)) "Да — и след рестарт. Google TV се включва само за смяна на профил" else "Не — пуснете tools/make-default.sh от компютъра", R.drawable.ic_check))

        section("Звук, известия и скрийнсейвър")
        row(toggle("Плъзгач за звука на Aura", "Натиснете OK, докато се вижда, за да смените слушалки/тонколони", "customVolume", true, R.drawable.ic_volume))
        row(toggle("Известия върху всяко приложение", null, "popups", true, R.drawable.ic_bell))
        row(toggle("Батерия на слушалките при свързване", "Galaxy Buds показват ляво / дясно / кутия", "budsPopup", true, R.drawable.ic_headphones))
        row(toggle("Музикален скрийнсейвър", "Пулсира в ритъма, когато върви музика и дистанционното не се ползва", "musicSaver", true, R.drawable.ic_music))
        row(cycle("Скрийнсейвърът тръгва след", R.drawable.ic_history, listOf(2, 5, 9), { prefs.getInt("musicSaverMin", 9) }, { "$it мин" }) { prefs.edit().putInt("musicSaverMin", it).apply() })

        section("Профили и заключване")
        row(ListRow(ctx, "Заключване на профила", lockSub(), R.drawable.ic_lock).apply {
            trailingToggle(Lock.enabled)
            setOnClickListener {
                if (Lock.enabled) { Lock.enabled = false; trailingToggle(false); setSubtitle(lockSub()) }
                else host.open(PinPanel(host, PinPanel.Mode.SET) { ok -> if (ok) { Lock.enabled = true; Lock.markUnlocked(); trailingToggle(true); setSubtitle(lockSub()) } })
            }
        })
        if (Lock.pinSet) row(ListRow(ctx, "Смяна на ПИН кода", null, R.drawable.ic_keyboard).apply {
            setOnClickListener { host.open(PinPanel(host, PinPanel.Mode.VERIFY) { ok -> if (ok) host.open(PinPanel(host, PinPanel.Mode.SET) { }) }) }
        })
        val names = Lock.profiles
        if (names.size > 1) row(ListRow(ctx, "Вашият профил", "Само той иска ПИН код", R.drawable.ic_person).apply {
            trailingText(Lock.displayName(Lock.owner))
            setOnClickListener {
                val next = names[(names.indexOfFirst { Lock.same(it, Lock.owner) } + 1).mod(names.size)]
                Lock.owner = next; trailingText(next); act.refreshAvatar()
            }
        })
        row(ListRow(ctx, "Зареждане на профилите от Google TV", if (names.isEmpty()) "Aura ще отвори менюто на Google TV за момент" else names.joinToString(" · "), R.drawable.ic_refresh).apply {
            setOnClickListener { dismiss(); Lock.switchTo(act, null) }
        })

        section("Поверителност")
        row(ListRow(ctx, "Блокиране на тракери (DNS)", dnsSub(), R.drawable.ic_lock).apply {
            trailingToggle(dnsOn())
            setOnClickListener {
                val on = !dnsOn()
                if (!setDns(on)) toast(ctx, "Нужно е еднократно разрешение — пуснете tools/privacy.sh от компютъра")
                trailingToggle(dnsOn()); setSubtitle(dnsSub())
            }
        })
        row(ListRow(ctx, "Данни за употреба и диагностика на Google", "Отваря „Информация“ — изключете „Употреба и диагностика“ там", R.drawable.ic_info).apply {
            setOnClickListener {
                try { act.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Throwable) { act.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
        addView(ctx.label(13f, 420, C.textFaint, 4,
            "Aura помни какво слушате, гледате и търсите само на този телевизор (за по-умно търсене). Нищо не се изпраща навън."),
            lp(MATCH, WRAP).margins(l = 18.dp, r = 18.dp, t = 4.dp))
        row(ListRow(ctx, "Изтриване на историята на Aura", null, R.drawable.ic_delete).apply {
            setOnClickListener {
                java.io.File(Aura.app.filesDir, "signals").deleteRecursively()
                prefs.edit().remove("recentSearches").apply()
                toast(ctx, "Историята е изтрита"); setSubtitle("Изтрита")
            }
        })

        section("Разширени функции")
        bridgeRow = ListRow(ctx, "Свързване със системата", null, R.drawable.ic_bolt).apply { setOnClickListener { connectBridge() } }
        row(bridgeRow!!)
        renderBridge()
        addView(ctx.label(13f, 420, C.textFaint, 6,
            "Позволява на Aura да сменя каналите в COTV, да чисти кеша на приложения, да ги спира и да използва телефона като дистанционно. " +
                "След всеки рестарт на Chromecast трябва да се пусне tools/setup.sh от компютъра. Първия път разрешете „aura@launcher“ на телевизора."),
            lp(MATCH, WRAP).margins(l = 18.dp, r = 18.dp, t = 4.dp))

        section("Изпращане към телевизора")
        row(toggle("Страница за телефона", ShareServer.address(ctx) ?: "Първо се свържете с Wi-Fi", "shareServer", true, R.drawable.ic_cast) { on ->
            if (on) ShareServer.start(ctx) else ShareServer.stop()
        })

        section("Телевизионна програма")
        val epg = Aura.app.epg
        row(ListRow(ctx, "Програмата на COTV", epgSub(), R.drawable.ic_live).apply {
            setOnClickListener { setSubtitle("Обновява се…"); Aura.net.execute { val ok = epg.refresh(); Aura.ui { setSubtitle(if (ok) epgSub() else "Неуспешно обновяване — опитайте по-късно") } } }
        })

        section("Информация")
        row(ListRow(ctx, "Aura 1.1", "Началният екран на този телевизор", R.drawable.ic_info))
    }

    private fun lockSub() = if (Lock.enabled) "Включено — само профилът „${Lock.displayName(Lock.owner)}“ иска ПИН" else "Изключено"

    private fun dnsOn() = Settings.Global.getString(ctx.contentResolver, "private_dns_mode") == "hostname" &&
        Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") == DNS_HOST

    private fun dnsSub() = if (dnsOn()) "Включено — рекламни и следящи адреси се блокират за целия телевизор" else
        "Изключено. Може да наруши историята в YouTube; включете по желание"

    private fun setDns(on: Boolean): Boolean = try {
        val cr = ctx.contentResolver
        if (on) { Settings.Global.putString(cr, "private_dns_specifier", DNS_HOST); Settings.Global.putString(cr, "private_dns_mode", "hostname") }
        else Settings.Global.putString(cr, "private_dns_mode", "opportunistic")
        true
    } catch (_: SecurityException) { false }

    private fun epgSub(): String {
        val epg = Aura.app.epg
        val ago = if (epg.lastUpdate == 0L) "никога" else relBg(System.currentTimeMillis() - epg.lastUpdate)
        return "Обновена $ago · ${epg.daysStored} дни запазени (пази 7 дни за търсене назад)"
    }

    private fun relBg(ms: Long): String {
        val m = ms / 60000
        return when { m < 1 -> "току-що"; m < 60 -> "преди $m мин"; m < 1440 -> "преди ${m / 60} ч"; else -> "преди ${m / 1440} дни" }
    }

    private fun renderBridge() {
        val r = bridgeRow ?: return
        val s = Aura.app.bridge.state
        r.setSubtitle(when (s) {
            AdbBridge.State.READY -> "Свързано — смяната на канали и инструментите работят"
            AdbBridge.State.CONNECTING -> "Свързване…"
            AdbBridge.State.WAITING_APPROVAL -> "Разрешете „aura@launcher“ в прозореца на телевизора"
            AdbBridge.State.OFFLINE, AdbBridge.State.UNKNOWN -> "Не е свързано — натиснете OK"
        })
        if (s == AdbBridge.State.READY) r.trailingIcon(R.drawable.ic_check) else r.trailingText("")
    }

    private fun connectBridge() {
        Aura.app.bridge.connect { ok ->
            toast(ctx, if (ok) "Разширените функции са включени" else "Няма връзка — пуснете tools/setup.sh от компютъра")
        }
    }

    private fun hiddenPage() = page {
        header("Скрити приложения", back = true)
        val repo = Aura.app.apps
        for (key in Aura.app.layout.hidden.toList()) {
            val e = repo[key] ?: continue
            row(ListRow(ctx, e.label, "OK — показване отново", 0, repo.cachedIcon(e, 104.dp)).apply {
                if (repo.cachedIcon(e, 104.dp) == null) repo.icon(e, 104.dp) { b -> icon.setImageBitmap(b) }
                setOnClickListener { Aura.app.layout.unhide(key); act.rebuildGrid(); visibility = View.GONE }
            })
        }
    }

    companion object { const val DNS_HOST = "dns.adguard-dns.com" }
}

/** Full-screen PIN entry using the row pad. SET asks twice; VERIFY checks the stored PIN. */
class PinPanel(host: PanelHost, private val mode: Mode, private val done: (Boolean) -> Unit) : Panel(host) {
    override val homeAlpha = 0f
    enum class Mode { SET, VERIFY }
    private val title = ctx.label(30f, 420, C.text, text = if (mode == Mode.SET) "Изберете 4-цифрен ПИН код" else "Въведете ПИН кода си")
    private val sub = ctx.label(16f, 420, C.textDim, text = if (mode == Mode.SET) "Само вашият профил ще го иска" else "")
    private var first: String? = null
    private var finished = false
    private lateinit var pad: RowPinPad
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    override val view: View = FrameLayout(ctx).apply {
        setBackgroundColor(0xE6090B0F.toInt())
        col.addView(title); col.addView(sub, lp(WRAP, WRAP).margins(t = 8.dp, b = 36.dp))
        pad = RowPinPad(ctx, 4, onComplete = ::complete, onCancel = { finish(false) })
        // explicit WRAP: a default (MATCH) child of a wrap-width column gets squeezed to the title's width
        col.addView(pad, lp(WRAP, WRAP))
        addView(col, flp(WRAP, WRAP, Gravity.CENTER))
    }

    private fun complete(pin: String) {
        when (mode) {
            Mode.VERIFY -> if (Lock.verify(pin)) finish(true) else { sub.text = "Грешен ПИН, опитайте пак"; pad.error() }
            Mode.SET -> if (first == null) { first = pin; title.text = "Въведете го още веднъж"; sub.text = "За потвърждение"; pad.reset() }
                else if (first == pin) { Lock.setPin(pin); finish(true) }
                else { first = null; title.text = "Кодовете не съвпадат"; sub.text = "Изберете 4-цифрен ПИН код"; pad.error() }
        }
    }

    private fun finish(ok: Boolean) { if (finished) return; finished = true; dismiss(); done(ok) }
    override fun onBack(): Boolean { finish(false); return true }
    override fun focusFirst() { pad.requestFocus() }
    override fun animateIn() { col.alpha = 0f; col.translationY = 30.dpf; col.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Motion.decel).start(); view.alpha = 1f }
}

/** Avatar → „Кой гледа?“: the Google TV profiles; only the owner's needs the PIN. One pick switches Google TV too. */
class ProfilesPanel(host: PanelHost, private val act: HomeActivity) : Panel(host) {
    override val homeAlpha = 0.12f
    private val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER; clipChildren = false }
    private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = FrameLayout(ctx).apply {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = 36.dpf; setColor(0xF2161A21.toInt()) }
            setPadding(48.dp, 36.dp, 48.dp, 30.dp); clipChildren = false
            addView(ctx.label(30f, 420, C.text, text = "Кой гледа?"))
            addView(row, lp(WRAP, WRAP).margins(t = 30.dp, b = 26.dp))
            addView(actions, lp(480.dp, WRAP))
        }
        addView(card, flp(WRAP, WRAP, Gravity.CENTER))
    }

    init { build() }

    private fun build() {
        row.removeAllViews(); actions.removeAllViews()
        val names = Lock.profiles.ifEmpty { listOfNotNull(Lock.current ?: Lock.owner) }
        for (name in names) {
            val isActive = Lock.same(name, Lock.active)
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
                val img = ImageView(ctx).apply {
                    setImageBitmap(Lock.avatar(ctx, name, 156)); clipToOutline = true
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF) }
                }
                val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(if (isActive) 2.dp else 0, C.textDim) }
                img.foreground = ring
                addView(img, lp(104.dp, 104.dp))
                addView(ctx.label(17f, 500, C.text, text = name), lp(WRAP, WRAP).margins(t = 12.dp))
                addView(ctx.label(13f, 450, C.textDim, text = when { isActive -> "Активен"; Lock.same(name, Lock.owner) && Lock.enabled -> "С ПИН"; else -> " " }), lp(WRAP, WRAP).margins(t = 2.dp))
                FocusFx.attach(this, 1.1f, extra = { has -> ring.setStroke(if (has) 4.dp else if (isActive) 2.dp else 0, if (has) C.text else C.textDim) })
                setOnClickListener { choose(name) }
            }
            row.addView(v, lp(WRAP, WRAP).margins(l = 22.dp, r = 22.dp))
        }
        if (Lock.profiles.size < 2) actions.addView(ListRow(ctx, "Зареждане на профилите", "Aura ще ги прочете от Google TV (няколко секунди)", R.drawable.ic_refresh).apply {
            setOnClickListener { dismiss(); Lock.switchTo(act, null) }
        })
        actions.addView(ListRow(ctx, "Управление на акаунтите", "Добавяне или премахване на акаунти в Google", R.drawable.ic_settings).apply {
            setOnClickListener { dismiss(); act.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
        })
        if (Lock.enabled) actions.addView(ListRow(ctx, "Заключи сега", "ПИН кодът ще се иска отново", R.drawable.ic_lock).apply {
            setOnClickListener { dismiss(); Lock.onScreenOff(); Lock.show(act) }
        })
        else actions.addView(ListRow(ctx, "Заключване на профила", "Старият ПИН с редове, само за вашия профил", R.drawable.ic_lock).apply {
            setOnClickListener { dismiss(); host.open(PinPanel(host, PinPanel.Mode.SET) { ok -> if (ok) { Lock.enabled = true; act.refreshAvatar(); toast(act, "Заключването е включено") } }) }
        })
    }

    private fun choose(name: String) {
        dismiss()
        if (Lock.same(name, Lock.active)) return
        val go = { Lock.markUnlocked(); Lock.switchTo(act, name) }
        if (Lock.same(name, Lock.owner) && Lock.enabled) host.open(PinPanel(host, PinPanel.Mode.VERIFY) { ok -> if (ok) go() })
        else go()
    }

    override fun focusFirst() {
        val names = Lock.profiles
        (row.getChildAt(names.indexOfFirst { Lock.same(it, Lock.active) }.coerceAtLeast(0)) ?: actions.getChildAt(0))?.requestFocus()
    }
    override fun animateIn() { view.alpha = 0f; view.scaleX = 0.96f; view.scaleY = 0.96f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.decel).start() }
}
