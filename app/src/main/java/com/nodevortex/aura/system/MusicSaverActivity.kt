package com.nodevortex.aura.system

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.ColorKit
import com.nodevortex.aura.util.Fonts
import com.nodevortex.aura.util.dpf
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Music screensaver: album art pulsing to the beat, with a radial spectrum and ripples. */
class MusicSaverActivity : Activity() {

    private lateinit var view: PulseView
    private var viz: Visualizer? = null
    private val startedAt = SystemClock.elapsedRealtime()
    private var silentSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = PulseView(this)
        setContentView(view)
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        MediaWatcher.addListener(onMedia)
        onMedia(MediaWatcher.now)
        startVisualizer()
    }

    private val onMedia: (MediaWatcher.Now?) -> Unit = { n ->
        view.setTrack(n)
        if (n?.playing == true) silentSince = 0L else if (silentSince == 0L) silentSince = SystemClock.elapsedRealtime()
    }

    private fun startVisualizer() {
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray, rate: Int) = view.onFft(fft)
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            viz = v
        } catch (_: Throwable) { viz = null }
    }

    override fun onResume() { super.onResume(); showing = true; view.running = true; view.kick() }
    override fun onPause() {
        super.onPause(); showing = false; view.running = false
        if (!isFinishing) finish()
    }
    override fun onDestroy() {
        MediaWatcher.removeListener(onMedia)
        try { viz?.enabled = false; viz?.release() } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    fun checkTimeouts() {
        val now = SystemClock.elapsedRealtime()
        if (silentSince > 0 && now - silentSince > 60_000) finish()
        if (now - startedAt > 3 * 3600_000L) finish()
    }

    companion object { @Volatile var showing = false }

    // ======================================================================================

    private class PulseView(private val act: MusicSaverActivity) : View(act), Choreographer.FrameCallback {
        var running = false
        private var art: Bitmap? = null
        private var blurred: Bitmap? = null
        private var accent = C.accent
        private var title = ""; private var artist = ""
        private var now: MediaWatcher.Now? = null
        private val bands = FloatArray(48)
        private val shown = FloatArray(48)
        private var pulse = 0f
        private var gotFft = 0L
        private val ripples = ArrayList<FloatArray>() // [radius, alpha]
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 120 }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 5.dpf }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.dpf }
        private val tTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36.dpf; color = C.text; textAlign = Paint.Align.CENTER; Fonts.paint(this, 560) }
        private val tArtist = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 21.dpf; color = C.textDim; textAlign = Paint.Align.CENTER; Fonts.paint(this, 420) }
        private val tClock = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34.dpf; color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.RIGHT; Fonts.paint(this, 300) }
        private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 4.dpf }
        private val r = RectF(); private val m = Matrix()
        private val t0 = SystemClock.uptimeMillis()

        fun kick() = Choreographer.getInstance().postFrameCallback(this)

        fun setTrack(n: MediaWatcher.Now?) {
            now = n
            title = n?.title ?: ""; artist = n?.artist ?: ""
            val a = n?.art
            if (a != null && a !== art) {
                art = a
                accent = ColorKit.ambient(ColorKit.accentOf(a), 0.66f, 0.16f)
                blurred = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(a, 12, 12, true), 48, 48, true)
            }
            invalidate()
        }

        // ---- beat tracking -------------------------------------------------------------
        // Onsets: spectral flux of the low/low-mid bins (kick + bass) against an adaptive threshold.
        // Tempo: median of recent inter-onset intervals, folded into 70–180 BPM.
        // Pulses come from a beat clock locked to that tempo and nudged by every real onset, so the
        // rhythm stays steady even when single hits are missed — and nothing is ever faked.
        private var prevLow = FloatArray(0)
        private val flux = FloatArray(40); private var fluxI = 0
        private var lastOnset = 0L
        private val ioi = ArrayList<Long>()
        private var period = 0L          // ms per beat, 0 = unknown
        private var nextBeat = 0L
        private var energy = 0f; private var silentFrames = 0
        var onsets = 0; var beats = 0

        fun onFft(fft: ByteArray) {
            val now = SystemClock.uptimeMillis()
            gotFft = now
            val n = fft.size / 2
            val mags = FloatArray(n)
            var total = 0f
            for (k in 1 until n) { mags[k] = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()); total += mags[k] }
            if (total < 1f) { silentFrames++; return } else silentFrames = 0
            for (b in 0 until bands.size) {
                val lo = (2.0 * Math.pow((n / 2.0), b / bands.size.toDouble())).toInt().coerceIn(1, n - 1)
                val hi = (2.0 * Math.pow((n / 2.0), (b + 1) / bands.size.toDouble())).toInt().coerceIn(lo + 1, n)
                var m = 0f; for (k in lo until hi) m = max(m, mags[k])
                bands[b] = (ln(1f + m) / 5f).coerceIn(0f, 1f)
            }
            // spectral flux over ~40–550 Hz (bins 1..12 at 44.1 kHz / 1024)
            val lowN = 12.coerceAtMost(n - 1)
            if (prevLow.size != lowN) prevLow = FloatArray(lowN)
            var f = 0f; var e = 0f
            for (k in 1..lowN) {
                val v = ln(1f + mags[k]); e += v
                val d = v - prevLow[k - 1]; if (d > 0) f += d
                prevLow[k - 1] = v
            }
            energy = energy * 0.8f + e / lowN * 0.2f
            flux[fluxI] = f; fluxI = (fluxI + 1) % flux.size
            val mean = flux.average().toFloat()
            var varSum = 0f; for (x in flux) varSum += (x - mean) * (x - mean)
            val std = kotlin.math.sqrt(varSum / flux.size)
            if (f > mean + 1.4f * std && f > 0.6f && now - lastOnset > 260) onset(now)
        }

        private fun onset(now: Long) {
            onsets++
            if (lastOnset > 0) {
                var d = now - lastOnset
                if (d in 250..2000) {
                    while (d < 333) d *= 2          // >180 bpm → half time
                    while (d > 857) d /= 2          // <70 bpm → double time
                    ioi += d; if (ioi.size > 12) ioi.removeAt(0)
                    if (ioi.size >= 4) period = ioi.sorted()[ioi.size / 2]
                }
            }
            lastOnset = now
            if (period == 0L) { fire(now, 1f); return }
            // phase-correct the beat clock toward the real hit
            val err = now - (nextBeat - period)
            nextBeat = if (kotlin.math.abs(err) < period / 3) nextBeat + err / 3 else now + period
        }

        private fun fire(now: Long, strength: Float) {
            beats++
            pulse = max(pulse, strength)
            if (ripples.size < 6) ripples += floatArrayOf(0f, 0.5f * strength)
            if (period > 0) nextBeat = now + period
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val t = (now - t0) / 1000f
            val live = now - gotFft < 1500 && silentFrames < 40
            if (live && period > 0 && now >= nextBeat) {
                // tempo-locked beat; stronger when a real onset landed close to it
                fire(now, if (now - lastOnset < 120) 1f else 0.75f)
            }
            if (!live) {
                // no audio data: slow breathing, no pretend beats
                val br = (sin(t * 1.6f) + 1f) / 2f
                for (b in bands.indices) bands[b] = bands[b] * 0.92f + 0.04f * br
                pulse = max(pulse, 0.25f * br)
            }
            for (i in shown.indices) shown[i] += (bands[i] - shown[i]) * (if (bands[i] > shown[i]) 0.55f else 0.12f)
            pulse *= 0.90f
            val it = ripples.iterator()
            while (it.hasNext()) { val rp = it.next(); rp[0] += 7.dpf; rp[1] *= 0.965f; if (rp[1] < 0.02f) it.remove() }
            if ((t * 10).toInt() % 20 == 0) act.checkTimeouts()
            if (now - lastLog > 3000) {
                lastLog = now
                android.util.Log.i("AuraSaver", "live=$live fft=${now - gotFft}ms ago silent=$silentFrames onsets=$onsets beats=$beats bpm=${if (period > 0) 60000 / period else 0}")
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
        private var lastLog = 0L

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val t = (SystemClock.uptimeMillis() - t0) / 1000f
            c.drawColor(0xFF07080B.toInt())
            blurred?.let { b ->
                val s = max(w, h) * 1.25f
                r.set(w / 2 - s / 2, h / 2 - s / 2, w / 2 + s / 2, h / 2 + s / 2)
                bgPaint.alpha = (90 + 50 * pulse).toInt()
                c.drawBitmap(b, null, r, bgPaint)
            }
            c.drawColor(0x66000000)
            // slow drift for burn-in protection
            val dx = sin(t / 23f) * 34.dpf; val dy = cos(t / 31f) * 18.dpf
            val cx = w / 2 + dx; val cy = h * 0.44f + dy
            val size = min(w, h) * 0.40f * (1f + 0.045f * pulse)
            glow.shader = RadialGradient(cx, cy, size * 1.25f, intArrayOf(C.a(accent, 0.55f + 0.3f * pulse), C.a(accent, 0.0f)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, size * 1.25f, glow)
            // radial spectrum
            val n = shown.size
            bar.color = C.a(accent, 0.9f)
            for (i in 0 until n * 2) {
                val v = shown[if (i < n) i else 2 * n - 1 - i]
                val ang = (i / (n * 2f)) * 2 * PI - PI / 2
                val r0 = size * 0.62f; val r1 = r0 + 12.dpf + v * 70.dpf
                c.drawLine(cx + cos(ang).toFloat() * r0, cy + sin(ang).toFloat() * r0, cx + cos(ang).toFloat() * r1, cy + sin(ang).toFloat() * r1, bar)
            }
            for (rp in ripples) { ring.color = C.a(accent, rp[1]); c.drawCircle(cx, cy, size * 0.6f + rp[0], ring) }
            // art
            val a = art
            r.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            if (a != null) {
                artPaint.shader = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    m.setRectToRect(RectF(0f, 0f, a.width.toFloat(), a.height.toFloat()), r, Matrix.ScaleToFit.FILL); it.setLocalMatrix(m)
                }
                c.drawRoundRect(r, 30.dpf, 30.dpf, artPaint)
            } else {
                artPaint.shader = null; artPaint.color = C.surface2; c.drawRoundRect(r, 30.dpf, 30.dpf, artPaint)
            }
            val ty = cy + size / 2 + 74.dpf
            c.drawText(TextUtils.ellipsize(title, tTitle, w * 0.7f, TextUtils.TruncateAt.END).toString(), cx, ty, tTitle)
            c.drawText(TextUtils.ellipsize(artist, tArtist, w * 0.6f, TextUtils.TruncateAt.END).toString(), cx, ty + 36.dpf, tArtist)
            now?.let { nw ->
                if (nw.duration > 0) {
                    val pw = 360.dpf; val py = ty + 66.dpf
                    prog.color = 0x33FFFFFF; c.drawLine(cx - pw / 2, py, cx + pw / 2, py, prog)
                    prog.color = C.text
                    val f = (nw.positionNow().toFloat() / nw.duration).coerceIn(0f, 1f)
                    c.drawLine(cx - pw / 2, py, cx - pw / 2 + pw * f, py, prog)
                }
            }
            c.drawText(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), w - 56.dpf + dx * 0.3f, 76.dpf, tClock)
        }
    }
}

@Suppress("unused") private fun keepCtx(c: Context) = c
