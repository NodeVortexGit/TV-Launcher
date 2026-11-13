package com.nodevortex.aura.system

import android.util.Base64
import android.util.Log
import com.nodevortex.aura.Aura

/**
 * Casting needs this Chromecast's factory credentials, which the cast service (mediashell) reads
 * from a provider inside Google's setup app (setupwraith). That app has to stay disabled (its
 * recovery screen claims Home), so after every boot the cast service fails and keeps retrying –
 * with growing gaps (1, 2, 4 … 32 s, then every 60 s) – and YouTube / screen casting don't work.
 * Once a load succeeds it keeps the credentials in memory.
 *
 * Fix (assets/castfix.sh, through the system connection): switch the setup app on, wait until the
 * log shows the credentials loaded (≤ 90 s), switch it off. During that window VeloCinema still owns
 * the Home button, and Google's recovery screen is sent home if it ever shows.
 */
object CastCreds {
    private const val TAG = "VeloCast"
    @Volatile var busy = false
        private set
    private var lastCheck = 0L

    /** Cheap when everything is fine; rate-limited unless [force]. Call after boot / on screen-on. */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastCheck < 15 * 60_000)) return
        lastCheck = now
        val b = Aura.app.bridge
        // Failing = a "Cannot get device credentials" within the last 2 minutes (it repeats at least every 60 s).
        b.shell("logcat -d -v epoch -s cast_ClientAuthCredsFactoryProvisioned:E | grep 'Cannot get device credentials' | tail -1; echo NOW \$(date +%s)") { out ->
            if (out == null) return@shell
            val nowS = Regex("NOW (\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return@shell
            val last = Regex("^\\s*(\\d+)\\.", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toLongOrNull()
            if (last == null || nowS - last > 120) { Log.i(TAG, "cast credentials fine"); return@shell }
            fix()
        }
    }

    private fun fix() {
        val script = try { Aura.app.assets.open("castfix.sh").use { it.readBytes() } } catch (_: Throwable) { return }
        val b64 = Base64.encodeToString(script, Base64.NO_WRAP)
        busy = true
        Log.i(TAG, "cast service lacks its credentials: lending it Google's setup app")
        Aura.app.bridge.shell("echo $b64 | base64 -d | sh", 120_000) { r ->
            busy = false
            Log.i(TAG, "castfix: ${r?.trim()}")
            if (r == null) Aura.app.bridge.shell("pm disable-user --user 0 ${StockLauncher.SETUP}") // never leave it on
            // Not loaded (or connection lost): look again in a couple of minutes.
            if (r == null || !r.contains("loaded")) Aura.main.postDelayed({ check(force = true) }, 120_000)
        }
    }
}
