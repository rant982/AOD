package com.aod.personal

import android.content.Context
import android.provider.Settings

/**
 * At night (Do Not Disturb on) the phone's own brightness is set to its minimum while the AOD is up, so that if the phone
 * ever flashes its lock screen between AOD runs, the flash is dim. The previous mode and level are put back as soon as the
 * AOD is left on purpose or Do Not Disturb ends. Needs the "Modify system settings" permission.
 */
object NightBrightness {
    fun apply(ctx: Context, night: Boolean) {
        try {
            if (!Prefs.nightSystem || !IdleTimeout.canWrite(ctx)) {
                if (!night || !Prefs.nightSystem) restore(ctx)
                return
            }
            if (night) {
                val cr = ctx.contentResolver
                if (Prefs.nbMode < 0) { // not already dimmed: remember what the phone had
                    Prefs.nbMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Prefs.nbValue = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                    AodLog.add("system brightness dimmed for the night (was mode ${Prefs.nbMode}, level ${Prefs.nbValue})")
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 1)
            } else restore(ctx)
        } catch (e: Exception) {
            AodLog.add("night brightness change failed: ${e.message}")
        }
    }

    fun restore(ctx: Context) {
        try {
            if (Prefs.nbMode < 0 || !IdleTimeout.canWrite(ctx)) return
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Prefs.nbValue)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Prefs.nbMode)
            AodLog.add("system brightness restored (mode ${Prefs.nbMode}, level ${Prefs.nbValue})")
            Prefs.nbMode = -1
            Prefs.nbValue = -1
        } catch (e: Exception) {
            AodLog.add("night brightness restore failed: ${e.message}")
        }
    }
}
