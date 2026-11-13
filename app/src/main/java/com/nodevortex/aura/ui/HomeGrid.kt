package com.nodevortex.aura.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.dp

/**
 * Fixed-column grid with deterministic D-pad navigation (no diagonal surprises) and
 * FLIP-style spring animation when items are reordered.
 */
class HomeGrid(ctx: Context) : ViewGroup(ctx) {

    var columns = 6
    var cell = 120.dp
    var gapX = 28.dp
    var gapY = 18.dp
    var cellH = 0
    /** Where focus goes when leaving the first row upwards. */
    var onExitUp: (() -> Boolean)? = null
    var onFocusIndex: ((Int, View) -> Unit)? = null
    var lastFocused = 0
        private set
    private val springs = HashMap<View, Pair<Spring, Spring>>()

    init {
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = false
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        var ch = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            ch = maxOf(ch, c.measuredHeight)
        }
        cellH = if (ch == 0) cell else ch
        val rows = (childCount + columns - 1) / columns
        val h = paddingTop + paddingBottom + rows * cellH + (rows - 1).coerceAtLeast(0) * gapY
        setMeasuredDimension(w, h)
    }

    /** Total width the grid needs, to centre it in the page. */
    fun contentWidth() = columns * cell + (columns - 1) * gapX

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = paddingLeft + (i % columns) * (cell + gapX)
            val y = paddingTop + (i / columns) * (cellH + gapY)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    /** Animate children from their previous positions after a reorder (call before mutating). */
    fun snapshot(): Map<Any, Rect> {
        val m = HashMap<Any, Rect>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            m[c.tag ?: c] = Rect(c.left + c.translationX.toInt(), c.top + c.translationY.toInt(), c.right, c.bottom)
        }
        return m
    }

    fun animateFrom(before: Map<Any, Rect>) {
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    val old = before[c.tag ?: c] ?: continue
                    val dx = (old.left - c.left).toFloat(); val dy = (old.top - c.top).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    val (sx, sy) = springs.getOrPut(c) {
                        Spring(0f, 480f, 0.85f, 0.5f) { c.translationX = it } to Spring(0f, 480f, 0.85f, 0.5f) { c.translationY = it }
                    }
                    sx.snapTo(dx); sy.snapTo(dy); sx.animateTo(0f); sy.animateTo(0f)
                }
                return true
            }
        })
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val i = if (focused == null) -1 else childIndexOf(focused)
        if (i < 0) return super.focusSearch(focused, direction)
        val c = i % columns
        val n = childCount
        return when (direction) {
            FOCUS_LEFT -> if (c > 0) getChildAt(i - 1) else focused
            FOCUS_RIGHT -> if (c < columns - 1 && i + 1 < n) getChildAt(i + 1) else focused
            FOCUS_DOWN -> if ((i / columns + 1) * columns < n) getChildAt(minOf(i + columns, n - 1)) else focused
            FOCUS_UP -> if (i >= columns) getChildAt(i - columns) else {
                if (onExitUp?.invoke() == true) null else super.focusSearch(focused, direction)
            }
            else -> super.focusSearch(focused, direction)
        }
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            lastFocused = indexOfChild(child)
            onFocusIndex?.invoke(lastFocused, child)
        }
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (childCount == 0) return false
        return getChildAt(lastFocused.coerceIn(0, childCount - 1)).requestFocus()
    }

    fun focusIndex(i: Int) {
        if (childCount == 0) return
        lastFocused = i.coerceIn(0, childCount - 1)
        getChildAt(lastFocused).requestFocus()
    }
}
