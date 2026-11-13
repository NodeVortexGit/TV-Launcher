package com.nodevortex.aura.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.nodevortex.aura.Aura

/** Device-admin receiver: only used for `lockNow()`, which puts the Chromecast to sleep. */
class SleepAdmin : DeviceAdminReceiver()

object SleepTimer {
    private const val KEY = "sleepAt"
    val endsAt: Long get() = Aura.prefs.getLong(KEY, 0L).let { if (it < System.currentTimeMillis()) 0L else it }

    fun set(ctx: Context, minutes: Int) {
        cancel(ctx)
        if (minutes <= 0) return
        val at = System.currentTimeMillis() + minutes * 60_000L
        Aura.prefs.edit().putLong(KEY, at).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 30_000L, pending(ctx))
    }

    fun cancel(ctx: Context) {
        Aura.prefs.edit().remove(KEY).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun adminActive(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(ctx, SleepAdmin::class.java))

    /** Put the TV to sleep using whatever power we have. */
    fun sleepNow(ctx: Context) {
        Aura.prefs.edit().remove(KEY).apply()
        val wait = NavSound.shutdown()
        if (wait > 0) Aura.main.postDelayed({ goDark(ctx) }, wait) else goDark(ctx)
    }

    private fun goDark(ctx: Context) {
        if (adminActive(ctx)) {
            try { ctx.getSystemService(DevicePolicyManager::class.java).lockNow(); return } catch (_: Throwable) { }
        }
        val b = Aura.app.bridge
        if (b.ready) { b.shell("input keyevent KEYCODE_SLEEP"); return }
        try {
            ctx.startActivity(Intent("com.google.android.pano.action.SLEEP").setPackage("com.android.tv.settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { }
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = AuraService.instance
        if (svc != null) svc.sleepCountdown() else SleepTimer.sleepNow(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        com.nodevortex.aura.tv.EpgJob.schedule(ctx)
        Aura.app.bridge.probe()
    }
}
