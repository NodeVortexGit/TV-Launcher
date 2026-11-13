package com.nodevortex.aura.system

import android.util.Log
import com.nodevortex.aura.Aura

/**
 * Casting needs this Chromecast's factory credentials, which the cast service (mediashell) reads
 * once per start from a provider inside Google's setup app (setupwraith). That app has to stay
 * disabled (its recovery screen claims Home), so while it is off the cast service fails and retries
 * every 60 s ("Cannot get device credentials"). After a load succeeds it keeps them in memory.
 *
 * So: read the cast service's last failure from the log (through the system connection) and switch
 * the setup app on for a few seconds around its next retry, then off again.
 */
object CastCreds {
    private const val TAG = "VeloCast"
    private const val SETUP = StockLauncher.SETUP
    @Volatile var busy = false
        private set
    private var lastCheck = 0L

    /** Cheap when everything is fine; rate-limited. Call after boot / on screen-on. */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastCheck < 15 * 60_000)) return
        lastCheck = now
        val b = Aura.app.bridge
        b.shell("logcat -d -v epoch -s cast_ClientAuthCredsFactoryProvisioned:E | grep 'Cannot get device credentials' | tail -1; echo NOW \$(date +%s)") { out ->
            if (out == null) return@shell
            val nowS = Regex("NOW (\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return@shell
            val last = Regex("^\\s*(\\d+)\\.", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toLongOrNull()
            if (last == null || nowS - last > 75) { Log.i(TAG, "cast credentials fine"); return@shell }
            // Retries come every 60 s: be on from ~3 s before the next one until ~3 s after.
            var wait = (last + 60 - 3) - nowS
            while (wait < 0) wait += 60
            Log.i(TAG, "cast service lacks credentials; setup app on in ${wait}s for 6s")
            busy = true
            Aura.main.postDelayed({
                b.shell("pm enable --user 0 $SETUP >/dev/null; sleep 6; pm disable-user --user 0 $SETUP >/dev/null; echo ok", 20_000) { r ->
                    busy = false
                    if (r == null) b.shell("pm disable-user --user 0 $SETUP") // never leave it on
                    Log.i(TAG, "window done: ${r?.trim()}")
                    // Make sure it took (the next check runs the window again if not).
                    Aura.main.postDelayed({ check(force = true) }, 70_000)
                }
            }, wait * 1000)
        }
    }
}
