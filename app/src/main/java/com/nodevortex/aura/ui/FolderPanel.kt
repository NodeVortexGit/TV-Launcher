package com.nodevortex.aura.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.nodevortex.aura.Aura
import com.nodevortex.aura.HomeActivity
import com.nodevortex.aura.data.HomeItem
import com.nodevortex.aura.util.C
import com.nodevortex.aura.util.MATCH
import com.nodevortex.aura.util.Motion
import com.nodevortex.aura.util.Spring
import com.nodevortex.aura.util.WRAP
import com.nodevortex.aura.util.dp
import com.nodevortex.aura.util.dpf
import com.nodevortex.aura.util.flp
import com.nodevortex.aura.util.label
import com.nodevortex.aura.util.lp
import com.nodevortex.aura.util.margins

/** A folder opened from the home grid: frosted card that springs open with its own grid. */
class FolderPanel(host: PanelHost, private val act: HomeActivity, private val folder: HomeItem.Folder) : Panel(host) {
    override val homeAlpha = 0.12f
    private val grid = HomeGrid(ctx)
    private val title = ctx.label(30f, 420, C.text, text = folder.name)
    private val count = ctx.label(15f, 450, C.textDim)
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 36.dpf; setColor(0xEB161A21.toInt()); setStroke(1.dp, 0x1AFFFFFF) }
        setPadding(44.dp, 34.dp, 44.dp, 40.dp)
        clipChildren = false; clipToPadding = false
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.BOTTOM
            addView(title); addView(count, lp(WRAP, WRAP).margins(l = 14.dp, b = 5.dp))
        })
    }
    private val scroll = SpringScrollV(ctx).apply { marginTop = 24.dp; marginBottom = 30.dp }
    override val view: View = FrameLayout(ctx).apply {
        clipChildren = false
        addView(card, flp(WRAP, WRAP, Gravity.CENTER))
    }
    private val open = Spring(0f, 340f, 0.82f, 0.001f) { p ->
        card.scaleX = 0.86f + 0.14f * p; card.scaleY = card.scaleX; card.alpha = p.coerceIn(0f, 1f)
    }

    init {
        val cols = 5
        val cell = 104.dp
        grid.columns = cols; grid.cell = cell; grid.gapX = 26.dp; grid.gapY = 14.dp
        grid.setPadding(0, 10.dp, 0, 10.dp)
        grid.onFocusIndex = { _, v -> (v as TileView).let { act.ambient.setColor(it.accent) } }
        scroll.addView(grid)
        card.addView(scroll, lp(cols * cell + (cols - 1) * 26.dp, WRAP).margins(t = 22.dp))
        build()
    }

    private fun build() {
        val repo = Aura.app.apps
        grid.removeAllViews()
        for (key in folder.apps) {
            val e = repo[key] ?: continue
            val t = TileView(ctx, 104.dp).apply { label = e.label; tag = key }
            repo.icon(e, 104.dp) { b -> t.bitmap = b; t.accent = e.accent }
            t.setOnClickListener { dismiss(); act.launch(e, t) }
            t.setOnLongClickListener { act.appMenu(e, t, folder); true }
            grid.addView(t)
        }
        count.text = "${folder.apps.size} приложения"
        val rows = (grid.childCount + 4) / 5
        scroll.layoutParams?.height = if (rows > 3) (3 * (104 + 30 + 14)).dp else WRAP
    }

    override fun animateIn() { open.snapTo(0f); open.animateTo(1f) }
    override fun animateOut(end: () -> Unit) {
        card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(160).setInterpolator(Motion.accel).withEndAction(end).start()
    }
    override fun focusFirst() { grid.focusIndex(0) }
    override fun onClosed() { act.rebuildGrid(folder.id) }
}

@Suppress("unused") private val keepMatch = MATCH
