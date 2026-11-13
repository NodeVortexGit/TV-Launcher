package com.nodevortex.aura.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.nodevortex.aura.Aura

/**
 * Google TV's launcher declares Home priority 2 (Google's setup screen 1); Android always picks the
 * highest priority before looking at the user's default, and normal apps are capped at 0. So Aura
 * becomes the real default home by keeping those two disabled. Google's launcher is switched on only
 * for the few seconds a Google TV profile switch needs.
 */
object StockLauncher {
    const val LX = "com.google.android.apps.tv.launcherx"
    const val SETUP = "com.google.android.tungsten.setupwraith"

    fun disabled(ctx: Context): Boolean = try {
        val st = ctx.packageManager.getApplicationEnabledSetting(LX)
        st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Throwable) { false }

    /** True when Android itself resolves Home to Aura. */
    fun auraIsDefault(ctx: Context): Boolean =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == ctx.packageName

    val wanted get() = Aura.prefs.getBoolean("realHome", false)

    fun enable(svc: AuraService, cb: (Boolean) -> Unit) {
        val b = Aura.app.bridge
        // Always try the system connection first (it reconnects by itself); Settings is the fallback.
        if (b.ready || Aura.prefs.getBoolean("bridgeTrusted", false)) {
            b.shell("pm enable $LX") { out ->
                if (out != null) Aura.main.postDelayed({ cb(!disabled(svc)) }, 300) else enableViaSettings(svc, cb)
            }
            return
        }
        enableViaSettings(svc, cb)
    }

    /** No system connection: open Google TV's app page in Settings and press "Активиране" ourselves. */
    private fun enableViaSettings(svc: AuraService, cb: (Boolean) -> Unit) {
        try {
            svc.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$LX")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { cb(false); return }
        var tries = 0
        val words = listOf("Активиране", "Активирай", "Enable", "Включване")
        lateinit var poll: Runnable
        poll = Runnable {
            if (!disabled(svc)) { svc.back(); Aura.main.postDelayed({ cb(true) }, 500); return@Runnable }
            if (tries++ > 24) { cb(false); return@Runnable }
            svc.clickText(words)
            Aura.main.postDelayed(poll, 350)
        }
        Aura.main.postDelayed(poll, 900)
    }

    /** Needs the shell bridge; otherwise it is remembered and done next time the bridge is up. */
    fun disable(ctx: Context) {
        if (!wanted) return
        val b = Aura.app.bridge
        if (b.ready) { b.shell("pm disable-user --user 0 $LX"); Aura.prefs.edit().putBoolean("stockPending", false).apply(); return }
        Aura.prefs.edit().putBoolean("stockPending", true).apply()
        // Try the bridge again (it can bring up wireless debugging by itself); meanwhile keep Google's
        // launcher from sitting in RAM.
        b.shell("pm disable-user --user 0 $LX") { out -> if (out != null) Aura.prefs.edit().putBoolean("stockPending", false).apply() }
        killStock(ctx)
    }

    /** Called on start-up / when Google's home shows up: switch it off again if we can, else stop it. */
    fun enforce(ctx: Context) {
        if (!wanted || disabled(ctx)) return
        val b = Aura.app.bridge
        b.shell("pm disable-user --user 0 $LX") { out ->
            if (out != null) Aura.prefs.edit().putBoolean("stockPending", false).apply() else killStock(ctx)
        }
    }

    fun killStock(ctx: Context) {
        try { ctx.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(LX) } catch (_: Throwable) { }
    }
}
