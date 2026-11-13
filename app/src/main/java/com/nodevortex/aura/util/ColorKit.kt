package com.nodevortex.aura.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Perceptual colour helpers (OkLab) so colour transitions never pass through muddy greys. */
object ColorKit {

    private fun toLinear(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private fun fromLinear(v: Float): Int {
        val c = if (v <= 0.0031308f) v * 12.92f else (1.055f * v.toDouble().pow(1 / 2.4).toFloat() - 0.055f)
        return (c * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun toLab(color: Int, out: FloatArray) {
        val r = toLinear(Color.red(color)); val g = toLinear(Color.green(color)); val b = toLinear(Color.blue(color))
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    fun fromLab(L: Float, a: Float, b: Float, alpha: Int = 255): Int {
        val l = (L + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (L - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (L - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bb = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return Color.argb(alpha, fromLinear(r.coerceIn(0f, 1f)), fromLinear(g.coerceIn(0f, 1f)), fromLinear(bb.coerceIn(0f, 1f)))
    }

    /** Re-shape any colour into a pleasant ambient background tone (fixed lightness, bounded chroma). */
    fun ambient(color: Int, lightness: Float = 0.50f, maxChroma: Float = 0.13f): Int {
        val lab = FloatArray(3); toLab(color, lab)
        var ch = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        val h = atan2(lab[2], lab[1])
        ch = if (ch < 0.025f) 0.035f else ch.coerceIn(0.06f, maxChroma)
        return fromLab(lightness, ch * cos(h), ch * sin(h))
    }

    /** Rotate hue in OkLab space – used for the secondary glow so it is related but not identical. */
    fun rotate(color: Int, radians: Float, lightness: Float? = null): Int {
        val lab = FloatArray(3); toLab(color, lab)
        val ch = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        val h = atan2(lab[2], lab[1]) + radians
        return fromLab(lightness ?: lab[0], ch * cos(h), ch * sin(h))
    }

    /** Most characteristic vivid colour of an icon (hue histogram weighted by chroma). */
    fun accentOf(bmp: Bitmap): Int {
        val small = if (bmp.width > 40) Bitmap.createScaledBitmap(bmp, 40, 40, true) else bmp
        val w = small.width; val h = small.height
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        val bins = 24
        val weight = FloatArray(bins); val sumL = FloatArray(bins); val sumA = FloatArray(bins); val sumB = FloatArray(bins)
        val lab = FloatArray(3)
        var greyW = 0f; var greyL = 0f
        for (c in px) {
            val alpha = Color.alpha(c) / 255f
            if (alpha < 0.5f) continue
            toLab(c, lab)
            val ch = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            if (ch < 0.04f) { greyW += alpha; greyL += lab[0] * alpha; continue }
            val hue = ((atan2(lab[2], lab[1]) + Math.PI) / (2 * Math.PI) * bins).toInt().coerceIn(0, bins - 1)
            // favour saturated, mid-light pixels
            val wgt = alpha * ch * (1f - kotlin.math.abs(lab[0] - 0.62f))
            weight[hue] += wgt; sumL[hue] += lab[0] * wgt; sumA[hue] += lab[1] * wgt; sumB[hue] += lab[2] * wgt
        }
        var best = -1; var bestW = 0f
        for (i in 0 until bins) {
            val wsum = weight[i] + weight[(i + 1) % bins] * 0.5f + weight[(i + bins - 1) % bins] * 0.5f
            if (wsum > bestW) { bestW = wsum; best = i }
        }
        if (best < 0 || bestW < 0.6f) {
            val l = if (greyW > 0) greyL / greyW else 0.5f
            return fromLab(l.coerceIn(0.3f, 0.7f), -0.01f, -0.035f) // neutral slate
        }
        return fromLab(sumL[best] / weight[best], sumA[best] / weight[best], sumB[best] / weight[best])
    }

    fun luminance(color: Int): Float {
        val lab = FloatArray(3); toLab(color, lab); return lab[0]
    }
}
