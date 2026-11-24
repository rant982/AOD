package com.aod.personal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        if (Prefs.enabled) ContextCompat.startForegroundService(c, Intent(c, AodService::class.java))
    }
}
