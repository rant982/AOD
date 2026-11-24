package com.aod.personal

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
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

    /** True while the PIN/biometric prompt is up, so the lock watchdog stays out of the way. */
    @Volatile var unlocking = false

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

    private fun ensureLocked() {
        if (AodState.unlocking || km.isDeviceLocked) return
        AodState.lastLaunch = 0 // let the service relaunch us after the lock
        lockPhone(this)
        handler.postDelayed({ if (!AodState.unlocking && !km.isDeviceLocked) finish() }, 800)
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
        handler.post(guard)
        if (Prefs.hideCovered) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(guard)
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
