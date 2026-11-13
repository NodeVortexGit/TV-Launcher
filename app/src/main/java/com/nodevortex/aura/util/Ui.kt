package com.nodevortex.aura.util

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

val density: Float get() = Resources.getSystem().displayMetrics.density
val Number.dp: Int get() = (toFloat() * density + 0.5f).toInt()
val Number.dpf: Float get() = toFloat() * density

object Motion {
    /** Material "emphasized decelerate" – things arriving on screen. */
    val decel = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    val accel = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
}

object C {
    const val bg = 0xFF090B0F.toInt()
    const val surface = 0xFF181B22.toInt()
    const val surface2 = 0xFF22262F.toInt()
    const val surface3 = 0xFF2D323D.toInt()
    const val panel = 0xF2121419.toInt()
    const val text = 0xFFF2F4F8.toInt()
    const val textDim = 0xFFB3B9C5.toInt()
    const val textFaint = 0xFF7A8290.toInt()
    const val onLight = 0xFF15171C.toInt()
    const val accent = 0xFF8AB4F8.toInt()
    const val good = 0xFF7EE2A8.toInt()
    const val warn = 0xFFFFC56B.toInt()
    const val bad = 0xFFFF8A80.toInt()
    fun a(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

object Fonts {
    lateinit var base: Typeface
    private var variable = false

    fun init() {
        val flex = Typeface.create("google-sans-flex", Typeface.NORMAL)
        val gs = Typeface.create("google-sans", Typeface.NORMAL)
        base = when {
            flex != Typeface.DEFAULT -> { variable = true; flex }
            gs != Typeface.DEFAULT -> gs
            else -> Typeface.SANS_SERIF
        }
    }

    fun apply(tv: TextView, weight: Int) {
        tv.typeface = base
        if (!(variable && tv.setFontVariationSettings("'wght' $weight"))) {
            tv.typeface = Typeface.create(base, weight.coerceIn(100, 900), false)
        }
    }

    fun paint(p: android.graphics.Paint, weight: Int) {
        p.typeface = base
        if (!(variable && p.setFontVariationSettings("'wght' $weight"))) {
            p.typeface = Typeface.create(base, weight.coerceIn(100, 900), false)
        }
    }
}

fun roundRect(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.label(
    size: Float, weight: Int = 400, color: Int = C.text, lines: Int = 1, text: CharSequence? = null,
): TextView = TextView(this).apply {
    textSize = size
    setTextColor(color)
    Fonts.apply(this, weight)
    includeFontPadding = false
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    if (text != null) this.text = text
}

fun Context.icon(res: Int, sizeDp: Int, tint: Int = C.text): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = android.content.res.ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
    scaleType = ImageView.ScaleType.FIT_CENTER
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }
fun FrameLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

fun Context.vbox(gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; this.gravity = gravity
}
fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
}

fun View.fadeIn(duration: Long = 220, delay: Long = 0) {
    alpha = 0f; visibility = View.VISIBLE
    animate().alpha(1f).setStartDelay(delay).setDuration(duration).setInterpolator(Motion.decel).start()
}

fun View.fadeOut(duration: Long = 160, gone: Boolean = true, end: (() -> Unit)? = null) {
    animate().alpha(0f).setStartDelay(0).setDuration(duration).setInterpolator(Motion.standard).withEndAction {
        if (gone) visibility = View.GONE
        end?.invoke()
    }.start()
}

fun blend(a: Int, b: Int, t: Float): Int {
    val it = 1f - t
    return Color.argb(
        (Color.alpha(a) * it + Color.alpha(b) * t).toInt(),
        (Color.red(a) * it + Color.red(b) * t).toInt(),
        (Color.green(a) * it + Color.green(b) * t).toInt(),
        (Color.blue(a) * it + Color.blue(b) * t).toInt(),
    )
}

fun relTime(nowMs: Long, thenMs: Long): String {
    val d = (nowMs - thenMs) / 60000
    return when {
        d < 1 -> "just now"
        d < 60 -> "${d}m ago"
        d < 60 * 24 -> "${d / 60}h ago"
        else -> "${d / 1440}d ago"
    }
}
