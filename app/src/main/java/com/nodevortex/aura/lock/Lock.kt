package com.nodevortex.aura.lock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.nodevortex.aura.Aura
import com.nodevortex.aura.R
import com.nodevortex.aura.system.AuraService
import com.nodevortex.aura.ui.AmbientBackground
import com.nodevortex.aura.ui.FocusFx
import com.nodevortex.aura.ui.toast
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.ColorKit
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Motion
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Profiles = the Google TV profiles (learned from Google TV's own UI by Aura's helper), and the
 * owner-only PIN lock. Aura follows whichever profile Google TV currently has active.
 */
object Lock {
    private val p get() = Aura.prefs
    @Volatile var showing = false
    @Volatile private var unlocked = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() = Aura.ui { listeners.forEach { it() } }

    val pinSet get() = p.contains("pinHash")
    var enabled: Boolean
        get() = p.getBoolean("lockEnabled", false) && pinSet
        set(v) = p.edit().putBoolean("lockEnabled", v).apply()

    /** Google TV profile names as Google TV shows them, e.g. "Ивелин". */
    var profiles: List<String>
        get() = p.getString("gtvProfiles", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) {
            // "Ивелин" (avatar) and "Ивелин Бонев" (menu) are the same person: keep the fuller name.
            val merged = ArrayList<String>()
            for (n in v.map { it.trim() }.filter { it.isNotEmpty() }) {
                val i = merged.indexOfFirst { same(it, n) }
                if (i < 0) merged += n else if (n.length > merged[i].length) merged[i] = n
            }
            p.edit().putString("gtvProfiles", merged.joinToString("\n")).apply(); changed()
        }

    /** The profile Google TV currently has active (null until Aura has seen it once). */
    var current: String?
        get() = p.getString("gtvCurrent", null)
        set(v) {
            if (v == null || v == current) return
            p.edit().putString("gtvCurrent", v).apply()
            if (owner == null) owner = v
            if (profiles.none { same(it, v) }) profiles = profiles + v
            changed()
        }

    var owner: String?
        get() = p.getString("owner", null)
        set(v) = p.edit().putString("owner", v).apply()

    /** Aura shows and locks for whatever Google TV has active. */
    val active: String? get() = current ?: owner

    fun displayName(name: String?) = name ?: "Вие"

    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = a.trim().lowercase(); val y = b.trim().lowercase()
        return x == y || x.startsWith("$y ") || y.startsWith("$x ")
    }

    fun avatar(ctx: Context, name: String?, size: Int): Bitmap {
        if (name == null || same(name, owner) || owner == null) {
            ownerPhoto(ctx)?.let { return it }
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val hue = ((name ?: "x").hashCode() and 0xffff) / 65535f * 6.28f
        val col = ColorKit.fromLab(0.62f, 0.11f * kotlin.math.cos(hue), 0.11f * kotlin.math.sin(hue))
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = C.onLight; textSize = size * 0.45f; textAlign = Paint.Align.CENTER; Fonts.paint(this, 600) }
        c.drawText(displayName(name).take(1).uppercase(), size / 2f, size / 2f + tp.textSize * 0.36f, tp)
        return out
    }

    /** The owner's photo: assets/avatar.png (kept out of git), else a neutral default. */
    @Volatile private var photo: Bitmap? = null
    fun ownerPhoto(ctx: Context): Bitmap? {
        photo?.let { return it }
        val b = try { ctx.assets.open("avatar.png").use { BitmapFactory.decodeStream(it) } } catch (_: Throwable) { null }
            ?: BitmapFactory.decodeResource(ctx.resources, R.drawable.avatar_default)
        photo = b; return b
    }

    fun required() = enabled && (active == null || same(active, owner)) && !unlocked
    fun onScreenOff() { if (enabled && same(active, owner)) unlocked = false }
    fun markUnlocked() { unlocked = true }

    private fun hash(pin: String): String {
        val salt = p.getString("pinSalt", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("pinSalt", it).apply() }
        return MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun verify(pin: String) = pinSet && hash(pin) == p.getString("pinHash", "")
    fun setPin(pin: String) { p.edit().putString("pinHash", hash(pin)).apply(); unlocked = true }

    fun show(ctx: Context) {
        ctx.startActivity(Intent(ctx, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /**
     * Switch the real Google TV profile. Aura's helper opens Google TV's profile menu and picks the
     * profile itself; without the helper you land on Google TV's home to pick it there.
     */
    fun switchTo(ctx: Context, name: String?) {
        val svc = AuraService.instance
        if (svc != null) { svc.switchProfile(name, learnOnly = name == null); return }
        toast(ctx, "Изберете профила от аватара горе вляво")
        openGoogleHome(ctx)
    }

    fun openGoogleHome(ctx: Context) {
        AuraService.instance?.allowStockHome(60_000)
        try {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { }
    }
}

/**
 * The classic Google TV PIN pad: only one row of three digits is visible. ◀ / OK / ▶ type the
 * left / middle / right digit, ▼ ▲ move between rows (1-2-3, 4-5-6, 7-8-9, 0). Every digit sends
 * you back to the first row, so bystanders can't read the code from the screen.
 */
@SuppressLint("ViewConstructor")
class RowPinPad(ctx: Context, private val length: Int = 4, private val onComplete: (String) -> Unit, private val onCancel: () -> Unit) : LinearLayout(ctx) {
    private val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", ""))
    private var row = 0
    private val entered = StringBuilder()
    private val dots = DotsView(ctx, length)
    private val keyRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val keys = List(3) { KeyCell(ctx) }
    private val upHint = ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); rotation = -90f; setColorFilter(C.textFaint) }
    private val downHint = ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); rotation = 90f; setColorFilter(C.textFaint) }
    private val rowSpring = Spring(0f, 520f, 0.85f, 0.002f) { v -> keyRow.translationY = v * 46.dpf; keyRow.alpha = 1f - kotlin.math.abs(v) * 0.9f }
    private val shake = Spring(0f, 900f, 0.25f, 0.3f) { dots.translationX = it }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true; isFocusableInTouchMode = true; defaultFocusHighlightEnabled = false
        // never narrower than the three keys, whatever the parent does
        minimumWidth = (3 * 108 + 2 * 22 + 24).dp
        addView(dots, lp(WRAP, WRAP).margins(b = 30.dp))
        addView(upHint, lp(28.dp, 28.dp))
        keys.forEachIndexed { i, k -> keyRow.addView(k, lp(108.dp, 108.dp).margins(l = if (i > 0) 22.dp else 0)) }
        keyRow.minimumWidth = (3 * 108 + 2 * 22).dp
        addView(keyRow, lp(WRAP, WRAP).margins(t = 6.dp, b = 6.dp))
        addView(downHint, lp(28.dp, 28.dp))
        addView(context.label(14f, 450, C.textFaint, text = "◀  ●  ▶  въвеждане   ·   ▲ ▼  смяна на реда   ·   НАЗАД  изтриване").apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(t = 22.dp))
        render()
    }

    fun reset() { entered.clear(); row = 0; dots.count = 0; render() }

    private fun render() {
        val r = rows[row]
        val arrows = listOf("◀", "OK", "▶")
        keys.forEachIndexed { i, k -> k.set(r[i], arrows[i]) }
        upHint.alpha = if (row > 0) 1f else 0.15f
        downHint.alpha = if (row < rows.size - 1) 1f else 0.15f
    }

    private fun changeRow(d: Int) {
        val nr = (row + d).coerceIn(0, rows.size - 1)
        if (nr == row) { rowSpring.nudge(d * 2.5f); return }
        row = nr; render()
        rowSpring.snapTo(d.toFloat()); rowSpring.animateTo(0f)
    }

    private fun type(col: Int) {
        val digit = rows[row][col]
        if (digit.isEmpty()) return
        keys[col].press()
        entered.append(digit); dots.count = entered.length
        if (entered.length >= length) {
            val pin = entered.toString()
            postDelayed({ onComplete(pin) }, 140)
        }
        // The row stays where it is: only ▲ / ▼ move it.
    }

    fun error() {
        entered.clear(); dots.count = 0
        shake.snapTo(0f); shake.nudge(-2600f)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> type(0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) type(1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> type(2)
            KeyEvent.KEYCODE_DPAD_DOWN -> changeRow(1)
            KeyEvent.KEYCODE_DPAD_UP -> changeRow(-1)
            KeyEvent.KEYCODE_BACK -> {
                if (event.repeatCount == 0) {
                    if (entered.isEmpty()) onCancel() else { entered.setLength(entered.length - 1); dots.count = entered.length }
                }
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                entered.append(keyCode - KeyEvent.KEYCODE_0); dots.count = entered.length
                if (entered.length >= length) { val pin = entered.toString(); postDelayed({ onComplete(pin) }, 140) }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK) || super.onKeyUp(keyCode, event)

    private class KeyCell(ctx: Context) : View(ctx) {
        private var digit = ""; private var hint = ""
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 44.dpf; textAlign = Paint.Align.CENTER; Fonts.paint(this, 420) }
        private val h = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.dpf; textAlign = Paint.Align.CENTER; color = C.textFaint; Fonts.paint(this, 600) }
        private var p = 0f
        private val s = Spring(0f, 700f, 0.7f, 0.002f) { p = it; invalidate() }
        fun set(dg: String, hn: String) { digit = dg; hint = hn; invalidate() }
        fun press() { s.snapTo(1f); s.animateTo(0f) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val ht = height.toFloat()
            if (digit.isEmpty()) return
            bg.color = com.nodevortex.aura.util.blend(0x1FFFFFFF, C.text, p)
            val inset = 6.dpf * p
            c.drawRoundRect(RectF(inset, inset, w - inset, ht - inset), 30.dpf, 30.dpf, bg)
            d.color = com.nodevortex.aura.util.blend(C.text, C.onLight, p)
            c.drawText(digit, w / 2, ht / 2 + 8.dpf, d)
            c.drawText(hint, w / 2, ht - 14.dpf, h)
        }
    }

    private class DotsView(ctx: Context, val n: Int) : View(ctx) {
        var count = 0
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((n * 34).dp, 24.dp)
        override fun onDraw(c: Canvas) {
            for (i in 0 until n) {
                val filled = i < count
                paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = 2.dpf
                paint.color = if (filled) C.text else C.textFaint
                c.drawCircle((i * 34 + 17).dpf, 12.dpf, if (filled) 9.dpf else 8.dpf, paint)
            }
        }
    }
}

/** „Кой гледа?“ screen shown on wake when the owner's profile is locked. */
class LockActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var profilesRow: LinearLayout
    private lateinit var title: android.widget.TextView
    private var pad: RowPinPad? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = FrameLayout(this)
        val ambient = AmbientBackground(this)
        root.addView(ambient, flp(MATCH, MATCH))
        ambient.setColor(ColorKit.accentOf(Lock.avatar(this, Lock.owner, 156)), immediate = true)
        val clock = label(64f, 260, C.text, text = DateFormat.format(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", Date()))
        root.addView(clock, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).margins(t = 44.dp, r = 64.dp))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = label(34f, 420, C.text, text = "Кой гледа?")
        col.addView(title, lp(WRAP, WRAP).margins(b = 40.dp))
        profilesRow = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false }
        col.addView(profilesRow, lp(WRAP, WRAP))
        root.addView(col, flp(WRAP, WRAP, Gravity.CENTER))
        root.addView(ImageView(this).apply { setImageResource(R.drawable.logo); adjustViewBounds = true; alpha = 0.85f },
            flp(WRAP, 54.dp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = 36.dp))
        setContentView(root)
        buildProfiles()
    }

    private fun buildProfiles() {
        profilesRow.removeAllViews()
        val names = Lock.profiles.ifEmpty { listOfNotNull(Lock.owner).ifEmpty { listOf("Вие") } }
        for (name in names) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
                val img = ImageView(context).apply {
                    setImageBitmap(Lock.avatar(context, name, 156)); clipToOutline = true
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF) }
                }
                addView(img, lp(128.dp, 128.dp))
                addView(context.label(18f, 500, C.text, text = name), lp(WRAP, WRAP).margins(t = 16.dp))
                if (Lock.same(name, Lock.owner) && Lock.enabled) addView(ImageView(context).apply { setImageResource(R.drawable.ic_lock); setColorFilter(C.textDim) }, lp(18.dp, 18.dp).margins(t = 6.dp))
                val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(0, C.text) }
                img.foreground = ring
                FocusFx.attach(this, 1.1f, extra = { has -> ring.setStroke(if (has) 4.dp else 0, C.text) })
                setOnClickListener { choose(name) }
            }
            profilesRow.addView(v, lp(WRAP, WRAP).margins(l = 28.dp, r = 28.dp))
        }
        profilesRow.post {
            val idx = names.indexOfFirst { Lock.same(it, Lock.active) }.coerceAtLeast(0)
            profilesRow.getChildAt(idx)?.requestFocus()
        }
    }

    private fun choose(name: String) {
        if (Lock.same(name, Lock.owner) && Lock.enabled) showPad(name) else finishUnlocked(name)
    }

    private fun showPad(name: String) {
        profilesRow.animate().alpha(0f).translationY(-30.dpf).setDuration(180).withEndAction { profilesRow.visibility = View.GONE }.start()
        title.text = "Въведете ПИН кода си"
        val p = RowPinPad(this, 4, onComplete = { pin ->
            if (Lock.verify(pin)) finishUnlocked(name) else pad?.error()
        }, onCancel = { hidePad() })
        pad = p
        (title.parent as LinearLayout).addView(p, lp(WRAP, WRAP))
        p.alpha = 0f; p.translationY = 40.dpf
        p.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Motion.decel).start()
        p.requestFocus()
    }

    private fun hidePad() {
        pad?.let { (it.parent as? LinearLayout)?.removeView(it) }; pad = null
        title.text = "Кой гледа?"
        profilesRow.visibility = View.VISIBLE
        profilesRow.animate().alpha(1f).translationY(0f).setDuration(200).start()
        profilesRow.getChildAt(0)?.requestFocus()
    }

    private fun finishUnlocked(name: String) {
        Lock.markUnlocked()
        Lock.showing = false
        val change = !Lock.same(name, Lock.active)
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
        if (change) Lock.switchTo(this, name)
    }

    override fun onResume() { super.onResume(); Lock.showing = true }
    override fun onPause() { super.onPause(); if (isFinishing) Lock.showing = false }
    override fun onStop() { super.onStop(); Lock.showing = false; if (!isFinishing && Lock.required()) Lock.show(this) }

    @Deprecated("Back is handled by the pad / ignored here")
    override fun onBackPressed() { if (pad != null) hidePad() }
}
