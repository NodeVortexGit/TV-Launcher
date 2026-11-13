package com.nodevortex.aura.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins

/** Thin progress bar drawn at the bottom of a card image. */
class ProgressLine(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = 0x55FFFFFF; c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = C.text; c.drawRoundRect(0f, 0f, w * progress.coerceIn(0f, 1f), h, h / 2, h / 2, p)
    }
}

/**
 * Generic focusable card: image area (logo on a light plate, or cover art), a badge, optional
 * progress, and two lines of text. Scales up with a spring and gets a white ring on focus.
 */
@SuppressLint("ViewConstructor")
class Card(ctx: Context, val w: Int, val imgH: Int, logoStyle: Boolean) : LinearLayout(ctx) {
    val image = ImageView(ctx)
    private val plate = FrameLayout(ctx)
    val title: TextView = ctx.label(16f, 550, C.text)
    val subtitle: TextView = ctx.label(13.5f, 420, C.textDim)
    val badge: TextView = ctx.label(12f, 700, C.onLight).apply {
        background = GradientDrawable().apply { cornerRadius = 100.dpf; setColor(C.text) }
        setPadding(9.dp, 4.dp, 9.dp, 4.dp); visibility = GONE; letterSpacing = 0.04f
    }
    val progress = ProgressLine(ctx).apply { visibility = GONE }
    private val ring = GradientDrawable().apply { cornerRadius = 20.dpf; setStroke(0, C.text) }
    var accent: Int = C.accent
    var onFocused: ((Card) -> Unit)? = null

    init {
        orientation = VERTICAL
        isFocusable = true; isClickable = true; isLongClickable = true; defaultFocusHighlightEnabled = false
        clipChildren = false
        plate.background = GradientDrawable().apply { cornerRadius = 18.dpf; setColor(if (logoStyle) 0xFFE9EDF3.toInt() else C.surface2) }
        plate.clipToOutline = true
        plate.foreground = ring
        if (logoStyle) {
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            plate.addView(image, flp(MATCH, MATCH).margins(w / 5, imgH / 4, w / 5, imgH / 4 + 4.dp))
        } else {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            plate.addView(image, flp(MATCH, MATCH))
        }
        plate.addView(badge, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).margins(l = 10.dp, t = 10.dp))
        plate.addView(progress, flp(MATCH, 4.dp, Gravity.BOTTOM).margins(l = 12.dp, r = 12.dp, b = 10.dp))
        addView(plate, lp(w, imgH))
        addView(title, lp(w, WRAP).margins(t = 12.dp))
        addView(subtitle, lp(w, WRAP).margins(t = 3.dp))
        pivotY = imgH / 2f
        FocusFx.attach(this, 1.07f, extra = { has ->
            ring.setStroke(if (has) 3.dp else 0, C.text)
            if (has) onFocused?.invoke(this)
        })
    }

    fun setBadge(text: String?, live: Boolean = false) {
        if (text == null) { badge.visibility = GONE; return }
        badge.text = text; badge.visibility = VISIBLE
        (badge.background as GradientDrawable).setColor(if (live) 0xFFFF5A5F.toInt() else C.text)
        badge.setTextColor(if (live) C.text else C.onLight)
    }

    fun setProgress(p: Float?) {
        if (p == null) { progress.visibility = GONE } else { progress.visibility = VISIBLE; progress.progress = p }
    }

    fun setImage(b: Bitmap?) { if (b != null) image.setImageBitmap(b) }
}

/** A titled horizontal row of cards inside a vertical page. */
class CardRow(ctx: Context, titleText: String) : LinearLayout(ctx) {
    val heading = ctx.sectionTitle(titleText)
    val scroller = SpringScrollH(ctx)
    val strip = LinearLayout(ctx).apply { orientation = HORIZONTAL; clipChildren = false; clipToPadding = false }

    init {
        orientation = VERTICAL
        clipChildren = false; clipToPadding = false
        addView(heading, lp(WRAP, WRAP).margins(l = 58.dp, b = 14.dp))
        scroller.setPadding(58.dp, 10.dp, 58.dp, 10.dp)
        scroller.addView(strip)
        addView(scroller, lp(MATCH, WRAP))
    }

    fun add(v: View, gap: Int = 22.dp) {
        strip.addView(v, lp(WRAP, WRAP).margins(r = gap))
    }

    val count get() = strip.childCount
    fun clear() { strip.removeAllViews(); scroller.reset() }
}
