package com.aod.personal

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Safety net, about every 10 minutes (even in Doze): make sure the service is running, and if the screen is off with
 * no AOD showing (for example overnight, after the phone killed the app), bring the AOD back.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        if (!Prefs.enabled) return
        val pm = c.getSystemService(PowerManager::class.java)
        val km = c.getSystemService(KeyguardManager::class.java)
        AodLog.add("watchdog: service=${AodService.running} aodShowing=${AodState.showing} screenOn=${pm.isInteractive} locked=${km.isDeviceLocked}")

        if (!AodService.running) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, AodService::class.java).putExtra("reason", "keeper"))
            } catch (e: Exception) {
                AodLog.add("watchdog could not start the service: ${e.message}")
            }
        }

        if (!Prefs.powerOff && !pm.isInteractive && !AodState.showing && System.currentTimeMillis() >= AodState.suppressRelaunchUntil && Settings.canDrawOverlays(c)) {
            val pending = goAsync()
            if (!km.isDeviceLocked) lockPhone(c)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!pm.isInteractive && !AodState.showing && km.isDeviceLocked) {
                        AodState.log("Watchdog: the screen was off with no AOD; brought the AOD back.")
                        c.startActivity(
                            Intent(c, AodActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        )
                    }
                } finally {
                    pending.finish()
                }
            }, 500)
        }
        schedule(c)
    }

    companion object {
        fun schedule(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                c, 77, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60_000L, pi)
        }
    }
}
