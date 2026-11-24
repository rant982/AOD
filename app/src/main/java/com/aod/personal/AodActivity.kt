package com.aod.personal

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

object AodState {
    var activity: WeakReference<AodActivity>? = null
    val showing get() = activity?.get() != null
    fun finish() {
        activity?.get()?.finish()
    }
}

class AodActivity : ComponentActivity(), SensorEventListener {
    private var covered by mutableStateOf(false)
    private var near = false
    private var down = false
    private lateinit var sm: SensorManager

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
            screenBrightness = Prefs.brightness
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        AodState.activity = WeakReference(this)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        setContent { AodScreen(covered = covered, onWake = { finish() }) }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.hideCovered) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    override fun onDestroy() {
        if (AodState.activity?.get() === this) AodState.activity = null
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PROXIMITY -> near = e.values[0] < minOf(5f, e.sensor.maximumRange)
            Sensor.TYPE_ACCELEROMETER -> down = e.values[2] < -7f
        }
        covered = near || down
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
