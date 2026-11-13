package com.nodevortex.aura.system

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nodevortex.aura.Aura
import com.nodevortex.aura.HomeActivity
import com.nodevortex.aura.lock.Lock
import com.nodevortex.aura.lock.LockActivity

/**
 * Always-on helper (an accessibility service, enabled once via adb):
 *  • replaces the system volume UI with Aura's capsule (+ OK → output picker)
 *  • notification pop-ups, earbuds battery card, sleep-timer countdown
 *  • starts the music visualiser when music plays and nobody touched the remote for a while
 *  • keeps the profile lock in front when it is active
 */
class AuraService : AccessibilityService() {

    private lateinit var volume: VolumeOverlay
    private lateinit var toasts: ToastStack
    private lateinit var buds: BudsPopup
    private lateinit var sleepWarn: SleepWarning
    private lateinit var status: StatusPill
    private lateinit var am: AudioManager
    @Volatile var lastInput = SystemClock.elapsedRealtime()
        private set
    @Volatile var foreground: String? = null
        private set
    private var swallowCenterUp = false
    private var swallowHomeUp = false
    private var countdown = -1
    private var cachedOut: AudioOutputs.Output? = null
    private var cachedOutAt = 0L

    override fun onServiceConnected() {
        instance = this
        am = getSystemService(AudioManager::class.java)
        volume = VolumeOverlay(this)
        toasts = ToastStack(this)
        buds = BudsPopup(this)
        sleepWarn = SleepWarning(this)
        status = StatusPill(this)
        Bt.init(this)
        NotificationHub.popup = { item -> if (Aura.prefs.getBoolean("popups", true) && !Lock.showing) toasts.show(item) }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BATTERY_CHANGED)
        })
        ShareServer.start(this)
        Aura.main.postDelayed(idleTick, 30_000)
        Aura.app.bridge.probe()
        // After a reboot Google TV's home is already showing when we connect: take over.
        Aura.main.postDelayed({ if (activeIsStockHome()) goHome() }, 1500)
        Aura.main.postDelayed({ StockLauncher.enforce(this) }, 4000)
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        Aura.main.removeCallbacks(idleTick)
        volume.detach(); toasts.detach(); buds.detach(); sleepWarn.detach(); status.detach()
        NotificationHub.popup = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        if (p == LX && cls.endsWith(".home.HomeActivity")) {
            // Google TV forces its own home; make Aura the real home screen.
            if (switching == null && SystemClock.elapsedRealtime() > allowStockUntil && Aura.prefs.getBoolean("homeOverride", true) && !Lock.showing) {
                goHome(); return
            }
            if (switching == null) Aura.main.postDelayed({ readCurrentProfile() }, 900)
        } else if (p != LX && p != packageName && p != "com.android.systemui" && cls.contains("Activity")) {
            allowStockUntil = 0L
        }
        if (p != "com.android.systemui" && p != packageName || cls.contains("Activity")) foreground = p
    }

    // ----------------------------------------------------------------------------- default home

    @Volatile private var allowStockUntil = 0L
    /** Let Google TV's home stay on screen for a while (opened on purpose from Aura). */
    fun allowStockHome(ms: Long) { allowStockUntil = SystemClock.elapsedRealtime() + ms }

    private fun activeIsStockHome(): Boolean = try { rootInActiveWindow?.packageName == LX } catch (_: Throwable) { false }

    // ----------------------------------------------------------------------------- Google TV profiles

    private class Switch(val target: String?, val learn: Boolean, var deadline: Long) {
        var stage = 0; var ups = 0
        var before: Set<String> = emptySet()
    }
    @Volatile private var switching: Switch? = null
    private val stepR = Runnable { step() }

    /** Reads "Влезли сте като <име>" from Google TV's avatar to know the active profile. */
    private fun readCurrentProfile() {
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        if (root.packageName != LX) return
        findAvatar(root)?.let { profileName(it)?.let { n -> Lock.current = n } }
    }

    /**
     * Switch Google TV's profile (or just learn the profile names when [learnOnly]). Opens Google
     * TV's home, clicks its avatar and picks the profile in its menu – the user chooses once, in Aura.
     */
    fun switchProfile(target: String?, learnOnly: Boolean) {
        if (StockLauncher.disabled(this)) {
            status.show("Подготвям смяната на профила…")
            StockLauncher.enable(this) { ok ->
                if (ok) { redisableAfter = true; startSwitch(target, learnOnly) }
                else { status.hide(); toasts.show(NotificationHub.Item("aura", packageName, "VeloCinema", "Профилът не може да се смени",
                    "Включете разширените функции (Настройки на VeloCinema) или натиснете „Активиране“ в настройките на Google TV", null, 0, null, false)) }
            }
            return
        }
        startSwitch(target, learnOnly)
    }

    private var redisableAfter = false

    private fun startSwitch(target: String?, learnOnly: Boolean) {
        allowStockHome(25_000)
        switching = Switch(target, learnOnly, SystemClock.elapsedRealtime() + 9_000)
        status.show(if (learnOnly) "Зареждам профилите от Google TV…" else "Превключване към ${target ?: "профила"}…")
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(android.content.ComponentName(LX, "$LX.home.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Throwable) { finishSwitch(false); return }
        Aura.main.removeCallbacks(stepR); Aura.main.postDelayed(stepR, 900)
    }

    private fun step() {
        val s = switching ?: return
        if (SystemClock.elapsedRealtime() > s.deadline) { finishSwitch(false, leaveOpen = s.stage >= 1); return }
        val root = try { rootInActiveWindow } catch (_: Throwable) { null }
        if (root == null || root.packageName != LX) { Aura.main.postDelayed(stepR, 250); return }
        when (s.stage) {
            0 -> {
                val av = findAvatar(root)
                if (av == null) {
                    // Google TV's home may be scrolled down; walk up to its top bar.
                    if (s.ups < 6) { performGlobalAction(GLOBAL_ACTION_DPAD_UP); s.ups++ }
                    Aura.main.postDelayed(stepR, 350); return
                }
                val cur = profileName(av)
                if (cur != null) Lock.current = cur
                if (!s.learn && s.target != null && Lock.same(cur, s.target)) { finishSwitch(true); return }
                s.before = labels(root).map { it.first }.toSet()
                click(av); s.stage = 1
                Aura.main.postDelayed(stepR, 1100)
            }
            1 -> {
                val fresh = labels(root).filter { it.first !in s.before && nameLike(it.first) }
                if (fresh.isEmpty()) { Aura.main.postDelayed(stepR, 300); return }
                val names = fresh.map { it.first.substringBefore(',').trim() }
                Lock.profiles = (listOfNotNull(Lock.current) + names).distinct()
                if (s.learn) { performGlobalAction(GLOBAL_ACTION_BACK); finishSwitch(true); return }
                val hit = fresh.firstOrNull { Lock.same(it.first.substringBefore(',').trim(), s.target) }
                if (hit == null) { finishSwitch(false, leaveOpen = true); return }
                click(hit.second); s.stage = 2; s.deadline = SystemClock.elapsedRealtime() + 15_000
                status.show("Превключване към ${s.target}…")
                Aura.main.postDelayed(stepR, 1500)
            }
            2 -> {
                val av = findAvatar(root)
                if (av != null && Lock.same(profileName(av), s.target)) { Lock.current = s.target; finishSwitch(true) }
                else Aura.main.postDelayed(stepR, 400)
            }
        }
    }

    private fun finishSwitch(ok: Boolean, leaveOpen: Boolean = false) {
        val s = switching
        switching = null
        Aura.main.removeCallbacks(stepR)
        status.hide()
        if (redisableAfter) { redisableAfter = false; StockLauncher.disable(this) }
        if (ok || !leaveOpen) { allowStockUntil = 0L; goHome() }
        else { allowStockHome(60_000); toasts.show(NotificationHub.Item("aura", packageName, "VeloCinema", "Изберете профила", "Изберете ${s?.target ?: "профил"} от менюто на Google TV", null, 0, null, false)) }
    }

    /** Clicks a button with one of [texts] in the active window (used for "Активиране" in TV Settings). */
    fun clickText(texts: List<String>): Boolean {
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return false
        val n = find(root) { node ->
            val t = (node.text ?: node.contentDescription)?.toString()?.trim() ?: return@find false
            texts.any { it.equals(t, ignoreCase = true) } && node.isVisibleToUser
        } ?: return false
        click(n); return true
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun budsPreview(three: Boolean) = buds.preview("Buds3 Pro", if (three) listOf("Ляво" to 82, "Дясно" to 79, "Кутия" to 45) else listOf("Батерия" to 80))

    private fun findAvatar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = find(root) { n ->
        AVATAR.containsMatchIn(n.contentDescription?.toString() ?: "")
    }

    private fun profileName(n: AccessibilityNodeInfo): String? = AVATAR.find(n.contentDescription?.toString() ?: "")?.groupValues?.get(2)?.trim()

    private fun find(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 600) {
            val n = q.removeFirst(); seen++
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun labels(root: AccessibilityNodeInfo): List<Pair<String, AccessibilityNodeInfo>> {
        val out = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        find(root) { n ->
            val t = (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString())?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) out += t to n
            false
        }
        return out
    }

    private fun nameLike(t: String): Boolean {
        val s = t.substringBefore(',').trim()
        return s.length in 2..32 && !s.any { it.isDigit() } && !NOT_NAMES.containsMatchIn(s) && !AVATAR.containsMatchIn(t)
    }

    private fun click(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        (c ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onInterrupt() {}

    // ----------------------------------------------------------------------------- keys

    override fun onKeyEvent(e: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode

        if (countdown >= 0) { if (down) cancelCountdown(); return true }

        if (Lock.showing && code !in LOCK_KEYS) {
            if (code == KeyEvent.KEYCODE_HOME && !down) Lock.show(this)
            return true
        }

        if (Aura.prefs.getBoolean("customVolume", true)) {
            when (code) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (down) adjust(if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> { if (down && e.repeatCount == 0) adjust(AudioManager.ADJUST_TOGGLE_MUTE); return true }
            }
        }

        if (volume.visible) {
            if (volume.pickerOpen) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (down) volume.move(-1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) volume.move(1); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (!down) pick(); return true }
                    KeyEvent.KEYCODE_BACK -> { if (!down) volume.closePicker(); return true }
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (down) swallowCenterUp = true
                else if (swallowCenterUp) { swallowCenterUp = false; volume.openPicker() }
                return true
            }
        } else if (!down && swallowCenterUp && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            swallowCenterUp = false; return true
        }

        if (code == KeyEvent.KEYCODE_HOME && Aura.prefs.getBoolean("homeOverride", true)) {
            if (down) {
                if (e.repeatCount == 0) { homeLong = false; Aura.main.removeCallbacks(homeLongR); Aura.main.postDelayed(homeLongR, 550) }
                return true
            }
            Aura.main.removeCallbacks(homeLongR)
            if (!homeLong) goHome()
            return true
        }
        return false
    }

    private fun adjust(dir: Int) {
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) } catch (_: Throwable) { }
        val now = SystemClock.elapsedRealtime()
        if (cachedOut == null || now - cachedOutAt > 3000) { cachedOut = AudioOutputs.current(this); cachedOutAt = now }
        volume.show(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            am.isStreamMute(AudioManager.STREAM_MUSIC), cachedOut!!)
    }

    private fun pick() {
        when (val c = volume.choose()) {
            is AudioOutputs.Output -> {
                volume.hide()
                AudioOutputs.select(this, c) { ok -> if (ok) { cachedOut = null; adjust(AudioManager.ADJUST_SAME) } }
            }
            -1 -> { volume.hide(); startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_OPEN, "bt-pair").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else -> { volume.hide(); AudioOutputs.openSystemPicker(this) }
        }
    }

    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return ri?.activityInfo?.packageName == packageName
    }

    private var homeLong = false
    /** Holding Home opens Aura's quick settings (like Google TV's dashboard did). */
    private val homeLongR = Runnable {
        homeLong = true
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(HomeActivity.EXTRA_OPEN, "qs"))
    }

    private fun goHome() = startActivity(Intent(this, HomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(HomeActivity.EXTRA_HOME_PRESSED, true))

    // ----------------------------------------------------------------------------- idle → music visualiser

    private val idleTick = object : Runnable {
        override fun run() {
            Aura.main.postDelayed(this, 20_000)
            if (!Aura.prefs.getBoolean("musicSaver", true)) return
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isInteractive || MusicSaverActivity.showing || Lock.showing) return
            val sysTimeout = Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000L)
            val wanted = Aura.prefs.getInt("musicSaverMin", 9) * 60_000L
            val threshold = minOf(wanted, (sysTimeout - 45_000L).coerceAtLeast(60_000L))
            if (SystemClock.elapsedRealtime() - lastInput >= threshold && MediaWatcher.isMusicPlaying()) {
                startActivity(Intent(this@AuraService, MusicSaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
    }

    // ----------------------------------------------------------------------------- sleep timer

    fun sleepCountdown() {
        countdown = 30
        sleepWarn.attach()
        tick()
    }

    private fun tick() {
        if (countdown < 0) return
        if (countdown == 0) { countdown = -1; sleepWarn.detach(); SleepTimer.sleepNow(this); return }
        sleepWarn.text.text = "Таймер за сън: телевизорът се изключва след ${countdown} с  ·  натиснете бутон за отказ"
        countdown--
        Aura.main.postDelayed(tickR, 1000)
    }

    private val tickR = Runnable { tick() }

    private fun cancelCountdown() {
        countdown = -1; sleepWarn.detach(); SleepTimer.cancel(this)
        Aura.main.removeCallbacks(tickR)
    }

    // ----------------------------------------------------------------------------- broadcasts

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { Lock.onScreenOff(); volume.hide() }
                Intent.ACTION_SCREEN_ON -> { lastInput = SystemClock.elapsedRealtime(); if (Lock.required()) Lock.show(c) }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    cachedOut = null
                    if (Bt.isAudio(d) && Aura.prefs.getBoolean("budsPopup", true)) Aura.main.postDelayed({ buds.show(d) }, 1500)
                }
                BATTERY_CHANGED -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val lvl = i.getIntExtra(EXTRA_BATTERY, -1)
                    Bt.noteBattery(d.address, lvl)
                    buds.battery(d.address, lvl)
                }
            }
        }
    }

    companion object {
        @Volatile var instance: AuraService? = null
        const val BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val LX = "com.google.android.apps.tv.launcherx"
        private val AVATAR = Regex("(Влезли сте като|Влязохте като|Signed in as)\\s+(.+?)[.,]\\s")
        private val NOT_NAMES = Regex("(?i)(добав|add|настрой|setting|управ|manage|акаунт|account|изход|sign|дет|kid|child|гост|guest|профил|profile|google|помощ|help|търс|search|начало|home|приложения|apps|скрийнсейвър|screensaver|бързи|quick|за вас|for you|продълж|continue|youtube|spotify|библиот|library|на живо|live)")
        const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private val LOCK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
        @Suppress("unused") private val keepLock = LockActivity::class
    }
}
