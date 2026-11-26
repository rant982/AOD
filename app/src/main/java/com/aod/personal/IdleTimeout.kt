package com.aod.personal

import android.content.Context
import android.provider.Settings

/**
 * "Switch to the AOD after N minutes idle": the AOD takes over when the phone's screen timeout ends, so this sets the
 * system screen timeout to N minutes while the AOD is running, and puts the original value back when it stops.
 * Needs the "Modify system settings" permission, which the user grants once.
 */
object IdleTimeout {
    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    /** Apply the wanted timeout (running = the AOD service is on), or restore the original one. */
    fun apply(ctx: Context, running: Boolean) {
        try {
            if (!canWrite(ctx)) return
            val cr = ctx.contentResolver
            if (running && Prefs.idleMinutes > 0) {
                if (Prefs.origTimeout < 0) Prefs.origTimeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Prefs.idleMinutes * 60_000)
            } else if (Prefs.origTimeout >= 0) {
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Prefs.origTimeout)
                Prefs.origTimeout = -1
            }
        } catch (e: Exception) {
            AodLog.add("screen timeout change failed: ${e.message}")
        }
    }
}
