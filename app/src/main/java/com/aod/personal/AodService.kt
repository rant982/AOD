package com.aod.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Watches for the screen turning off and launches the AOD screen.
 * If the AOD is already showing when the screen turns off, that was the power button: close it and stay off.
 */
class AodService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_SCREEN_OFF) return
            if (AodState.showing) {
                AodState.finish()
            } else {
                handler.postDelayed({ launch() }, 300)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("aod", "AOD running", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, "aod")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Always-on display is active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        return START_STICKY
    }

    private fun launch() {
        if (AodState.showing || !Settings.canDrawOverlays(this)) return
        startActivity(
            Intent(this, AodActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
