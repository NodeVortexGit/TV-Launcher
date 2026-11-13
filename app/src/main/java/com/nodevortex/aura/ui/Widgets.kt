package com.nodevortex.aura.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nodevortex.aura.R
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.blend
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins

/** Pill button with a spring focus transition (dark → white), as used across Google TV. */
@SuppressLint("ViewConstructor")
open class Chip(
    ctx: Context, text: CharSequence?, iconRes: Int = 0,
    private val idleBg: Int = C.a(0xFFFFFFFF.toInt(), 0.0f),
    textSize: Float = 17f, val square: Boolean = false,
) : LinearLayout(ctx) {
    val icon: ImageView? = if (iconRes != 0) ImageView(ctx).apply { setImageResource(iconRes) } else null
    val title: TextView? = if (text != null) ctx.label(textSize, 500, C.text, text = text) else null
    private val bg = GradientDrawable().apply { cornerRadius = 100.dpf }
    var selectedTab = false
        set(v) { field = v; paint(spring.value) }
    private val spring = Spring(0f, 700f, 1f, 0.002f) { paint(it) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = bg
        isFocusable = true; isClickable = true
        defaultFocusHighlightEnabled = false
        val padH = if (square || text == null) 0 else 20.dp
        setPadding(padH, 0, padH, 0)
        icon?.let { addView(it, lp(24.dp, 24.dp)) }
        title?.let {
            addView(it, lp(WRAP, WRAP).margins(l = if (icon != null) 10.dp else 0))
        }
        setOnFocusChangeListener { _, has -> spring.animateTo(if (has) 1f else 0f) }
        paint(0f)
    }

    private fun paint(p: Float) {
        val base = if (selectedTab) C.a(0xFFFFFFFF.toInt(), 0.16f) else idleBg
        bg.setColor(blend(base, C.text, p))
        val fg = blend(C.text, C.onLight, p)
        title?.setTextColor(fg)
        icon?.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        val s = 1f + 0.06f * p
        scaleX = s; scaleY = s
    }
}

/** Animated on/off switch drawn by hand. */
class Toggle(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; spring.animateTo(if (v) 1f else 0f) }
    var onDark = true
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val spring = Spring(0f, 520f, 0.8f, 0.002f) { invalidate() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(46.dp, 28.dp)
    override fun onDraw(c: Canvas) {
        val p = spring.value
        track.color = blend(if (onDark) 0x55FFFFFF else 0x33000000, C.accent, p)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, height / 2f, height / 2f, track)
        thumb.color = blend(0xFFDDE1E8.toInt(), C.onLight, p)
        val rad = height / 2f - 4.dpf
        c.drawCircle(height / 2f + (width - height) * p, height / 2f, rad, thumb)
    }
}

/** A focusable list row: icon, title/subtitle and a trailing widget. Turns white on focus. */
@SuppressLint("ViewConstructor")
open class ListRow(ctx: Context, title: CharSequence, subtitle: CharSequence? = null, iconRes: Int = 0, iconBitmap: Bitmap? = null) : LinearLayout(ctx) {
    val icon = ImageView(ctx)
    val title: TextView = ctx.label(17f, 500, C.text, text = title)
    val subtitle: TextView = ctx.label(14f, 400, C.textDim, text = subtitle)
    val trailing = FrameLayout(ctx)
    private val bg = GradientDrawable().apply { cornerRadius = 18.dpf }
    private val spring = Spring(0f, 700f, 1f, 0.002f) { paint(it) }
    var tintIcon = true

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = bg
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        setPadding(18.dp, 12.dp, 18.dp, 12.dp)
        minimumHeight = 64.dp
        if (iconRes != 0 || iconBitmap != null) {
            if (iconBitmap != null) { icon.setImageBitmap(iconBitmap); tintIcon = false } else icon.setImageResource(iconRes)
            addView(icon, lp(28.dp, 28.dp).margins(r = 16.dp))
        }
        val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
        texts.addView(this.title)
        texts.addView(this.subtitle, lp(WRAP, WRAP).margins(t = 3.dp))
        if (subtitle == null) this.subtitle.visibility = GONE
        addView(texts, lp(0, WRAP, 1f))
        addView(trailing, lp(WRAP, WRAP).margins(l = 12.dp))
        setOnFocusChangeListener { _, has -> spring.animateTo(if (has) 1f else 0f) }
        paint(0f)
    }

    fun setSubtitle(s: CharSequence?) {
        subtitle.text = s; subtitle.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
    }

    open fun paint(p: Float) {
        bg.setColor(blend(C.a(0xFFFFFFFF.toInt(), 0.0f), C.text, p))
        title.setTextColor(blend(C.text, C.onLight, p))
        subtitle.setTextColor(blend(C.textDim, 0xFF4A505C.toInt(), p))
        if (tintIcon) icon.imageTintList = android.content.res.ColorStateList.valueOf(blend(C.text, C.onLight, p))
        (trailing.getChildAt(0) as? TextView)?.setTextColor(blend(C.textDim, 0xFF4A505C.toInt(), p))
        (trailing.getChildAt(0) as? Toggle)?.let { if (it.onDark != (p < 0.5f)) { it.onDark = p < 0.5f; it.invalidate() } }
        (trailing.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(blend(C.text, C.onLight, p))
    }

    fun trailingText(s: CharSequence) { trailing.removeAllViews(); trailing.addView(context.label(15f, 500, C.textDim, text = s)) }
    fun trailingIcon(res: Int) {
        trailing.removeAllViews()
        trailing.addView(ImageView(context).apply { setImageResource(res) }, FrameLayout.LayoutParams(24.dp, 24.dp))
        paint(spring.value)
    }
    fun trailingToggle(on: Boolean): Toggle {
        val t = (trailing.getChildAt(0) as? Toggle) ?: Toggle(context).also { trailing.removeAllViews(); trailing.addView(it) }
        t.checked = on
        return t
    }
}

fun Context.sectionTitle(text: String) = label(15f, 600, C.textDim, text = text).apply {
    letterSpacing = 0.02f
}

// ------------------------------------------------------------------------------------------ panels

/** Something drawn above the home screen that owns focus and the Back key until dismissed. */
abstract class Panel(val host: PanelHost) {
    val ctx: Context get() = host.root.context
    abstract val view: View
    open val dims = true
    /** How visible the home screen stays behind this panel. */
    open val homeAlpha = 0.35f
    var isOpen = false
    open fun animateIn() { view.alpha = 0f; view.animate().alpha(1f).setDuration(200).start() }
    open fun animateOut(end: () -> Unit) { view.animate().alpha(0f).setDuration(150).withEndAction(end).start() }
    open fun focusFirst() { view.requestFocus() }
    open fun onBack(): Boolean { dismiss(); return true }
    open fun onKey(e: KeyEvent): Boolean = false
    open fun onClosed() {}
    open fun onActivityResult(requestCode: Int, data: android.content.Intent?) {}
    fun dismiss() = host.close(this)
}

class PanelHost(val root: FrameLayout, private val home: ViewGroup, private val onDim: (Float, Float) -> Unit) {
    val stack = ArrayList<Panel>()
    private val restore = HashMap<Panel, View?>()
    val top get() = stack.lastOrNull()

    fun open(p: Panel) {
        if (p.isOpen) return
        restore[p] = root.findFocus()
        (stack.lastOrNull()?.view as? ViewGroup ?: home).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        stack += p
        p.isOpen = true
        if (p.view.parent == null) root.addView(p.view)
        if (p.dims) onDim(1f, stack.filter { it.dims }.minOf { it.homeAlpha })
        p.animateIn()
        p.view.post { p.focusFirst() }
    }

    fun close(p: Panel) {
        if (!p.isOpen) return
        p.isOpen = false
        stack.remove(p)
        val below = stack.lastOrNull()
        (below?.view as? ViewGroup ?: home).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (stack.none { it.dims }) onDim(0f, 1f) else onDim(1f, stack.filter { it.dims }.minOf { it.homeAlpha })
        val v = restore.remove(p)
        p.animateOut { root.removeView(p.view) }
        if (v != null && v.isAttachedToWindow && v.isShown) v.requestFocus() else (below?.view ?: home).requestFocus()
        p.onClosed()
    }

    fun closeAll() { stack.toList().asReversed().forEach { close(it) } }
    fun back(): Boolean = top?.onBack() ?: false
}

/** Small floating menu (long-press on a tile). */
class MenuPanel(host: PanelHost, private val anchor: View?, private val heading: String?, private val items: List<Triple<Int, String, () -> Unit>>) : Panel(host) {
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = SpringScrollV(ctx).apply { marginTop = 8.dp; marginBottom = 8.dp; setFadingEdgeLength(20.dp) }
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 26.dpf; setColor(0xFA1E222A.toInt()) }
        elevation = 24.dpf
        setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        if (heading != null) addView(ctx.label(15f, 600, C.textDim, text = heading).apply { setPadding(16.dp, 8.dp, 16.dp, 8.dp) })
        for ((ic, text, action) in items) list.addView(ListRow(ctx, text, null, ic).apply {
            minimumHeight = 48.dp; setPadding(16.dp, 6.dp, 16.dp, 6.dp)
            setOnClickListener { dismiss(); post { action() } }
        }, lp(MATCH, WRAP))
        scroll.addView(list)
        addView(scroll, lp(MATCH, WRAP))
    }
    override val view: View = FrameLayout(ctx).apply { addView(card, FrameLayout.LayoutParams(300.dp, WRAP)) }
    override val dims = false

    override fun animateIn() {
        val loc = IntArray(2); val rootLoc = IntArray(2)
        host.root.getLocationOnScreen(rootLoc)
        val maxH = host.root.height - 48.dp
        card.measure(View.MeasureSpec.makeMeasureSpec(300.dp, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        var h = card.measuredHeight
        if (h > maxH) {
            scroll.layoutParams = lp(MATCH, maxH - (h - scroll.measuredHeight)); h = maxH
        }
        var x = host.root.width / 2f - 150.dp; var y = host.root.height / 2f - h / 2f
        if (anchor != null) {
            anchor.getLocationOnScreen(loc)
            x = (loc[0] - rootLoc[0] + anchor.width * anchor.scaleX + 18.dp)
            if (x + 300.dp > host.root.width - 24.dp) x = (loc[0] - rootLoc[0] - 300.dp - 18.dp).toFloat()
            y = (loc[1] - rootLoc[1]).toFloat()
        }
        y = y.coerceIn(24.dpf, maxOf(24.dpf, host.root.height - h - 24.dpf))
        card.translationX = x; card.translationY = y
        card.pivotX = if (anchor != null && x > anchor.x) 0f else 300.dpf; card.pivotY = 30.dpf
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(com.nodevortex.aura.util.Motion.decel).start()
    }

    override fun animateOut(end: () -> Unit) {
        card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(120).withEndAction(end).start()
    }

    override fun focusFirst() { list.getChildAt(0)?.requestFocus() }
}

/** Simple centred dialog with text fields (folder name, Wi-Fi password, …). */
class FormPanel(
    host: PanelHost, title: String, subtitle: String?,
    private val fields: List<Field>, confirm: String,
    private val onConfirm: (List<String>) -> Unit,
) : Panel(host) {
    class Field(val hint: String, val initial: String = "", val password: Boolean = false, val choices: List<String>? = null)

    private val edits = ArrayList<View>()
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 30.dpf; setColor(0xFA1C2028.toInt()) }
        setPadding(32.dp, 28.dp, 32.dp, 24.dp)
        addView(ctx.label(26f, 450, C.text, text = title))
        if (subtitle != null) addView(ctx.label(15f, 400, C.textDim, 3, subtitle), lp(MATCH, WRAP).margins(t = 8.dp))
        for (f in fields) {
            val v: View = if (f.choices != null) ChoiceRow(ctx, f.hint, f.choices, f.initial) else edit(f)
            edits += v
            addView(v, lp(MATCH, 58.dp).margins(t = 16.dp))
        }
        val buttons = LinearLayout(ctx).apply { gravity = Gravity.END }
        val cancel = Chip(ctx, "Отказ", 0, C.a(0xFFFFFFFF.toInt(), 0.08f)).apply { setOnClickListener { dismiss() } }
        val ok = Chip(ctx, confirm, 0, C.a(0xFFFFFFFF.toInt(), 0.14f)).apply { setOnClickListener { submit() } }
        buttons.addView(cancel, lp(WRAP, 48.dp))
        buttons.addView(ok, lp(WRAP, 48.dp).margins(l = 12.dp))
        addView(buttons, lp(MATCH, WRAP).margins(t = 24.dp))
    }
    override val view: View = FrameLayout(ctx).apply {
        addView(card, FrameLayout.LayoutParams(560.dp, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).margins(t = 70.dp))
    }

    private fun edit(f: Field) = EditText(ctx).apply {
        hint = f.hint
        setText(f.initial)
        setSingleLine(true)
        textSize = 19f
        setTextColor(C.text); setHintTextColor(C.textFaint)
        Fonts.apply(this, 450)
        setPadding(20.dp, 0, 20.dp, 0)
        inputType = if (f.password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val bg = GradientDrawable().apply { cornerRadius = 16.dpf; setColor(0x22FFFFFF) }
        background = bg
        setOnFocusChangeListener { _, has -> bg.setStroke(if (has) 2.dp else 0, C.text) }
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                val idx = edits.indexOf(this)
                val next = edits.getOrNull(idx + 1)
                if (next != null) next.requestFocus() else submit()
                true
            } else false
        }
    }

    private fun submit() {
        val values = edits.map { if (it is EditText) it.text.toString() else (it as ChoiceRow).value }
        hideIme()
        dismiss()
        onConfirm(values)
    }

    private fun hideIme() {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun animateIn() {
        card.alpha = 0f; card.translationY = 30.dpf
        card.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(com.nodevortex.aura.util.Motion.decel).start()
    }

    override fun focusFirst() {
        val first = edits.firstOrNull() ?: return
        first.requestFocus()
        if (first is EditText) {
            first.setSelection(first.text.length)
            first.postDelayed({ ctx.getSystemService(InputMethodManager::class.java).showSoftInput(first, InputMethodManager.SHOW_IMPLICIT) }, 120)
        }
    }

    override fun onBack(): Boolean { hideIme(); dismiss(); return true }

    /** Left/right cycles through options. */
    class ChoiceRow(ctx: Context, hint: String, private val choices: List<String>, initial: String) : ListRow(ctx, hint) {
        var value = initial.ifEmpty { choices.first() }
        init {
            trailingText("‹  $value  ›")
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val d = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                if (d == 0) return@setOnKeyListener false
                value = choices[(choices.indexOf(value) + d + choices.size) % choices.size]
                trailingText("‹  $value  ›"); true
            }
            setOnClickListener { value = choices[(choices.indexOf(value) + 1) % choices.size]; trailingText("‹  $value  ›") }
        }
    }
}

fun toast(ctx: Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

/** Unused-resource guard so R stays referenced from this file. */
@Suppress("unused") private val keep = R.drawable.ic_check
