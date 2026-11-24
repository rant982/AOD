package com.aod.personal

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.media.AudioManager
import android.os.PowerManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

object AodState {
    var activity: WeakReference<AodActivity>? = null
    val showing get() = activity?.get() != null

    @Volatile var lastLaunch = 0L

    /** When an ordinary (non-call, non-alarm) notification last arrived, and when we last brought the AOD back. */
    @Volatile var lastNotifAt = 0L
    @Volatile var lastRelaunch = 0L

    /** Plain-English result of the most recent screen-off, shown in the self-test. */
    @Volatile var lastEvent = "none yet"

    fun log(msg: String) {
        lastEvent = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) + "  " + msg
    }

    /** True while the PIN/biometric prompt is up, so the lock watchdog stays out of the way. */
    @Volatile var unlocking = false

    /** A message to open once the user unlocks (set by a triple-tap on its text). */
    @Volatile var pendingOpen: NotifItem? = null
    @Volatile var pendingAt = 0L

    fun finish() {
        activity?.get()?.finish()
    }
}

class AodActivity : ComponentActivity(), SensorEventListener {
    // Starts true: black and untouchable until the proximity sensor confirms the screen is uncovered.
    private var covered by mutableStateOf(true)
    private var near = false
    private var down = false
    private var proxSeen = false
    private var hasProx = false
    private lateinit var sm: SensorManager
    private lateinit var km: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())
    private val preview by lazy { intent.getBooleanExtra("preview", false) }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = applyBrightness()
    }

    /** Watchdog: if the phone is ever found unlocked while the AOD is up, lock it again. */
    private val guard = object : Runnable {
        override fun run() {
            ensureLocked()
            handler.postDelayed(this, 2000)
        }
    }

    /**
     * If a new notification makes the phone push its own lock screen over the AOD, bring the AOD back.
     * Only for ordinary notifications while the phone is locked and awake, never during a call or alarm.
     */
    private val relaunch = Runnable {
        if (isFinishing || isDestroyed || preview) return@Runnable
        val now = System.currentTimeMillis()
        val awake = getSystemService(PowerManager::class.java).isInteractive
        val idle = getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL
        if (!km.isKeyguardLocked || !awake || !idle) return@Runnable
        if (now - AodState.lastNotifAt > 10_000 || now - AodState.lastRelaunch < 2000) return@Runnable
        AodState.lastRelaunch = now
        AodState.log("A notification pushed the AOD aside; brought it back.")
        startActivity(
            Intent(this, AodActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    private var notedUnlocked = false

    /**
     * Safety check, every 2 seconds.
     * - Phone locked: fine.
     * - Unlocked by face or fingerprint but the lock screen is still showing (open padlock): leave the AOD alone.
     * - Lock screen gone altogether: the AOD would be covering an open phone, so close it.
     */
    private fun ensureLocked() {
        if (AodState.unlocking || preview) return
        if (km.isDeviceLocked) {
            notedUnlocked = false
            return
        }
        if (km.isKeyguardLocked) {
            if (!notedUnlocked) {
                notedUnlocked = true
                AodState.log("Phone unlocked (face or trust) while the AOD was showing; AOD left in place.")
            }
            return
        }
        AodState.log("AOD closed: the lock screen was gone (phone already unlocked).")
        finish()
    }

    private fun updateCovered() {
        covered = Prefs.hideCovered && ((hasProx && !proxSeen) || near || down)
    }

    private fun isDnd(): Boolean {
        val f = getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Day: follow the system brightness (auto or manual). Do Not Disturb: night level. */
    private fun applyBrightness() {
        val lp = window.attributes
        lp.screenBrightness = if (Prefs.nightDim && isDnd()) Prefs.nightBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyBrightness()
        ContextCompat.registerReceiver(
            this, dndReceiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        AodState.activity = WeakReference(this)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        km = getSystemService(KeyguardManager::class.java)
        hasProx = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        updateCovered()
        setContent { AodScreen(covered = covered, onWake = { unlock(this) }) }
    }

    override fun onResume() {
        super.onResume()
        AodState.unlocking = false
        handler.removeCallbacks(relaunch)
        if (!preview) handler.post(guard)
        if (Prefs.hideCovered) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(guard)
        if (!isFinishing) handler.postDelayed(relaunch, 400)
        sm.unregisterListener(this)
    }

    override fun onDestroy() {
        unregisterReceiver(dndReceiver)
        handler.removeCallbacksAndMessages(null)
        if (AodState.activity?.get() === this) AodState.activity = null
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                near = e.values[0] < minOf(5f, e.sensor.maximumRange)
                proxSeen = true
            }
            Sensor.TYPE_ACCELEROMETER -> down = e.values[2] < -7f
        }
        updateCovered()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
