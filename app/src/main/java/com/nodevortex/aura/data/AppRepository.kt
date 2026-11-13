package com.nodevortex.aura.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import android.util.LruCache
import com.nodevortex.aura.Aura
import com.nodevortex.aura.util.ColorKit
import java.util.concurrent.CopyOnWriteArrayList

class AppEntry(
    val key: String,          // "pkg/cls"
    val pkg: String,
    val cls: String,
    val label: String,
    val isTv: Boolean,
    val isSystem: Boolean,
) {
    @Volatile var accent: Int = 0
    val component get() = ComponentName(pkg, cls)
}

class AppRepository(private val ctx: Context) {

    @Volatile var apps: List<AppEntry> = emptyList()
        private set
    private val byKey = HashMap<String, AppEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val icons = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val prefs = Aura.prefs

    init {
        val la = ctx.getSystemService(LauncherApps::class.java)
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = changed(p)
            override fun onPackageAdded(p: String, u: UserHandle) = changed(p)
            override fun onPackageChanged(p: String, u: UserHandle) = changed(p)
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = changed(null)
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = changed(null)
        }, Aura.main)
    }

    private fun changed(pkg: String?) {
        if (pkg != null) synchronized(icons) {
            icons.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { icons.remove(it) }
        }
        reload()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    operator fun get(key: String) = synchronized(byKey) { byKey[key] }
    fun byPackage(pkg: String) = apps.firstOrNull { it.pkg == pkg }

    fun reload() {
        Aura.io.execute {
            val list = query()
            synchronized(byKey) {
                byKey.clear(); list.forEach { byKey[it.key] = it }
            }
            apps = list
            Aura.ui { listeners.forEach { it() } }
        }
    }

    private fun query(): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        fun add(list: List<ResolveInfo>, tv: Boolean) {
            for (ri in list) {
                val ai = ri.activityInfo
                if (ai.packageName == ctx.packageName || seen.containsKey(ai.packageName)) continue
                if (ai.packageName in HIDDEN_PACKAGES) continue
                val sys = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }
                seen[ai.packageName] = AppEntry("${ai.packageName}/${ai.name}", ai.packageName, ai.name, label, tv, sys)
            }
        }
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), true)
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), false)
        return seen.values.toList()
    }

    // ---------------------------------------------------------------- icons

    /** Square tile bitmap at [size] px, rendered off the UI thread and cached. */
    fun icon(e: AppEntry, size: Int, cb: (Bitmap) -> Unit) {
        val k = "${e.key}@$size"
        icons.get(k)?.let { cb(it); return }
        synchronized(pending) {
            pending[k]?.let { it.add(cb); return }
            pending[k] = mutableListOf(cb)
        }
        Aura.io.execute {
            val bmp = loadDisk(e, size) ?: (try { render(e, size).also { saveDisk(e, size, it) } } catch (t: Throwable) { fallback(e, size) })
            icons.put(k, bmp)
            val cbs = synchronized(pending) { pending.remove(k) } ?: return@execute
            Aura.ui { cbs.forEach { it(bmp) } }
        }
    }

    fun cachedIcon(e: AppEntry, size: Int): Bitmap? = icons.get("${e.key}@$size")

    // ---------------------------------------------------------------- disk cache
    // Rendering an icon (high-density drawable → square tile + accent colour) takes tens of ms;
    // the launcher gets killed often on a 1.4 GB TV, so cold starts read the finished tiles from disk.
    private val diskDir by lazy { java.io.File(ctx.cacheDir, "icons").apply { mkdirs() } }
    private val accents by lazy { ctx.getSharedPreferences("icon_accents", Context.MODE_PRIVATE) }

    private fun diskName(e: AppEntry, size: Int): String? = try {
        val pi = ctx.packageManager.getPackageInfo(e.pkg, 0)
        "${e.pkg}_${e.cls.hashCode().toUInt()}_${pi.longVersionCode}_${pi.lastUpdateTime}_$size.png"
    } catch (_: Throwable) { null }

    private fun loadDisk(e: AppEntry, size: Int): Bitmap? {
        val name = diskName(e, size) ?: return null
        val f = java.io.File(diskDir, name)
        if (!f.exists() || !accents.contains(name)) return null
        val b = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return null
        e.accent = accents.getInt(name, e.accent)
        return b
    }

    private fun saveDisk(e: AppEntry, size: Int, b: Bitmap) {
        val name = diskName(e, size) ?: return
        try {
            java.io.File(diskDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            accents.edit().putInt(name, e.accent).apply()
            diskDir.listFiles()?.let { all ->
                // drop stale versions of this app's tile and keep the cache bounded
                all.filter { it.name.startsWith("${e.pkg}_") && it.name.endsWith("_$size.png") && it.name != name }.forEach { it.delete(); accents.edit().remove(it.name).apply() }
                if (all.size > 400) all.sortedBy { it.lastModified() }.take(all.size - 300).forEach { it.delete() }
            }
        } catch (_: Throwable) { }
    }

    /** Called on memory pressure: tiles can be re-read from disk cheaply. */
    fun trimIcons(hard: Boolean) { if (hard) icons.evictAll() else icons.trimToSize(icons.maxSize() / 2) }

    private fun loadDrawable(e: AppEntry): Drawable? {
        val pm = ctx.packageManager
        return try {
            val ai = pm.getActivityInfo(e.component, 0)
            val res = pm.getResourcesForApplication(ai.applicationInfo)
            val id = ai.iconResource
            (if (id != 0) res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) else null)
                ?: ai.loadIcon(pm)
        } catch (t: Throwable) {
            try { pm.getApplicationIcon(e.pkg) } catch (_: Throwable) { null }
        }
    }

    private fun render(e: AppEntry, size: Int): Bitmap {
        val d = loadDrawable(e) ?: return fallback(e, size)
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(content)
        if (d is AdaptiveIconDrawable) {
            // Draw both layers full-bleed (no system mask) – a gentle zoom keeps the logo prominent.
            val pad = (size * 0.22f).toInt()
            d.background?.let { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(c) }
            d.foreground?.let { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(c) }
            e.accent = ColorKit.accentOf(content)
        } else {
            val src = drawableToBitmap(d, size)
            e.accent = ColorKit.accentOf(src)
            if (isFullBleed(src)) {
                c.drawBitmap(src, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                // Legacy/round icon: place it on a soft gradient of its own colour.
                val top = ColorKit.ambient(e.accent, 0.40f, 0.10f)
                val bottom = ColorKit.ambient(e.accent, 0.26f, 0.08f)
                val bgPaint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
                c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)
                val inset = size * 0.16f
                c.drawBitmap(src, null, RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        }
        return rounded(content, size)
    }

    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size); d.draw(c)
        return b
    }

    private fun isFullBleed(b: Bitmap): Boolean {
        if (b.width < 8 || b.height < 8) return false
        val w = b.width - 1; val h = b.height - 1
        val pts = listOf(2 to 2, w - 2 to 2, 2 to h - 2, w - 2 to h - 2, w / 2 to 1, 1 to h / 2)
        return pts.all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 230 }
    }

    private fun rounded(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size * TILE_RADIUS
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, p)
        return out
    }

    private fun fallback(e: AppEntry, size: Int): Bitmap {
        e.accent = 0xFF5B6B8C.toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E3442.toInt() }
        val r = size * TILE_RADIUS
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, p)
        p.color = Color.WHITE; p.textSize = size * 0.42f; p.textAlign = Paint.Align.CENTER
        c.drawText(e.label.take(1).uppercase(), size / 2f, size * 0.64f, p)
        return b
    }

    // ---------------------------------------------------------------- launching & usage

    fun launchIntent(e: AppEntry): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (e.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(e.component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun noteLaunch(e: AppEntry) {
        val (count, _) = usage(e.key)
        prefs.edit().putString("use:${e.key}", "${count + 1},${System.currentTimeMillis()}").apply()
        Signals.noteLaunchHour(e.pkg)
    }

    fun usage(key: String): Pair<Int, Long> {
        val s = prefs.getString("use:$key", null) ?: return 0 to 0L
        val p = s.split(',')
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun recent(limit: Int): List<AppEntry> = apps
        .map { it to usage(it.key).second }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(limit).map { it.first }

    companion object {
        const val TILE_RADIUS = 0.2f
        /** Packages that are not useful as home tiles on this Chromecast. */
        val HIDDEN_PACKAGES = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith",
            "com.android.tv.settings",
            "com.spocky.projengmenu",
        )
        val me: UserHandle get() = Process.myUserHandle()
    }
}
