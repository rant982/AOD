package com.aod.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
    private var lastLaunch = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_SCREEN_OFF) return
            if (System.currentTimeMillis() - lastLaunch < 1500) return
            if (AodState.showing && Prefs.powerOff) {
                AodState.finish() // power button = fully off
                return
            }
            AodState.finish()
            handler.removeCallbacksAndMessages(null)
            handler.post { lockAndLaunch() }
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

    /** Lock the phone first (needs device admin), then show the AOD over the lock screen. */
    private fun lockAndLaunch() {
        if (Prefs.lockOnAod) {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
                try {
                    dpm.lockNow()
                } catch (e: SecurityException) {
                }
            }
        }
        handler.postDelayed({ launch() }, 250)
    }

    private fun launch() {
        if (!Settings.canDrawOverlays(this)) return
        lastLaunch = System.currentTimeMillis()
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
