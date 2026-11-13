package com.nodevortex.aura

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.nodevortex.aura.bridge.AdbBridge
import com.nodevortex.aura.data.AppRepository
import com.nodevortex.aura.data.HomeLayout
import com.nodevortex.aura.tv.Channels
import com.nodevortex.aura.tv.Epg
import com.nodevortex.aura.tv.EpgJob
import com.nodevortex.aura.util.Fonts
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Process-wide singletons. Kept deliberately small: this runs on a 1.4 GB Chromecast. */
class Aura : Application() {

    lateinit var apps: AppRepository
    lateinit var layout: HomeLayout
    lateinit var channels: Channels
    lateinit var epg: Epg
    lateinit var bridge: AdbBridge

    override fun onCreate() {
        super.onCreate()
        app = this
        prefs = getSharedPreferences("aura", MODE_PRIVATE)
        Fonts.init()
        com.nodevortex.aura.system.NavSound.init(this)
        apps = AppRepository(this)
        layout = HomeLayout(prefs)
        channels = Channels(this)
        epg = Epg(this)
        bridge = AdbBridge(this)
        EpgJob.schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Launcher in the background and the TV short on RAM: give memory back (all of it is rebuildable).
        when {
            level >= TRIM_MEMORY_COMPLETE -> { apps.trimIcons(hard = true); epg.trim() }
            level >= TRIM_MEMORY_MODERATE -> { apps.trimIcons(hard = true) }
            level >= TRIM_MEMORY_BACKGROUND -> apps.trimIcons(hard = false)
            level == TRIM_MEMORY_RUNNING_CRITICAL -> epg.trim()
        }
    }

    companion object {
        lateinit var app: Aura
            private set
        lateinit var prefs: SharedPreferences
            private set
        val main = Handler(Looper.getMainLooper())
        /** General background work (icon rendering, provider queries). */
        val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "aura-io").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        /** Network work (EPG download, speed test, Wikidata) so it never blocks icon loading. */
        val net: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "aura-net") }

        fun ui(r: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) r() else main.post(r)
        }
    }
}
