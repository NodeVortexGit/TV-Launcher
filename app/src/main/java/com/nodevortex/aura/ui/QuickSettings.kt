package com.nodevortex.aura.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nodevortex.aura.Aura
import com.nodevortex.aura.HomeActivity
import com.nodevortex.aura.R
import com.nodevortex.aura.lock.Lock
import com.nodevortex.aura.system.AudioOutputs
import com.nodevortex.aura.system.Bt
import com.nodevortex.aura.system.NotificationHub
import com.nodevortex.aura.system.SleepTimer
import com.nodevortex.aura.system.SpeedTest
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.blend
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins
import java.util.Date
import java.util.Locale

/** Right-hand sheet with a stack of pages that slide horizontally. */
abstract class SheetPanel(host: PanelHost, protected val act: HomeActivity, width: Int = 440.dp) : Panel(host) {
    protected val sheet = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { cornerRadius = 34.dpf; setColor(0xFF15181E.toInt()); setStroke(1.dp, 0x14FFFFFF) }
        clipToOutline = true
    }
    override val view: View = FrameLayout(ctx).apply { addView(sheet, flp(width, MATCH, Gravity.END).margins(t = 22.dp, b = 22.dp, r = 22.dp)) }
    override val dims = true
    private val pages = ArrayList<View>()
    private val slide = Spring(1f, 300f, 0.9f, 0.001f) { p -> sheet.translationX = p * (width + 40.dp); sheet.alpha = 1f - p * 0.6f }

    override fun animateIn() { slide.snapTo(1f); slide.animateTo(0f) }
    override fun animateOut(end: () -> Unit) { slide.onSettled = { end() }; slide.animateTo(1f) }

    protected fun page(build: LinearLayout.() -> Unit): SpringScrollV {
        val s = SpringScrollV(ctx).apply { marginTop = 30.dp; marginBottom = 40.dp; setFadingEdgeLength(28.dp) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(22.dp, 26.dp, 22.dp, 30.dp); clipChildren = false }
        col.build()
        s.addView(col)
        return s
    }

    protected fun push(v: View) {
        val prev = pages.lastOrNull()
        pages += v
        sheet.addView(v, flp(MATCH, MATCH))
        if (prev != null) {
            prev.animate().translationX(-60.dpf).alpha(0f).setDuration(200).withEndAction { prev.visibility = View.INVISIBLE }.start()
            v.alpha = 0f; v.translationX = 80.dpf
            v.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(com.nodevortex.aura.util.Motion.decel).start()
        }
        v.post { (v as? ViewGroup2)?.focusFirst() ?: firstFocusable(v)?.requestFocus() }
    }

    protected fun pop(): Boolean {
        if (pages.size <= 1) return false
        val top = pages.removeAt(pages.size - 1)
        onPagePopped(top)
        val prev = pages.last()
        top.animate().translationX(80.dpf).alpha(0f).setDuration(180).withEndAction { sheet.removeView(top) }.start()
        prev.visibility = View.VISIBLE
        prev.animate().translationX(0f).alpha(1f).setDuration(260).start()
        prev.post { firstFocusable(prev)?.requestFocus() }
        return true
    }

    open fun onPagePopped(v: View) {}

    override fun onBack(): Boolean { if (!pop()) dismiss(); return true }
    override fun focusFirst() { pages.lastOrNull()?.let { firstFocusable(it)?.requestFocus() } }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    interface ViewGroup2 { fun focusFirst() }

    protected fun LinearLayout.header(title: String, back: Boolean) {
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        if (back) row.addView(Chip(ctx, null, R.drawable.ic_back, 0x1AFFFFFF, square = true).apply { setOnClickListener { pop() } }, lp(48.dp, 48.dp).margins(r = 14.dp))
        row.addView(ctx.label(26f, 450, C.text, text = title))
        addView(row, lp(MATCH, WRAP).margins(b = 18.dp))
    }

    protected fun LinearLayout.section(title: String) = addView(ctx.sectionTitle(title), lp(WRAP, WRAP).margins(l = 18.dp, t = 22.dp, b = 8.dp))
    protected fun LinearLayout.row(r: View) = addView(r, lp(MATCH, WRAP).margins(b = 4.dp))
}

/** Square-ish tile used on the main quick-settings page. */
@SuppressLint("ViewConstructor")
class QsTile(ctx: Context, iconRes: Int, title: String) : LinearLayout(ctx) {
    private val icon = ImageView(ctx).apply { setImageResource(iconRes) }
    private val t = ctx.label(16.5f, 560, C.text, text = title)
    val sub: TextView = ctx.label(13.5f, 420, C.textDim)
    var on = false
        set(v) { field = v; paint(spring.value) }
    private val bg = GradientDrawable().apply { cornerRadius = 24.dpf }
    private val spring = Spring(0f, 700f, 1f, 0.002f) { paint(it) }

    init {
        orientation = VERTICAL
        background = bg; setPadding(18.dp, 16.dp, 14.dp, 14.dp)
        isFocusable = true; isClickable = true; isLongClickable = true; defaultFocusHighlightEnabled = false
        addView(icon, lp(26.dp, 26.dp).margins(b = 14.dp))
        addView(t); addView(sub, lp(WRAP, WRAP).margins(t = 2.dp))
        setOnFocusChangeListener { _, has -> spring.animateTo(if (has) 1f else 0f) }
        paint(0f)
    }

    private fun paint(p: Float) {
        val idle = if (on) 0xFF2F3A52.toInt() else C.surface2
        bg.setColor(blend(idle, C.text, p))
        val fg = blend(C.text, C.onLight, p)
        t.setTextColor(fg); sub.setTextColor(blend(C.textDim, 0xFF4A505C.toInt(), p))
        icon.setColorFilter(if (on && p < 0.5f) C.accent else fg)
        scaleX = 1f + 0.04f * p; scaleY = scaleX
    }
}

@SuppressLint("MissingPermission")
class QuickSettingsPanel(host: PanelHost, act: HomeActivity, private val startPage: String?) : SheetPanel(host, act) {

    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private lateinit var wifiTile: QsTile
    private lateinit var btTile: QsTile
    private lateinit var sleepTile: QsTile
    private lateinit var outTile: QsTile
    private var notifBox: LinearLayout? = null
    private val notifListener: () -> Unit = { renderNotifications() }

    override fun onClosed() {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
        NotificationHub.removeListener(notifListener)
        try { Bt.adapter?.cancelDiscovery() } catch (_: Throwable) { }
        speed?.cancel()
    }

    // =================================================================================== main

    private fun mainPage() = page {
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        times.addView(ctx.label(15f, 450, C.textDim, text = DateFormat.format(DateFormat.getBestDateTimePattern(Locale("bg"), "EEEEdMMMM"), Date()).toString()))
        times.addView(ctx.label(40f, 300, C.text, text = DateFormat.format(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm", Date())), lp(WRAP, WRAP).margins(t = 2.dp))
        head.addView(times, lp(0, WRAP, 1f))
        head.addView(Chip(ctx, null, R.drawable.ic_settings, 0x1AFFFFFF, square = true).apply {
            setOnClickListener { act.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, lp(52.dp, 52.dp).margins(r = 10.dp))
        head.addView(ImageView(ctx).apply {
            setImageBitmap(Lock.avatar(ctx, Lock.active, 156)); clipToOutline = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF) }
            isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(0, C.text) }
            foreground = ring
            FocusFx.attach(this, 1.1f, extra = { has -> ring.setStroke(if (has) 3.dp else 0, C.text) })
            setOnClickListener { dismiss(); act.panels.open(ProfilesPanel(act.panels, act)) }
        }, lp(52.dp, 52.dp))
        addView(head, lp(MATCH, WRAP).margins(l = 6.dp, r = 4.dp, b = 22.dp))

        wifiTile = QsTile(ctx, R.drawable.ic_wifi, "Wi-Fi").apply { setOnClickListener { push(wifiPage(false)) } }
        btTile = QsTile(ctx, R.drawable.ic_bluetooth, "Bluetooth").apply { setOnClickListener { push(btPage(false)) } }
        val saver = QsTile(ctx, R.drawable.ic_screensaver, "Скрийнсейвър").apply {
            sub.text = "Пусни сега"
            setOnClickListener { dismiss(); startScreensaver() }
            setOnLongClickListener { act.startActivity(Intent("com.google.android.tv.settings.ambient")); true }
        }
        val homeT = QsTile(ctx, R.drawable.ic_home, "Google Home").apply {
            sub.text = "Умен дом"
            setOnClickListener {
                try { act.startActivity(Intent("com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME").setPackage("com.google.android.apps.tv.dreamx")) }
                catch (_: Throwable) { toast(ctx, "Google Home не е наличен") }
            }
        }
        sleepTile = QsTile(ctx, R.drawable.ic_moon, "Таймер за сън").apply { setOnClickListener { push(sleepPage()) } }
        outTile = QsTile(ctx, R.drawable.ic_output, "Аудиоизход").apply { setOnClickListener { push(outputPage()) } }
        val tiles = listOf(wifiTile, btTile, saver, homeT, sleepTile, outTile)
        for (i in tiles.indices step 2) {
            val r = LinearLayout(ctx)
            r.addView(tiles[i], lp(0, 112.dp, 1f).margins(r = 6.dp))
            r.addView(tiles[i + 1], lp(0, 112.dp, 1f).margins(l = 6.dp))
            addView(r, lp(MATCH, WRAP).margins(b = 12.dp))
        }
        refreshTiles()

        notifBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(notifBox, lp(MATCH, WRAP))
        renderNotifications()

        section("Aura")
        row(ListRow(ctx, "Настройки на Aura", "Мрежа, звук, профили и заключване, поверителност", R.drawable.ic_tune).apply {
            setOnClickListener { dismiss(); act.panels.open(SettingsPanel(act.panels, act)) }
        })
        com.nodevortex.aura.system.ShareServer.address(ctx)?.let { url ->
            row(ListRow(ctx, "Изпращане от телефона", url, R.drawable.ic_cast).apply { setOnClickListener { toast(ctx, "Отворете $url в браузъра на телефона") } })
        }
    }

    private fun startScreensaver() {
        try { act.startActivity(Intent("com.google.android.pano.action.SLEEP").setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.device.display.daydream.DaydreamVoiceAction"))) }
        catch (_: Throwable) { act.startActivity(Intent("com.google.android.tv.settings.ambient")) }
    }

    private fun refreshTiles() {
        if (!::wifiTile.isInitialized) return
        val info = wm.connectionInfo
        val wifiOn = wm.isWifiEnabled
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && info.networkId != -1 }
        wifiTile.on = wifiOn && ssid != null
        wifiTile.sub.text = when { !wifiOn -> "Изключено"; ssid == null -> "Няма връзка"; else -> "$ssid · ${info.linkSpeed} Mbps" }
        val ad = Bt.adapter
        val connected = Bt.bonded().filter { Bt.isConnected(it) && Bt.isAudio(it) }
        btTile.on = ad?.isEnabled == true
        btTile.sub.text = when {
            ad?.isEnabled != true -> "Изключено"
            connected.isNotEmpty() -> connected.first().let { d -> Bt.displayName(d) + Bt.battery(d).let { if (it >= 0) " · $it%" else "" } }
            else -> "Включено"
        }
        val end = SleepTimer.endsAt
        sleepTile.on = end > 0
        sleepTile.sub.text = if (end > 0) "остават ${((end - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} мин" else "Изключен"
        outTile.sub.text = AudioOutputs.current(ctx).name
    }

    private fun renderNotifications() {
        val box = notifBox ?: return
        box.removeAllViews()
        val items = NotificationHub.items.take(8)
        if (items.isEmpty()) return
        val hdr = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        hdr.addView(ctx.sectionTitle("Известия"), lp(0, WRAP, 1f))
        hdr.addView(Chip(ctx, "Изчисти всички", 0, 0x14FFFFFF, textSize = 14f).apply { setOnClickListener { items.filter { it.clearable }.forEach(NotificationHub::dismiss) } }, lp(WRAP, 40.dp))
        box.addView(hdr, lp(MATCH, WRAP).margins(l = 18.dp, t = 18.dp, b = 8.dp))
        for (n in items) {
            val r = ListRow(ctx, n.title.ifEmpty { n.app }, n.text.take(120), 0, n.icon).apply {
                subtitle.maxLines = 2
                setOnClickListener { dismiss(); NotificationHub.open(n) }
                setOnLongClickListener { NotificationHub.dismiss(n); true }
            }
            r.trailingText(com.nodevortex.aura.util.relTime(System.currentTimeMillis(), n.time))
            box.addView(r, lp(MATCH, WRAP).margins(b = 4.dp))
        }
    }

    // =================================================================================== Wi-Fi

    private var wifiList: LinearLayout? = null
    private var wifiCurrent: LinearLayout? = null
    private var wifiToggle: ListRow? = null
    private var wifiStatus: TextView? = null
    private var pendingSsid: String? = null
    private var speed: SpeedTest? = null
    private var speedValue: TextView? = null
    private var speedInfo: TextView? = null

    private fun wifiPage(runSpeed: Boolean): View {
        val p = page {
            header("Wi-Fi", back = true)
            wifiToggle = ListRow(ctx, "Wi-Fi", null, R.drawable.ic_wifi).apply {
                trailingToggle(wm.isWifiEnabled)
                @Suppress("DEPRECATION")
                setOnClickListener { val on = !wm.isWifiEnabled; wm.isWifiEnabled = on; trailingToggle(on) }
            }
            row(wifiToggle!!)
            wifiStatus = ctx.label(14f, 450, C.warn).apply { visibility = View.GONE }
            addView(wifiStatus, lp(WRAP, WRAP).margins(l = 18.dp, t = 6.dp))
            wifiCurrent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(wifiCurrent, lp(MATCH, WRAP))
            section("Налични мрежи")
            wifiList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(wifiList, lp(MATCH, WRAP))
            row(ListRow(ctx, "Добавяне на мрежа…", "Скрита мрежа или въвеждане на името", R.drawable.ic_add).apply { setOnClickListener { addNetwork() } })
            row(ListRow(ctx, "Разширени настройки за Wi-Fi", null, R.drawable.ic_tune).apply { setOnClickListener { act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } })
        }
        @Suppress("DEPRECATION") wm.startScan()
        renderWifi()
        if (runSpeed) p.post { runSpeedTest() }
        return p
    }

    @Suppress("DEPRECATION")
    private fun renderWifi() {
        val list = wifiList ?: return
        val cur = wifiCurrent ?: return
        wifiToggle?.trailingToggle(wm.isWifiEnabled)
        refreshTiles()
        cur.removeAllViews()
        val info = wm.connectionInfo
        val connectedSsid = info?.ssid?.trim('"')?.takeIf { info.networkId != -1 && it != "<unknown ssid>" }
        if (connectedSsid != null) {
            val band = if (info.frequency > 5900) "6 GHz" else if (info.frequency > 4900) "5 GHz" else "2.4 GHz"
            val std = if (Build.VERSION.SDK_INT >= 30) when (info.wifiStandard) { 4 -> "Wi-Fi 4"; 5 -> "Wi-Fi 5"; 6 -> "Wi-Fi 6"; 7 -> "Wi-Fi 7"; else -> "" } else ""
            val rx = if (Build.VERSION.SDK_INT >= 29) info.rxLinkSpeedMbps else -1
            val tx = if (Build.VERSION.SDK_INT >= 29) info.txLinkSpeedMbps else -1
            val sig = WifiManager.calculateSignalLevel(info.rssi, 5)
            val quality = listOf("много слаб", "слаб", "среден", "добър", "отличен")[sig.coerceIn(0, 4)]
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = 24.dpf; setColor(0xFF1E2532.toInt()) }
                setPadding(22.dp, 18.dp, 22.dp, 18.dp)
            }
            card.addView(ctx.label(13f, 600, C.good, text = "СВЪРЗАНО").apply { letterSpacing = 0.08f })
            card.addView(ctx.label(22f, 520, C.text, text = connectedSsid), lp(WRAP, WRAP).margins(t = 4.dp))
            val link = if (rx > 0 && tx > 0) "Връзка ↓ $rx  ↑ $tx Mbps" else "Връзка ${info.linkSpeed} Mbps"
            card.addView(ctx.label(14.5f, 450, C.textDim, 2, "$link · $band${if (std.isNotEmpty()) " · $std" else ""}\nСигнал: $quality (${info.rssi} dBm)"), lp(WRAP, WRAP).margins(t = 6.dp))
            val sp = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
            speedValue = ctx.label(40f, 300, C.text, text = Aura.prefs.getString("lastSpeed", null) ?: "—")
            sp.addView(speedValue)
            sp.addView(ctx.label(15f, 500, C.textDim, text = " Mbps"), lp(WRAP, WRAP).margins(b = 7.dp))
            card.addView(sp, lp(WRAP, WRAP).margins(t = 12.dp))
            speedInfo = ctx.label(13.5f, 450, C.textDim, 2, Aura.prefs.getString("lastSpeedInfo", "Реалната скорост на интернета — пуснете тест"))
            card.addView(speedInfo)
            cur.addView(card, lp(MATCH, WRAP).margins(t = 10.dp, b = 6.dp))
            cur.addView(ListRow(ctx, "Тест на скоростта", "Изтегляне, качване и пинг чрез Cloudflare", R.drawable.ic_speed).apply { setOnClickListener { runSpeedTest() } }, lp(MATCH, WRAP))
        }
        list.removeAllViews()
        if (!wm.isWifiEnabled) { list.addView(ctx.label(15f, 450, C.textDim, text = "Wi-Fi е изключен").apply { setPadding(18.dp, 8.dp, 0, 8.dp) }); return }
        val saved = try { wm.configuredNetworks ?: emptyList() } catch (_: Throwable) { emptyList<WifiConfiguration>() }
        val savedSsids = saved.map { it.SSID.trim('"') }.toSet()
        val results = try { wm.scanResults } catch (_: Throwable) { emptyList<ScanResult>() }
            .filter { it.SSID.isNotBlank() }.groupBy { it.SSID }.map { (_, v) -> v.maxByOrNull { it.level }!! }
            .sortedWith(compareByDescending<ScanResult> { it.SSID == connectedSsid }.thenByDescending { it.SSID in savedSsids }.thenByDescending { it.level })
        if (results.isEmpty()) list.addView(ctx.label(15f, 450, C.textDim, text = "Търсене на мрежи…").apply { setPadding(18.dp, 8.dp, 0, 8.dp) })
        for (r in results.take(25)) {
            val sec = security(r)
            val lvl = WifiManager.calculateSignalLevel(r.level, 4)
            val band = if (r.frequency > 4900) "5 GHz" else "2.4 GHz"
            val isCur = r.SSID == connectedSsid
            val sub = when { isCur -> "Свързано · $band"; r.SSID == pendingSsid -> "Свързване…"; r.SSID in savedSsids -> "Запазена · $band"; sec == "Open" -> "Без парола · $band"; else -> "$sec · $band" }
            val row = ListRow(ctx, r.SSID, sub, R.drawable.ic_wifi).apply {
                icon.alpha = 0.4f + 0.2f * lvl
                if (isCur) trailingIcon(R.drawable.ic_check) else if (sec != "Open") trailingIcon(R.drawable.ic_lock)
                setOnClickListener { if (!isCur) tapNetwork(r, sec, saved.firstOrNull { it.SSID.trim('"') == r.SSID }) }
                setOnLongClickListener {
                    val s = saved.firstOrNull { it.SSID.trim('"') == r.SSID } ?: return@setOnLongClickListener false
                    host.open(MenuPanel(host, this, r.SSID, listOf(Triple(R.drawable.ic_delete, "Забравяне на мрежата") {
                        if (!wm.removeNetwork(s.networkId)) act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) else { wm.saveConfiguration(); renderWifi() }
                    }))); true
                }
            }
            list.addView(row, lp(MATCH, WRAP).margins(b = 4.dp))
        }
    }

    private fun security(r: ScanResult): String {
        val c = r.capabilities
        return when {
            c.contains("EAP") -> "Enterprise"
            c.contains("SAE") && !c.contains("PSK") -> "WPA3"
            c.contains("PSK") -> "WPA2"
            c.contains("WEP") -> "WEP"
            else -> "Open"
        }
    }

    @Suppress("DEPRECATION")
    private fun tapNetwork(r: ScanResult, sec: String, saved: WifiConfiguration?) {
        when {
            sec == "Enterprise" -> act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            saved != null -> { pendingSsid = r.SSID; wm.disconnect(); wm.enableNetwork(saved.networkId, true); wm.reconnect(); status("Свързване с ${r.SSID}…") ; renderWifi() }
            sec == "Open" -> connect(r.SSID, sec, null)
            else -> host.open(FormPanel(host, r.SSID, "Въведете паролата на Wi-Fi мрежата", listOf(FormPanel.Field("Парола", password = true)), "Свързване") { v -> connect(r.SSID, sec, v[0]) })
        }
    }

    private fun addNetwork() {
        host.open(FormPanel(host, "Добавяне на мрежа", "Въведете името на мрежата, изберете защита (◀ ▶) и паролата",
            listOf(FormPanel.Field("Име на мрежата (SSID)"), FormPanel.Field("Защита", "WPA2", choices = listOf("WPA2", "WPA3", "Без парола", "WEP")), FormPanel.Field("Парола", password = true)), "Свързване") { v ->
            if (v[0].isNotBlank()) connect(v[0].trim(), if (v[1] == "Без парола") "Open" else v[1], v[2].ifEmpty { null }, hidden = true)
        })
    }

    @Suppress("DEPRECATION")
    private fun connect(ssid: String, sec: String, pw: String?, hidden: Boolean = false) {
        if (sec != "Open" && (pw == null || (sec != "WEP" && pw.length < 8))) { status("Паролата трябва да е поне 8 знака"); return }
        val conf = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            hiddenSSID = hidden
            when (sec) {
                "Open" -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                "WEP" -> { wepKeys[0] = "\"$pw\""; wepTxKeyIndex = 0; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE); allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED) }
                "WPA3" -> { if (Build.VERSION.SDK_INT >= 30) setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE); preSharedKey = "\"$pw\"" }
                else -> { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK); preSharedKey = "\"$pw\"" }
            }
        }
        val existing = try { wm.configuredNetworks?.firstOrNull { it.SSID == conf.SSID } } catch (_: Throwable) { null }
        val id = if (existing != null) { conf.networkId = existing.networkId; wm.updateNetwork(conf) } else wm.addNetwork(conf)
        if (id == -1) { status("Android не позволи добавянето — отварям настройките за Wi-Fi"); act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); return }
        pendingSsid = ssid
        wm.disconnect(); wm.enableNetwork(id, true); wm.reconnect()
        status("Свързване с $ssid…")
        renderWifi()
    }

    private fun status(s: String?) {
        val v = wifiStatus ?: return
        if (s == null) v.visibility = View.GONE else { v.text = s; v.visibility = View.VISIBLE }
    }

    private fun runSpeedTest() {
        speed?.cancel()
        speedValue?.text = "…"; speedInfo?.text = "Измерване на пинга…"
        speed = SpeedTest({ phase, mbps, ping ->
            when (phase) {
                SpeedTest.Phase.PING -> speedInfo?.text = "Пинг $ping ms"
                SpeedTest.Phase.DOWNLOAD -> { speedValue?.text = "%.0f".format(mbps); speedInfo?.text = "↓ Изтегляне…  ·  пинг $ping ms" }
                SpeedTest.Phase.UPLOAD -> speedInfo?.text = "↑ Качване… %.0f Mbps".format(mbps)
                SpeedTest.Phase.FAILED -> speedInfo?.text = "Тестът не успя — проверете връзката"
                SpeedTest.Phase.DONE -> {}
            }
        }) { down, up, ping ->
            val v = "%.0f".format(down)
            val info = "↓ $v Mbps  ↑ %.0f Mbps  ·  пинг $ping ms".format(up)
            speedValue?.text = v; speedInfo?.text = info
            Aura.prefs.edit().putString("lastSpeed", v).putString("lastSpeedInfo", "$info · току-що").apply()
        }.also { it.start() }
    }

    // =================================================================================== Bluetooth

    private var pairedBox: LinearLayout? = null
    private var foundBox: LinearLayout? = null
    private var scanRow: ListRow? = null
    private var btToggle: ListRow? = null
    private val found = LinkedHashMap<String, Pair<BluetoothDevice, Short>>()

    private fun btPage(pairNow: Boolean): View {
        val p = page {
            header("Bluetooth", back = true)
            btToggle = ListRow(ctx, "Bluetooth", null, R.drawable.ic_bluetooth).apply {
                trailingToggle(Bt.adapter?.isEnabled == true)
                @Suppress("DEPRECATION")
                setOnClickListener { Bt.adapter?.let { if (it.isEnabled) it.disable() else it.enable() } }
            }
            row(btToggle!!)
            section("Сдвоени устройства")
            pairedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(pairedBox, lp(MATCH, WRAP))
            section("Сдвояване на ново устройство")
            scanRow = ListRow(ctx, "Търсене на устройства", "Включете слушалките, тонколоната или контролера в режим на сдвояване", R.drawable.ic_refresh).apply { setOnClickListener { startScan() } }
            row(scanRow!!)
            foundBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(foundBox, lp(MATCH, WRAP))
            row(ListRow(ctx, "Дистанционни и аксесоари", null, R.drawable.ic_tune).apply {
                setOnClickListener { try { act.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Throwable) { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            })
        }
        renderPaired()
        if (pairNow) p.post { startScan() }
        return p
    }

    private fun renderPaired() {
        val box = pairedBox ?: return
        btToggle?.trailingToggle(Bt.adapter?.isEnabled == true)
        refreshTiles()
        box.removeAllViews()
        val devs = Bt.bonded().sortedByDescending { Bt.isConnected(it) }
        if (devs.isEmpty()) box.addView(ctx.label(15f, 450, C.textDim, text = "Няма сдвоени устройства").apply { setPadding(18.dp, 8.dp, 0, 8.dp) })
        for (d in devs) {
            val conn = Bt.isConnected(d)
            val bat = if (conn) Bt.battery(d) else -1
            val sub = (if (conn) "Свързано" else "Не е свързано") + if (bat >= 0) " · батерия $bat%" else ""
            val r = ListRow(ctx, Bt.displayName(d), sub, Bt.kindIcon(d)).apply {
                setOnClickListener {
                    if (conn) {
                        toast(ctx, "${Bt.displayName(d)} е свързано" + if (bat >= 0) " · батерия $bat%" else "")
                    } else {
                        Aura.io.execute {
                            val ok = Bt.connect(d)
                            Aura.ui { toast(ctx, if (ok) "Свързване с ${Bt.displayName(d)}…" else "Включете ${Bt.displayName(d)} (или го извадете от кутията) — свързва се само") }
                        }
                    }
                }
                setOnLongClickListener {
                    host.open(MenuPanel(host, this, Bt.displayName(d), listOf(
                        Triple(R.drawable.ic_info, "Настройки на устройството") { try { act.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Throwable) { } },
                        Triple(R.drawable.ic_delete, "Забравяне") {
                            val ok = try { d.javaClass.getMethod("removeBond").invoke(d) as Boolean } catch (_: Throwable) { false }
                            if (!ok) { toast(ctx, "Забравете го от Настройки → Дистанционни и аксесоари"); try { act.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Throwable) { } }
                        },
                    ))); true
                }
            }
            box.addView(r, lp(MATCH, WRAP).margins(b = 4.dp))
        }
    }

    private fun startScan() {
        val a = Bt.adapter ?: return
        @Suppress("DEPRECATION") if (!a.isEnabled) { a.enable(); toast(ctx, "Включване на Bluetooth…"); scanRow?.postDelayed({ startScan() }, 1800); return }
        found.clear(); renderFound()
        a.cancelDiscovery(); a.startDiscovery()
    }

    private fun renderFound() {
        val box = foundBox ?: return
        box.removeAllViews()
        for ((d, rssi) in found.values.sortedByDescending { it.second }) {
            val name = d.name ?: continue
            val state = when (d.bondState) { BluetoothDevice.BOND_BONDING -> "Сдвояване…"; BluetoothDevice.BOND_BONDED -> "Сдвоено"; else -> "Сигнал ${rssi} dBm · OK за сдвояване" }
            box.addView(ListRow(ctx, name, state, Bt.kindIcon(d)).apply {
                setOnClickListener {
                    Bt.adapter?.cancelDiscovery()
                    if (!d.createBond()) toast(ctx, "Сдвояването с $name не можа да започне") else { setSubtitle("Сдвояване… потвърдете на устройството, ако се поиска") }
                }
            }, lp(MATCH, WRAP).margins(b = 4.dp))
        }
    }

    // =================================================================================== sleep

    private fun sleepPage() = page {
        header("Таймер за сън", back = true)
        val end = SleepTimer.endsAt
        addView(ctx.label(15f, 450, C.textDim, 2, if (end > 0) "Телевизорът се изключва след ${((end - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} мин" else "Автоматично изключване на телевизора"), lp(WRAP, WRAP).margins(l = 18.dp, b = 10.dp))
        val opts = listOf(0 to "Изключен", 15 to "15 минути", 30 to "30 минути", 45 to "45 минути", 60 to "1 час", 90 to "1 час и половина", 120 to "2 часа", 180 to "3 часа")
        for ((min, label) in opts) {
            row(ListRow(ctx, label).apply {
                if ((min == 0 && end == 0L)) trailingIcon(R.drawable.ic_check)
                setOnClickListener {
                    if (min == 0) SleepTimer.cancel(ctx) else SleepTimer.set(ctx, min)
                    toast(ctx, if (min == 0) "Таймерът е изключен" else "Телевизорът ще се изключи след $label")
                    refreshTiles(); pop()
                }
            })
        }
        row(ListRow(ctx, "Изключи сега", null, R.drawable.ic_moon).apply { setOnClickListener { dismiss(); SleepTimer.sleepNow(ctx) } })
        if (!SleepTimer.adminActive(ctx)) addView(ctx.label(13f, 450, C.textFaint, 3, "Съвет: пуснете веднъж tools/setup.sh, за да може Aura сама да изключва телевизора."), lp(WRAP, WRAP).margins(l = 18.dp, t = 12.dp))
    }

    // =================================================================================== output

    private fun outputPage() = page {
        header("Аудиоизход", back = true)
        for (o in AudioOutputs.list(ctx)) {
            val sub = when { o.active -> "Звукът е тук"; o.connected -> "Свързано"; o.kind == AudioOutputs.Kind.TV -> "HDMI"; else -> "Сдвоено" } + if (o.battery >= 0) " · ${o.battery}%" else ""
            row(ListRow(ctx, o.name, sub, AudioOutputs.icon(o.kind)).apply {
                if (o.active) trailingIcon(R.drawable.ic_check)
                setOnClickListener { AudioOutputs.select(ctx, o) { ok -> if (ok) { toast(ctx, "Звукът е на ${o.name}"); refreshTiles(); pop() } } }
            })
        }
        row(ListRow(ctx, "Сдвояване на ново устройство…", null, R.drawable.ic_add).apply { setOnClickListener { push(btPage(true)) } })
        row(ListRow(ctx, "Още опции за изхода", "Системният панел за аудиоизход", R.drawable.ic_tune).apply { setOnClickListener { AudioOutputs.openSystemPicker(act) } })
    }

    // =================================================================================== events

    private val receiver: BroadcastReceiver by lazy { object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION, WifiManager.WIFI_STATE_CHANGED_ACTION -> renderWifi()
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val info = wm.connectionInfo
                    if (pendingSsid != null && info?.ssid?.trim('"') == pendingSsid && info.networkId != -1 && info.supplicantState == SupplicantState.COMPLETED) {
                        status("Свързано с $pendingSsid"); pendingSsid = null
                    }
                    renderWifi()
                }
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    if (i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING && pendingSsid != null) {
                        status("Грешна парола за $pendingSsid"); pendingSsid = null; renderWifi()
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
                com.nodevortex.aura.system.AuraService.BATTERY_CHANGED -> { renderPaired() }
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (d.bondState == BluetoothDevice.BOND_BONDED || d.name == null) return
                    found[d.address] = d to i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    renderFound()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanRow?.setSubtitle("Търсене…")
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanRow?.setSubtitle(if (found.isEmpty()) "Нищо не е намерено — проверете дали устройството е в режим на сдвояване" else "Търсене отново")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> { found.remove(d.address); toast(ctx, "Сдвоено с ${d.name}"); renderPaired() }
                        BluetoothDevice.BOND_NONE -> if (found.containsKey(d.address)) toast(ctx, "Сдвояването с ${d.name} не успя")
                    }
                    renderFound()
                }
            }
        }
    } }

    init {
        push(mainPage())
        if (startPage == null) wifiTile.post { wifiTile.requestFocus() }
        when (startPage) {
            "wifi" -> push(wifiPage(false)); "speed" -> push(wifiPage(true)); "bt" -> push(btPage(false)); "bt-pair" -> push(btPage(true))
            "sleep" -> push(sleepPage())
        }
        NotificationHub.addListener(notifListener)
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(com.nodevortex.aura.system.AuraService.BATTERY_CHANGED)
        })
    }
}
