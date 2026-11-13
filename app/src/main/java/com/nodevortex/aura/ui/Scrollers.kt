package com.nodevortex.aura.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.dp
import kotlin.math.roundToInt

/**
 * Vertical container that keeps the focused descendant visible by spring-scrolling.
 * Unlike ScrollView it never jumps: every focus change retargets one continuous spring.
 */
open class SpringScrollV(ctx: Context) : FrameLayout(ctx) {
    /** false = fade content directly instead of through a full-screen offscreen buffer (much cheaper on the TV's GPU). */
    var overlapping = true
    override fun hasOverlappingRendering() = overlapping
    var marginTop = 28.dp
    var marginBottom = 56.dp
    /** If > 0 the focused item is pinned at this offset from the top (Google TV "focus line"). */
    var anchor = -1
    private val tmp = Rect()
    private val spring = Spring(0f, 300f, 1f, 0.5f) { scrollTo(0, it.roundToInt()) }

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(36.dp)
        clipToPadding = false
        isFocusable = false
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        super.onMeasure(wSpec, hSpec)
        val c = getChildAt(0) ?: return
        c.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = getChildAt(0) ?: return
        c.layout(paddingLeft, paddingTop, paddingLeft + c.measuredWidth, paddingTop + c.measuredHeight)
        val max = maxScroll()
        if (spring.target > max) spring.animateTo(max.toFloat())
    }

    private fun maxScroll(): Int {
        val c = getChildAt(0) ?: return 0
        return (c.measuredHeight + paddingTop + paddingBottom - height).coerceAtLeast(0)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused != null) reveal(focused)
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean) = false

    fun reveal(v: View, immediate: Boolean = false) {
        tmp.set(0, 0, v.width, v.height)
        offsetDescendantRectToMyCoords(v, tmp)
        // offsetDescendantRectToMyCoords already includes our current scroll; convert to content coords
        val top = tmp.top; val bottom = tmp.bottom
        val cur = spring.target
        var target = cur
        if (anchor >= 0) {
            target = (top - anchor).toFloat()
        } else {
            if (top - marginTop < cur) target = (top - marginTop).toFloat()
            else if (bottom + marginBottom > cur + height) target = (bottom + marginBottom - height).toFloat()
        }
        target = target.coerceIn(0f, maxScroll().toFloat())
        if (immediate) spring.snapTo(target) else spring.animateTo(target)
    }

    fun scrollToTop(immediate: Boolean = false) = if (immediate) spring.snapTo(0f) else spring.animateTo(0f)

    override fun getTopFadingEdgeStrength() = if (scrollY > 0) (scrollY / 36.dp.toFloat()).coerceAtMost(1f) else 0f
    override fun getBottomFadingEdgeStrength(): Float {
        val c = getChildAt(0) ?: return 0f
        val rem = c.bottom + paddingBottom - (scrollY + height)
        return (rem / 36.dp.toFloat()).coerceIn(0f, 1f)
    }
}

/** Horizontal twin used for rows of cards (search results, For you, …). */
open class SpringScrollH(ctx: Context) : FrameLayout(ctx) {
    var margin = 48.dp
    private val tmp = Rect()
    private val spring = Spring(0f, 320f, 1f, 0.5f) { scrollTo(it.roundToInt(), 0) }

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(40.dp)
        clipToPadding = false
        clipChildren = false
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val c = getChildAt(0)
        if (c == null) { super.onMeasure(wSpec, hSpec); return }
        c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), getChildMeasureSpec(hSpec, paddingTop + paddingBottom, c.layoutParams.height))
        val h = c.measuredHeight + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(h, hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = getChildAt(0) ?: return
        c.layout(paddingLeft, paddingTop, paddingLeft + c.measuredWidth, paddingTop + c.measuredHeight)
    }

    private fun maxScroll(): Int {
        val c = getChildAt(0) ?: return 0
        return (c.measuredWidth + paddingLeft + paddingRight - width).coerceAtLeast(0)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused == null) return
        tmp.set(0, 0, focused.width, focused.height)
        offsetDescendantRectToMyCoords(focused, tmp)
        val cur = spring.target
        var target = cur
        if (tmp.left - margin < cur) target = (tmp.left - margin).toFloat()
        else if (tmp.right + margin > cur + width) target = (tmp.right + margin - width).toFloat()
        spring.animateTo(target.coerceIn(0f, maxScroll().toFloat()))
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean) = false

    fun reset() = spring.snapTo(0f)

    override fun getLeftFadingEdgeStrength() = if (scrollX > 0) (scrollX / 40.dp.toFloat()).coerceAtMost(1f) else 0f
    override fun getRightFadingEdgeStrength(): Float {
        val c = getChildAt(0) ?: return 0f
        return ((c.right + paddingRight - (scrollX + width)) / 40.dp.toFloat()).coerceIn(0f, 1f)
    }
}

/** A LinearLayout row whose children wrap focus horizontally only (no accidental jumps). */
fun ViewGroup.childIndexOf(v: View): Int {
    var cur: View? = v
    while (cur != null && cur.parent !== this) cur = cur.parent as? View
    return if (cur == null) -1 else indexOfChild(cur)
}
