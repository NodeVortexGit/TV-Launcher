package com.nodevortex.aura.system

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.nodevortex.aura.Aura
import com.nodevortex.aura.R

/**
 * Navigation sounds. The "pop" heard on every D-pad move is Android's own UI sound
 * (Effect_Tick.ogg), played by the system for every app while Settings.System.SOUND_EFFECTS_ENABLED
 * is on. VeloCinema can switch it (WRITE_SECURE_SETTINGS, or the system connection as a fallback)
 * and play its own move / confirm / back sounds instead.
 *
 * Modes: VELO = VeloCinema's sounds (system pop off), GOOGLE = system pop, OFF = silence.
 * VeloCinema's sounds only play while the system pop is off, so the two never double up.
 */
object NavSound {
    const val VELO = 0
    const val GOOGLE = 1
    const val OFF = 2
    /** Playback level of the sounds (1 = as recorded). */
    private const val VOLUME = 0.7f

    private var pool: SoundPool? = null
    private var sMove = 0; private var sConfirm = 0; private var sBack = 0
    @Volatile private var sysOn = true
    private var lastDpad = 0L

    fun init(ctx: Context) {
        val cr = ctx.contentResolver
        sysOn = readSystem(ctx)
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SOUND_EFFECTS_ENABLED), false,
            object : ContentObserver(Aura.main) { override fun onChange(self: Boolean) { sysOn = readSystem(ctx) } })
        if (!Aura.prefs.contains("navSound")) {
            // First run with the new sounds: VeloCinema's replace Google's pop.
            Aura.prefs.edit().putInt("navSound", VELO).apply()
            if (sysOn) setSystem(ctx, false) { }
        }
        Aura.io.execute {
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            sMove = p.load(ctx, R.raw.sfx_move, 1)
            sConfirm = p.load(ctx, R.raw.sfx_confirm, 1)
            sBack = p.load(ctx, R.raw.sfx_back, 1)
            pool = p
        }
    }

    // ------------------------------------------------------------------------------- mode

    /** What is actually heard right now (the system pop wins whenever it is on). */
    fun mode(): Int = if (sysOn) GOOGLE else Aura.prefs.getInt("navSound", VELO).let { if (it == GOOGLE) OFF else it }

    fun setMode(ctx: Context, m: Int, cb: (Boolean) -> Unit) {
        Aura.prefs.edit().putInt("navSound", m).apply()
        setSystem(ctx, m == GOOGLE) { ok -> if (ok && m == VELO) confirm(); cb(ok) }
    }

    fun systemOn(ctx: Context): Boolean = readSystem(ctx)

    private fun readSystem(ctx: Context) =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1

    private fun setSystem(ctx: Context, on: Boolean, cb: (Boolean) -> Unit) {
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0)
            sysOn = readSystem(ctx); cb(sysOn == on)
        } catch (_: SecurityException) {
            Aura.app.bridge.shell("settings put system sound_effects_enabled ${if (on) 1 else 0}") { sysOn = readSystem(ctx); cb(sysOn == on) }
        }
    }

    // ------------------------------------------------------------------------------- playing

    /** A move the caller sounds itself: ignore the focus hops it causes. */
    fun manualMove() { lastDpad = 0L }

    fun move() = play(sMove)
    fun confirm() = play(sConfirm)
    fun back() = play(sBack)

    private fun play(id: Int) {
        if (id == 0 || sysOn || Aura.prefs.getInt("navSound", VELO) != VELO) return
        pool?.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    /**
     * Hook for an activity's window: a move sound whenever the remote moves focus, confirm on OK
     * over something clickable, back on Back. Call [onKey] first thing in dispatchKeyEvent.
     */
    fun attach(act: Activity) {
        act.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            // Only focus moves made by the remote (not ones the launcher makes by itself).
            if (old != null && new != null && SystemClock.uptimeMillis() - lastDpad < 300) move()
        }
    }

    fun onKey(act: Activity, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> lastDpad = SystemClock.uptimeMillis()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0 && act.currentFocus?.isClickable == true) confirm()
            KeyEvent.KEYCODE_BACK -> if (e.repeatCount == 0) back()
        }
    }
}
