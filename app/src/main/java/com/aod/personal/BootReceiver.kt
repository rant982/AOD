package com.aod.personal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the AOD service after a phone restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        if (!Prefs.enabled) return
        val reason = if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) "update" else "boot"
        try {
            ContextCompat.startForegroundService(c, Intent(c, AodService::class.java).putExtra("reason", reason))
        } catch (e: Exception) {
        }
    }
}
