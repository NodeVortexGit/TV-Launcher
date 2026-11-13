package com.nodevortex.aura.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nodevortex.aura.R
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Motion
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.blend
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins

/** Base for windows drawn by the accessibility service above every app (no focus stealing). */
abstract class Overlay(protected val ctx: Context) {
    protected val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    abstract val root: View
    var attached = false
        private set

    /** Where the window sits on screen. */
    protected open val gravity: Int = Gravity.CENTER
    /** Full-width/height windows let the content grow or shrink later without resizing the window. */
    protected open val fullWidth = false
    protected open val fullHeight = false

    // Not FLAG_NOT_TOUCHABLE: Android caps such overlays at 80 % opacity. A TV has no touch anyway.
    protected open fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).also {
        it.gravity = gravity
        if (fullWidth) it.width = WindowManager.LayoutParams.MATCH_PARENT
        if (fullHeight) it.height = WindowManager.LayoutParams.MATCH_PARENT
    }

    fun attach() { if (!attached) { try { wm.addView(root, params()); attached = true } catch (_: Throwable) { } } }
    fun detach() { if (attached) { try { wm.removeView(root) } catch (_: Throwable) { }; attached = false } }
}

// ===================================================================================== volume

/** The capsule itself: glassy track, white fill rising from the bottom, number + output icon. */
class VolumeCapsule(ctx: Context) : View(ctx) {
    var level = 0f
    var number = "0"
    var muted = false
    var iconRes = R.drawable.ic_tv
        set(v) { field = v; icon = context.getDrawable(v)?.mutate() }
    private var icon = ctx.getDrawable(R.drawable.ic_tv)?.mutate()
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6161A21.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.dpf; color = 0x1FFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26.dpf; textAlign = Paint.Align.CENTER; Fonts.paint(this, 520) }
    private val r = RectF()
    val fillSpring = Spring(0f, 520f, 0.9f, 0.0005f) { level = it; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(76.dp, 310.dp)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        fill.shader = LinearGradient(0f, h.toFloat(), 0f, 0f, 0xFFFFFFFF.toInt(), 0xFFE3EAFF.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = w / 2f
        r.set(0f, 0f, w, h)
        val save = c.saveLayer(r, null)
        c.drawRoundRect(r, rad, rad, base)
        val top = h - h * level.coerceIn(0f, 1f)
        c.drawRect(0f, top, w, h, fill)
        c.restoreToCount(save)
        c.drawRoundRect(r, rad, rad, border)
        // number: dark when the fill reaches it
        val ny = 46.dpf
        num.color = if (top < ny - 10.dpf) C.onLight else C.text
        c.drawText(if (muted) "–" else number, w / 2f, ny, num)
        icon?.let { d ->
            val s = 26.dp; val cy = (h - 40.dpf).toInt()
            d.setBounds((w / 2 - s / 2).toInt(), cy - s / 2, (w / 2 + s / 2).toInt(), cy + s / 2)
            d.setTint(if (top < cy) C.onLight else C.text)
            d.draw(c)
        }
    }
}

@SuppressLint("SetTextI18n")
class VolumeOverlay(ctx: Context) : Overlay(ctx) {
    override val gravity = Gravity.END or Gravity.CENTER_VERTICAL
    override val fullHeight = true
    private val capsule = VolumeCapsule(ctx)
    private val hint = ctx.label(14f, 500, C.textDim).apply {
        background = GradientDrawable().apply { cornerRadius = 100.dpf; setColor(0xE6161A21.toInt()) }
        setPadding(16.dp, 9.dp, 16.dp, 9.dp)
    }
    private val picker = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 28.dpf; setColor(0xF0161A21.toInt()); setStroke(1.dp, 0x1FFFFFFF) }
        setPadding(10.dp, 16.dp, 10.dp, 10.dp)
        visibility = View.GONE
    }
    private val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    override val root = FrameLayout(ctx).apply {
        column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(picker, lp(380.dp, WRAP))
        }, lp(WRAP, WRAP).margins(r = 18.dp))
        column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(capsule)
            addView(hint, lp(WRAP, WRAP).margins(t = 14.dp))
        })
        addView(column, flp(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).margins(r = 44.dp))
    }
    private val slide = Spring(1f, 300f, 0.86f, 0.001f) { p ->
        column.translationX = p * 220.dp; column.alpha = 1f - p
        if (p >= 0.999f && slideTarget == 1f) detach()
    }
    private var slideTarget = 1f
    private val hideRunnable = Runnable { hide() }
    var outputs: List<AudioOutputs.Output> = emptyList()
        private set
    var pickerOpen = false
        private set
    private var sel = 0

    val visible get() = attached && slideTarget == 0f

    fun show(index: Int, max: Int, muted: Boolean, out: AudioOutputs.Output) {
        attach()
        capsule.number = index.toString(); capsule.muted = muted
        capsule.iconRes = if (muted) R.drawable.ic_volume_off else AudioOutputs.icon(out.kind)
        capsule.fillSpring.animateTo(if (muted || max == 0) 0f else index.toFloat() / max)
        hint.text = "${out.name}  ·  OK за изходи"
        slideTarget = 0f; slide.animateTo(0f)
        scheduleHide(2600)
    }

    fun scheduleHide(ms: Long) { root.removeCallbacks(hideRunnable); root.postDelayed(hideRunnable, ms) }

    fun hide() {
        root.removeCallbacks(hideRunnable)
        closePicker()
        slideTarget = 1f; slide.animateTo(1f)
    }

    fun openPicker() {
        outputs = AudioOutputs.list(ctx)
        sel = outputs.indexOfFirst { it.active }.coerceAtLeast(0)
        pickerOpen = true
        render()
        picker.visibility = View.VISIBLE
        picker.alpha = 0f; picker.translationX = 40.dpf
        picker.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(Motion.decel).start()
        hint.text = "▲▼ избор  ·  OK потвърждение  ·  НАЗАД затваряне"
        scheduleHide(9000)
    }

    fun closePicker() {
        if (!pickerOpen) return
        pickerOpen = false
        picker.animate().alpha(0f).translationX(30.dpf).setDuration(150).withEndAction { picker.visibility = View.GONE }.start()
    }

    private val extraRows get() = listOf("Сдвояване на ново устройство…", "Още опции за изхода…")

    fun move(d: Int) {
        val n = outputs.size + extraRows.size
        sel = (sel + d + n) % n
        render(); scheduleHide(9000)
    }

    /** Returns the chosen output, or an index for the extra rows (−1 pair, −2 more). */
    fun choose(): Any = if (sel < outputs.size) outputs[sel] else -(sel - outputs.size + 1)

    private fun render() {
        picker.removeAllViews()
        picker.addView(ctx.label(15f, 600, C.textDim, text = "Пусни звука на").apply { setPadding(14.dp, 0, 14.dp, 10.dp) })
        outputs.forEachIndexed { i, o ->
            val sub = when {
                o.active -> "Звукът е тук" + if (o.battery >= 0) " · ${o.battery}%" else ""
                o.connected -> "Свързано" + if (o.battery >= 0) " · ${o.battery}%" else ""
                o.kind == AudioOutputs.Kind.TV -> "HDMI"
                else -> "Сдвоено"
            }
            picker.addView(row(AudioOutputs.icon(o.kind), o.name, sub, i == sel, o.active))
        }
        extraRows.forEachIndexed { j, t -> picker.addView(row(if (j == 0) R.drawable.ic_add else R.drawable.ic_tune, t, null, sel == outputs.size + j, false)) }
    }

    private fun row(icon: Int, title: String, sub: String?, selected: Boolean, active: Boolean): View {
        val fg = if (selected) C.onLight else C.text
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 18.dpf; setColor(if (selected) C.text else 0) }
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(fg) }, lp(26.dp, 26.dp).margins(r = 14.dp))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.label(17f, 500, fg, text = title))
                if (sub != null) addView(ctx.label(13f, 400, if (selected) 0xFF4A505C.toInt() else C.textDim, text = sub), lp(WRAP, WRAP).margins(t = 2.dp))
            }, lp(0, WRAP, 1f))
            if (active) addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_check); setColorFilter(fg) }, lp(22.dp, 22.dp))
        }.also { it.layoutParams = lp(MATCH, WRAP).margins(b = 4.dp) }
    }
}

// ===================================================================================== toasts

/** Notification pop-ups that slide in at the top right over any app. */
class ToastStack(ctx: Context) : Overlay(ctx) {
    override val gravity = Gravity.END or Gravity.TOP
    override val fullHeight = true
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    override val root = FrameLayout(ctx).apply { addView(col, flp(440.dp, WRAP, Gravity.END or Gravity.TOP).margins(t = 32.dp, r = 36.dp)) }

    fun show(item: NotificationHub.Item) {
        attach()
        if (col.childCount >= 3) col.removeViewAt(col.childCount - 1)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24.dpf; setColor(0xF21B1F27.toInt()); setStroke(1.dp, 0x1AFFFFFF) }
            setPadding(16.dp, 14.dp, 20.dp, 14.dp)
            elevation = 12.dpf
            val ic = ImageView(ctx).apply {
                if (item.icon != null) setImageBitmap(item.icon) else setImageResource(R.drawable.ic_bell)
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = 12.dpf; setColor(0x22FFFFFF) }
            }
            addView(ic, lp(44.dp, 44.dp).margins(r = 14.dp))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.label(12f, 600, C.textFaint, text = item.app.uppercase()).apply { letterSpacing = 0.06f })
                addView(ctx.label(17f, 550, C.text, text = item.title.ifEmpty { item.app }), lp(WRAP, WRAP).margins(t = 2.dp))
                if (item.text.isNotEmpty()) addView(ctx.label(14f, 400, C.textDim, 2, item.text), lp(WRAP, WRAP).margins(t = 2.dp))
            }, lp(0, WRAP, 1f))
        }
        col.addView(card, 0, lp(MATCH, WRAP).margins(b = 12.dp))
        card.translationX = 480.dpf
        val s = Spring(480.dpf, 260f, 0.82f, 0.5f) { card.translationX = it }
        s.animateTo(0f)
        card.postDelayed({
            s.onSettled = { col.removeView(card); if (col.childCount == 0) detach() }
            s.animateTo(500.dpf)
        }, 6500)
    }
}

// ===================================================================================== earbuds

/**
 * Battery gauges that always fill the card's width: three rings spread evenly for Galaxy Buds
 * (left / right / case), or a big percentage + full-width bar when there is a single value.
 */
class BatteryRings(ctx: Context) : View(ctx) {
    var values: List<Pair<String, Int>> = emptyList()
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6.dpf; color = 0x26FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6.dpf; strokeCap = Paint.Cap.ROUND }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pct = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17.dpf; color = C.text; textAlign = Paint.Align.CENTER; Fonts.paint(this, 600) }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34.dpf; color = C.text; Fonts.paint(this, 500) }
    private val cap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.dpf; color = C.textDim; textAlign = Paint.Align.CENTER; Fonts.paint(this, 500) }
    private val r = RectF()
    private var progress = 0f
    val grow = Spring(0f, 120f, 1f, 0.001f) { progress = it; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 96.dp)

    private fun colorFor(v: Int) = when { v <= 15 -> C.bad; v <= 30 -> C.warn; else -> C.good }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        if (values.size == 1) {
            val (name, v) = values[0]
            // big number on the left, label under it, long bar across the rest
            big.textAlign = Paint.Align.LEFT
            val txt = if (v >= 0) "$v%" else "–"
            c.drawText(txt, 0f, 44.dpf, big)
            cap.textAlign = Paint.Align.LEFT
            c.drawText(name, 2.dpf, 72.dpf, cap)
            val x0 = big.measureText("100%") + 26.dpf
            val cy = 33.dpf; val hgt = 14.dpf
            r.set(x0, cy - hgt / 2, w - 6.dpf, cy + hgt / 2)
            bar.color = 0x26FFFFFF; c.drawRoundRect(r, hgt / 2, hgt / 2, bar)
            if (v >= 0) {
                bar.color = colorFor(v)
                r.right = x0 + (w - 6.dpf - x0) * (v / 100f) * progress
                c.drawRoundRect(r, hgt / 2, hgt / 2, bar)
            }
            cap.textAlign = Paint.Align.CENTER
            return
        }
        val n = values.size.coerceAtLeast(1)
        val slot = w / n
        values.forEachIndexed { i, (name, v) ->
            val cx = slot * i + slot / 2; val cy = 36.dpf; val rad = 30.dpf
            r.set(cx - rad, cy - rad, cx + rad, cy + rad)
            c.drawOval(r, track)
            if (v >= 0) { arc.color = colorFor(v); c.drawArc(r, -90f, 360f * v / 100f * progress, false, arc) }
            c.drawText(if (v >= 0) "$v%" else "–", cx, cy + 6.dpf, pct)
            c.drawText(name, cx, 90.dpf, cap)
        }
    }
}

/** Samsung-style "your earbuds are connected" card with battery levels. */
class BudsPopup(ctx: Context) : Overlay(ctx) {
    private val CARD_W = 640.dp
    override val gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    override val fullWidth = true
    private val art = ImageView(ctx)
    private val name = ctx.label(22f, 550, C.text)
    private val status = ctx.label(14f, 450, C.textDim)
    private val rings = BatteryRings(ctx)
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = 34.dpf; setColor(0xF5181C23.toInt()); setStroke(1.dp, 0x1AFFFFFF) }
        setPadding(28.dp, 22.dp, 36.dp, 18.dp)
        elevation = 20.dpf
        addView(FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1AFFFFFF) }
            addView(art, flp(54.dp, 54.dp, Gravity.CENTER))
        }, lp(96.dp, 96.dp).margins(r = 26.dp))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(status, lp(WRAP, WRAP).margins(t = 4.dp, b = 10.dp))
            addView(rings, lp(MATCH, WRAP))
        }, lp(0, WRAP, 1f))
    }
    override val root = FrameLayout(ctx).apply { addView(card, flp(CARD_W, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = 48.dp)) }
    private val slide = Spring(1f, 240f, 0.8f, 0.001f) { p -> card.translationY = p * 260.dp; card.alpha = 1f - p * 0.8f; if (p > 0.999f && target == 1f) detach() }
    private var target = 1f
    private var current: String? = null

    @SuppressLint("MissingPermission")
    fun show(d: BluetoothDevice, connected: Boolean = true) {
        attach()
        current = d.address
        art.setImageResource(Bt.kindIcon(d)); art.setColorFilter(C.text)
        name.text = Bt.displayName(d)
        status.text = if (connected) "Свързано с Chromecast" else "Изключено"
        val b = Bt.battery(d)
        rings.values = listOf("Батерия" to b)
        rings.grow.snapTo(0f); rings.grow.animateTo(1f)
        target = 0f; slide.animateTo(0f)
        root.removeCallbacks(hideR); root.postDelayed(hideR, 7000)
        if (connected && GalaxyBuds.isGalaxy(d)) {
            GalaxyBuds.query(d) { gb ->
                if (gb != null && current == d.address) {
                    rings.values = listOf("Ляво" to gb.left, "Дясно" to gb.right, "Кутия" to (if (gb.case in 0..100) gb.case else -1))
                    rings.grow.snapTo(0f); rings.grow.animateTo(1f)
                    root.removeCallbacks(hideR); root.postDelayed(hideR, 6000)
                }
            }
        }
    }

    /** Layout preview (used by `--es aura.open buds-test`). */
    fun preview(title: String, values: List<Pair<String, Int>>) {
        attach(); current = null
        art.setImageResource(R.drawable.ic_headphones); art.setColorFilter(C.text)
        name.text = title; status.text = "Свързано с Chromecast"
        rings.values = values; rings.grow.snapTo(0f); rings.grow.animateTo(1f)
        target = 0f; slide.animateTo(0f)
        root.removeCallbacks(hideR); root.postDelayed(hideR, 6000)
    }

    fun battery(addr: String, level: Int) {
        if (addr != current || !attached) return
        if (rings.values.size == 1) { rings.values = listOf("Батерия" to level); rings.invalidate() }
    }

    private val hideR = Runnable { target = 1f; slide.animateTo(1f) }
}

// ===================================================================================== sleep warning

class SleepWarning(ctx: Context) : Overlay(ctx) {
    override val gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    override val fullWidth = true
    val text: TextView = ctx.label(20f, 500, C.text)
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = 100.dpf; setColor(0xF5181C23.toInt()) }
        setPadding(26.dp, 16.dp, 30.dp, 16.dp)
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_moon); setColorFilter(C.text) }, lp(26.dp, 26.dp).margins(r = 14.dp))
        addView(text)
    }
    override val root = FrameLayout(ctx).apply { addView(card, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(t = 40.dp)) }
}

@Suppress("unused") private val keepBitmap: Bitmap? = null
@Suppress("unused") private val keepBlend = ::blend

/** Small status pill at the top (e.g. "Превключване към Ивелин…") while Aura works in the background. */
class StatusPill(ctx: Context) : Overlay(ctx) {
    override val gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    override val fullWidth = true
    private val text: TextView = ctx.label(18f, 500, C.text)
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = 100.dpf; setColor(0xFA181C23.toInt()); setStroke(1.dp, 0x1AFFFFFF) }
        setPadding(24.dp, 14.dp, 28.dp, 14.dp)
        addView(android.widget.ProgressBar(ctx).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(C.text) }, lp(24.dp, 24.dp).margins(r = 14.dp))
        addView(text)
    }
    override val root = FrameLayout(ctx).apply { addView(card, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(t = 36.dp)) }
    fun show(t: String) { text.text = t; attach(); card.alpha = 0f; card.animate().alpha(1f).setDuration(180).start() }
    fun hide() { if (attached) card.animate().alpha(0f).setDuration(150).withEndAction { detach() }.start() }
}

// ===================================================================================== profile switch cover

/**
 * Full-screen VeloCinema screen shown while a Google TV profile switch runs. Google's own profile
 * menu has to be operated underneath (only it may switch profiles), so this keeps it out of sight.
 * Not focusable: the accessibility helper still sees and clicks Google's window below it.
 */
class SwitchCover(ctx: Context) : Overlay(ctx) {
    override val fullWidth = true
    override val fullHeight = true
    private val avatar = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
        } }
    private val title: TextView = ctx.label(34f, 560, C.text).apply { gravity = Gravity.CENTER }
    private val sub: TextView = ctx.label(18f, 420, C.textDim).apply { gravity = Gravity.CENTER }
    private val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(avatar, lp(150.dp, 150.dp).margins(b = 32.dp))
        addView(title, lp(WRAP, WRAP))
        addView(sub, lp(WRAP, WRAP).margins(t = 10.dp))
        addView(android.widget.ProgressBar(ctx).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(C.text) },
            lp(36.dp, 36.dp).margins(t = 36.dp))
    }
    override val root = FrameLayout(ctx).apply {
        setBackgroundColor(C.bg)
        addView(col, flp(WRAP, WRAP, Gravity.CENTER))
        addView(ImageView(ctx).apply { setImageResource(R.drawable.logo); adjustViewBounds = true; alpha = 0.85f },
            flp(WRAP, 54.dp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = 36.dp))
    }

    fun show(name: String?, learn: Boolean) {
        avatar.setImageBitmap(com.nodevortex.aura.lock.Lock.avatar(ctx, name, 300))
        avatar.visibility = if (learn) View.GONE else View.VISIBLE
        title.text = if (learn) "Зареждам профилите…" else "Здравей, ${com.nodevortex.aura.lock.Lock.displayName(name).substringBefore(' ')}"
        sub.text = if (learn) "Само момент" else "Превключвам профила…"
        val fresh = !attached
        attach()
        if (fresh) { root.alpha = 0f; root.animate().alpha(1f).setDuration(160).start() }
    }
    fun status(t: String) { sub.text = t }
    fun hide() { if (attached) root.animate().alpha(0f).setDuration(220).withEndAction { detach() }.start() }
}
