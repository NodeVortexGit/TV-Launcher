package com.nodevortex.aura.util

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A tiny retargetable spring driven by Choreographer (vsync-aligned).
 *
 * Retargeting keeps the current velocity, so rapid D-pad presses produce one continuous motion
 * instead of a series of restarted tweens; this is the main ingredient of "smooth as butter".
 *
 * @param stiffness  higher = faster (≈ 200 soft, 600 snappy, 1500 very fast)
 * @param damping    damping ratio (1 = critically damped, < 1 overshoots slightly)
 */
class Spring(
    initial: Float,
    var stiffness: Float = 420f,
    var damping: Float = 1f,
    private val threshold: Float = 0.001f,
    private val onUpdate: (Float) -> Unit,
) : Choreographer.FrameCallback {

    var value = initial
        private set
    var target = initial
        private set
    var velocity = 0f
        private set
    var running = false
        private set
    private var lastFrame = 0L
    var onSettled: (() -> Unit)? = null

    fun animateTo(t: Float) {
        target = t
        if (!running) {
            if (abs(value - target) < threshold && abs(velocity) < threshold) return
            running = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun snapTo(v: Float) {
        if (running) {
            Choreographer.getInstance().removeFrameCallback(this)
            running = false
        }
        value = v; target = v; velocity = 0f
        onUpdate(v)
    }

    fun nudge(v: Float) { velocity += v; animateTo(target) }

    fun cancel() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 1f / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrame = frameTimeNanos
        val steps = ceil(dt / 0.004f).toInt().coerceAtLeast(1)
        val h = dt / steps
        val omega = sqrt(stiffness)
        val c = 2f * damping * omega
        repeat(steps) {
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
        }
        if (abs(value - target) < threshold && abs(velocity) < threshold * 8f) {
            value = target; velocity = 0f; running = false
            onUpdate(value)
            onSettled?.invoke()
            return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(this)
    }
}
