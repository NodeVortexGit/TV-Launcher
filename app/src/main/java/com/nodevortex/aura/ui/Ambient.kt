package com.nodevortex.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.ColorKit
import com.nodevortex.aura.util.Spring

/**
 * Google-TV-style ambient backdrop. The colour glides in OkLab space through three retargetable
 * springs (never through grey), and the glow drifts toward the focused item.
 */
class AmbientBackground(ctx: Context) : View(ctx) {

    private val lab = FloatArray(3)
    private val glowA = Paint().apply { isDither = true }
    private val glowB = Paint().apply { isDither = true }
    private val scrim = Paint().apply { isDither = true }
    private var color = 0
    private var dirty = true
    private var lastFx = -1f
    private val mA = android.graphics.Matrix()
    private val mB = android.graphics.Matrix()

    private val sL = Spring(0.42f, 55f, 1f, 0.0004f) { dirty = true; invalidate() }
    private val sA = Spring(-0.01f, 55f, 1f, 0.0004f) { dirty = true; invalidate() }
    private val sB = Spring(-0.04f, 55f, 1f, 0.0004f) { dirty = true; invalidate() }
    private val sX = Spring(0.25f, 38f, 1f, 0.0005f) { invalidate() }
    /** 0 = full glow, 1 = dimmed (for panels). */
    private val sDim = Spring(0f, 140f, 1f, 0.002f) { invalidate() }

    init {
        setWillNotDraw(false)
        // Cached on the GPU: other views animating don't re-render the full-screen gradients.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // Rendered at 1/4 resolution and scaled up ×4 by the GPU: gradients look identical but
        // cost 1/16 of the fill rate on the Chromecast's small GPU.
        pivotX = 0f; pivotY = 0f; scaleX = SCALE.toFloat(); scaleY = SCALE.toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((MeasureSpec.getSize(widthMeasureSpec) + SCALE - 1) / SCALE, (MeasureSpec.getSize(heightMeasureSpec) + SCALE - 1) / SCALE)
    }

    companion object { const val SCALE = 4 }

    fun setColor(c: Int, immediate: Boolean = false) {
        ColorKit.toLab(ColorKit.ambient(c), lab)
        if (immediate) { sL.snapTo(lab[0]); sA.snapTo(lab[1]); sB.snapTo(lab[2]) }
        else { sL.animateTo(lab[0]); sA.animateTo(lab[1]); sB.animateTo(lab[2]) }
    }

    /** Horizontal position of the focused item (0..1); the glow follows it lazily. */
    fun setFocusX(fraction: Float) = sX.animateTo(fraction.coerceIn(0f, 1f))

    fun setDim(dim: Float) = sDim.animateTo(dim)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scrim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0x33000000, 0xB3090B0F.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        dirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        canvas.drawColor(C.bg)
        val dim = 1f - 0.55f * sDim.value
        val fx = sX.value
        if (dirty) {
            // colour changed: rebuild the two gradients once, centred at the origin
            color = ColorKit.fromLab(sL.value, sA.value, sB.value)
            glowA.shader = RadialGradient(0f, 0f, w * 0.95f,
                intArrayOf(C.a(color, 0.78f), C.a(color, 0.40f), C.a(color, 0.12f), 0),
                floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP)
            val second = ColorKit.rotate(color, 0.55f, 0.36f)
            glowB.shader = RadialGradient(0f, 0f, w * 0.62f,
                intArrayOf(C.a(second, 0.40f), C.a(second, 0.12f), 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            dirty = false; lastFx = Float.NaN
        }
        if (fx != lastFx) {
            // the glow only drifts: move the existing gradients instead of allocating new ones
            lastFx = fx
            mA.setTranslate(w * (0.10f + 0.45f * fx), -h * 0.18f); glowA.shader?.setLocalMatrix(mA)
            mB.setTranslate(w * (1.05f - 0.25f * fx), h * 1.08f); glowB.shader?.setLocalMatrix(mB)
        }
        glowA.alpha = (255 * dim).toInt()
        glowB.alpha = (255 * dim).toInt()
        canvas.drawRect(0f, 0f, w, h, glowA)
        canvas.drawRect(0f, 0f, w, h, glowB)
        canvas.drawRect(0f, 0f, w, h, scrim)
        if (sDim.value > 0.001f) canvas.drawColor(C.a(0xFF000000.toInt(), 0.35f * sDim.value))
    }

    val currentColor: Int get() = color
}
