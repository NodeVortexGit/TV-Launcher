package com.nodevortex.aura.system

import android.content.Context
import android.provider.Settings
import com.nodevortex.aura.Aura

/**
 * The "pop" heard on every D-pad move is Android's own UI sound (Effect_Tick.ogg), played by the
 * system for every app while Settings.System.SOUND_EFFECTS_ENABLED is on. VeloCinema switches it
 * with its WRITE_SECURE_SETTINGS grant, or through the system connection as a fallback.
 */
object NavSound {
    fun systemOn(ctx: Context): Boolean =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1

    fun setSystem(ctx: Context, on: Boolean, cb: (Boolean) -> Unit) {
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0)
            cb(systemOn(ctx) == on)
        } catch (_: SecurityException) {
            Aura.app.bridge.shell("settings put system sound_effects_enabled ${if (on) 1 else 0}") { cb(systemOn(ctx) == on) }
        }
    }
}
