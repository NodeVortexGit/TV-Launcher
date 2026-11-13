package com.nodevortex.aura.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.nodevortex.aura.data.AppRepository
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf

/** Spring-driven focus scale for any view, with an optional progress callback for custom drawing. */
class FocusFx(private val view: View, private val scaleUp: Float = 1.1f, private val onProgress: ((Float) -> Unit)? = null) {
    val spring = Spring(0f, 560f, 0.74f, 0.0008f) { p ->
        val s = 1f + (scaleUp - 1f) * p
        view.scaleX = s; view.scaleY = s
        onProgress?.invoke(p)
    }
    fun set(focused: Boolean) = spring.animateTo(if (focused) 1f else 0f)
    val progress get() = spring.value

    companion object {
        fun attach(v: View, scale: Float = 1.06f, extra: ((Boolean) -> Unit)? = null, onProgress: ((Float) -> Unit)? = null): FocusFx {
            val fx = FocusFx(v, scale, onProgress)
            v.setOnFocusChangeListener { _, has -> fx.set(has); extra?.invoke(has) }
            return fx
        }
    }
}

private object Shadows {
    private val cache = HashMap<Int, Bitmap>()
    fun get(size: Int): Bitmap = cache.getOrPut(size) {
        val blur = (size * 0.10f)
        val b = Bitmap.createBitmap(size + (blur * 4).toInt(), size + (blur * 4).toInt(), Bitmap.Config.ALPHA_8)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL); alpha = 200 }
        val r = size * AppRepository.TILE_RADIUS
        c.drawRoundRect(RectF(blur * 2, blur * 2, blur * 2 + size, blur * 2 + size), r, r, p)
        b
    }
}

/**
 * A big square home tile: an app icon, or a folder with a 2×2 preview. Drawn by hand so a grid of
 * dozens costs one display list each and focus animations stay on the RenderThread.
 */
@SuppressLint("ViewConstructor")
class TileView(ctx: Context, val tileSize: Int) : View(ctx) {

    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var minis: List<Bitmap?> = emptyList()
        set(v) { field = v; invalidate() }
    var isFolder = false
    var label: String = ""
        set(v) { field = v; ellipsized = null; invalidate() }
    var alwaysLabel = false
    var accent: Int = C.accent
    var lifted = false
        set(v) { field = v; fx.spring.stiffness = if (v) 380f else 560f; invalidate(); updateScale() }
    var badge: String? = null

    private val labelH = 30.dp
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0xFF000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.dpf; color = C.text }
    private val liftRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.dpf; pathEffect = DashPathEffect(floatArrayOf(10.dpf, 7.dpf), 0f)
    }
    private val folderBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15.dpf; color = C.text; textAlign = Paint.Align.CENTER; Fonts.paint(this, 500)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.dpf; color = C.onLight; textAlign = Paint.Align.CENTER; Fonts.paint(this, 650) }
    private var ellipsized: CharSequence? = null
    private val rect = RectF()
    private var p = 0f
    val fx = FocusFx(this, 1.0f) { prog -> p = prog; updateScale(); invalidate() }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, has -> fx.set(has) }
    }

    private fun updateScale() {
        val s = 1f + 0.11f * p + if (lifted) 0.06f else 0f
        scaleX = s; scaleY = s
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) = setMeasuredDimension(tileSize, tileSize + labelH)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        pivotX = w / 2f; pivotY = tileSize / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val s = tileSize.toFloat()
        val r = s * AppRepository.TILE_RADIUS
        // shadow
        if (p > 0.01f || lifted) {
            val sh = Shadows.get(tileSize)
            val off = (sh.width - tileSize) / 2f
            shadowPaint.alpha = (150 * maxOf(p, if (lifted) 1f else 0f)).toInt()
            canvas.drawBitmap(sh, -off, -off + 7.dpf * p, shadowPaint)
        }
        rect.set(0f, 0f, s, s)
        if (isFolder) {
            folderBg.color = C.a(0xFFFFFFFF.toInt(), 0.10f + 0.06f * p)
            canvas.drawRoundRect(rect, r, r, folderBg)
            val pad = s * 0.12f; val gap = s * 0.06f
            val cell = (s - pad * 2 - gap) / 2f
            for (i in 0 until minOf(4, minis.size)) {
                val b = minis[i] ?: continue
                val x = pad + (i % 2) * (cell + gap); val y = pad + (i / 2) * (cell + gap)
                canvas.drawBitmap(b, null, RectF(x, y, x + cell, y + cell), tilePaint)
            }
        } else {
            val b = bitmap
            if (b != null) canvas.drawBitmap(b, null, rect, tilePaint)
            else { folderBg.color = C.a(0xFFFFFFFF.toInt(), 0.06f); canvas.drawRoundRect(rect, r, r, folderBg) }
        }
        if (p > 0.01f) {
            ring.alpha = (255 * p).toInt()
            val o = 5.dpf
            rect.set(-o, -o, s + o, s + o)
            canvas.drawRoundRect(rect, r + o, r + o, ring)
        }
        if (lifted) {
            liftRing.color = accent
            val o = 9.dpf
            rect.set(-o, -o, s + o, s + o)
            canvas.drawRoundRect(rect, r + o, r + o, liftRing)
        }
        badge?.let {
            badgePaint.color = C.text
            val bw = maxOf(22.dpf, badgeText.measureText(it) + 12.dpf)
            rect.set(s - bw + 4.dpf, -6.dpf, s + 4.dpf, 16.dpf)
            canvas.drawRoundRect(rect, 11.dpf, 11.dpf, badgePaint)
            canvas.drawText(it, rect.centerX(), rect.bottom - 6.dpf, badgeText)
        }
        val la = if (alwaysLabel) 0.62f + 0.38f * p else p
        if (la > 0.01f) {
            if (ellipsized == null) ellipsized = TextUtils.ellipsize(label, text, s + 24.dpf, TextUtils.TruncateAt.END)
            text.alpha = (255 * la).toInt()
            canvas.drawText(ellipsized!!, 0, ellipsized!!.length, s / 2f, s + 23.dpf + 4.dpf * p, text)
        }
    }
}
