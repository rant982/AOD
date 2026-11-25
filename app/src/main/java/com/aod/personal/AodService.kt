package com.aod.personal

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
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
 * When the screen turns off (power button or timeout): lock the phone, then show the AOD over the lock screen.
 * Safety rule: the AOD is only ever shown while Android reports the device as PIN/pattern/password-locked.
 */
class AodService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile var running = false
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val kmx = getSystemService(KeyguardManager::class.java)
            val state = "showing=${AodState.showing} deviceLocked=${kmx.isDeviceLocked} keyguard=${kmx.isKeyguardLocked}"
            if (i.action == Intent.ACTION_SCREEN_ON) {
                AodLog.add("SCREEN_ON $state")
                return
            }
            if (i.action != Intent.ACTION_SCREEN_OFF) return
            AodLog.add("SCREEN_OFF $state")
            if (System.currentTimeMillis() < AodState.suppressRelaunchUntil) {
                // The user was on the way into the phone (lock screen timed out first): don't throw the AOD back up.
                AodState.suppressRelaunchUntil = 0
                AodState.wantHomeUntil = 0
                AodState.pendingOpen = null
                AodLog.add("SCREEN_OFF: the user was heading into the phone, so the AOD was not relaunched")
                return
            }
            AodState.pendingOpen = null // never open a stale message after the screen has gone off
            if (System.currentTimeMillis() - AodState.lastLaunch < 1500) return
            val missing = Requirements.missingForService(this@AodService)
            if (missing.isNotEmpty()) {
                AodState.log("Screen off: AOD NOT shown. Setup incomplete (${missing.joinToString { it.title }}).")
                return
            }
            if (AodState.showing && Prefs.powerOff) {
                AodState.finish() // power button = fully off
                lockPhone(this@AodService)
                return
            }
            AodState.finish()
            handler.removeCallbacksAndMessages(null)
            handler.post {
                if (!getSystemService(KeyguardManager::class.java).isDeviceLocked) lockPhone(this@AodService)
                handler.postDelayed({ launch(true) }, 250)
            }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            AodLog.add("USER_PRESENT (phone unlocked)")
            val now = System.currentTimeMillis()
            val n = AodState.pendingOpen
            AodState.pendingOpen = null
            AodState.suppressRelaunchUntil = 0
            if (n != null) {
                // Triple-tapped an icon or its text: take the user to that app.
                if (now - AodState.pendingAt < 60_000) openNotificationNow(this@AodService, n)
            } else if (now < AodState.wantHomeUntil) {
                // Triple-tapped the clock: take the user to the home screen, never back into the AOD.
                AodState.wantHomeUntil = 0
                AodLog.add("Going to the home screen")
                goHome(this@AodService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        running = true
        AodLog.add("service created")
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("aod", "AOD running", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("aod_alert", "AOD can't run", NotificationManager.IMPORTANCE_DEFAULT))
        val missing = Requirements.missingForService(this)
        if (missing.isNotEmpty()) {
            // Refuse to run half-configured: say so, switch off, stop.
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val alert = NotificationCompat.Builder(this, "aod_alert")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("My AOD can't run")
                .setContentText("Missing: " + missing.joinToString { it.title })
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            ServiceCompat.startForeground(
                this, 1, alert,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            AodState.log("Not started. Missing: " + missing.joinToString { it.title })
            stopSelf()
            return START_NOT_STICKY
        }
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
        val why = when (intent?.getStringExtra("reason")) {
            "boot" -> "after restart"
            "update" -> "after app update"
            "keeper" -> "restarted by watchdog"
            else -> "from the app"
        }
        Prefs.lastStart = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) + " ($why)"
        AodLog.add("service started $why (${if (intent == null) "system restart, no intent" else "explicit start"})")
        WatchdogReceiver.schedule(this)
        return START_STICKY
    }

    private fun launch(retry: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            AodState.log("AOD NOT shown: no 'Draw over other apps' permission.")
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceLocked) {
            // Never show the AOD over an unlocked phone. Try once more, then give up (screen stays off).
            if (retry) {
                lockPhone(this)
                handler.postDelayed({ launch(false) }, 400)
            } else {
                AodState.log("AOD NOT shown: Android says the phone isn't locked yet (set 'Automatically lock' to Immediately or enable the lock helper; or Smart Lock is keeping it open). Screen left off.")
            }
            return
        }
        AodState.lastLaunch = System.currentTimeMillis()
        AodState.log("Screen off: phone locked, AOD shown.")
        startActivity(
            Intent(this, AodActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    override fun onDestroy() {
        running = false
        AodLog.add("service destroyed")
        unregisterReceiver(screenReceiver)
        unregisterReceiver(unlockReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
