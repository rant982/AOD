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
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
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

    /** Last call/alarm/full-screen notification: never fight those for the screen. */
    @Volatile var lastFullScreenAt = 0L
    val relaunchTimes = mutableListOf<Long>()

    /** Plain-English result of the most recent screen-off, shown in the self-test. */
    @Volatile var lastEvent = "none yet"

    fun log(msg: String) {
        lastEvent = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) + "  " + msg
        AodLog.add(msg)
    }

    /** Last pixel-shift move, for the status card. */
    @Volatile var shiftNote = "not moved yet"

    /** True while the PIN/biometric prompt is up, so the lock watchdog stays out of the way. */
    @Volatile var unlocking = false

    /** A message to open once the user unlocks (set by a triple-tap on its text). */
    @Volatile var pendingOpen: NotifItem? = null
    @Volatile var pendingAt = 0L

    fun finish() {
        activity?.get()?.finish()
    }

    /**
     * Brings the AOD back to the front if it was pushed aside or closed by the system while the phone is still
     * locked and awake. Never during calls, alarms or other full-screen notifications, and never more than
     * 6 times in 30 seconds.
     */
    fun maybeRelaunch(ctx: Context, why: String) {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val awake = ctx.getSystemService(PowerManager::class.java).isInteractive
        val idle = ctx.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL
        val now = System.currentTimeMillis()
        if (!km.isKeyguardLocked || !awake || !idle) return
        if (now - lastFullScreenAt < 120_000 || now - lastRelaunch < 1500) return
        relaunchTimes.removeAll { now - it > 30_000 }
        if (relaunchTimes.size >= 6) return
        relaunchTimes.add(now)
        lastRelaunch = now
        log("AOD $why; brought it back.")
        ctx.startActivity(
            Intent(ctx, AodActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
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
     * If anything pushes the AOD aside while the phone is still locked and awake (a Home or edge swipe, the system
     * lock screen coming forward after a notification), bring it back. Leaving is only by triple-tapping the clock.
     * Never during calls, alarms or other full-screen notifications, and never more than 6 times in 30 seconds.
     */
    private val relaunch = Runnable {
        if (isFinishing || isDestroyed || preview) return@Runnable
        AodState.maybeRelaunch(this, "was pushed aside (Home/edge swipe or system lock screen)")
    }

    /** True once our own code asked the AOD to close (clock triple-tap, notification open, power button, ...). */
    private var selfFinished = false

    private var notedUnlocked = false
    private var notedGone = false

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
            notedGone = false
            return
        }
        if (km.isKeyguardLocked) {
            notedGone = false
            if (!notedUnlocked) {
                notedUnlocked = true
                AodState.log("Phone unlocked (face or trust) while the AOD was showing; AOD left in place.")
            }
            return
        }
        // Lock screen gone altogether (face/trust unlocked straight to the home screen). Never throw the user
        // onto the home screen from here. Leaving the AOD now asks for face, fingerprint or PIN first.
        if (!notedGone) {
            notedGone = true
            AodState.log("Lock screen was gone (unlocked straight to home) while the AOD showed; AOD kept in front. Leaving it now asks for face, fingerprint or PIN.")
        }
    }

    private fun updateCovered() {
        val c = Prefs.hideCovered && ((hasProx && !proxSeen) || near || down)
        if (c != covered) AodLog.add("covered=$c near=$near faceDown=$down proxSeen=$proxSeen")
        covered = c
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
        AodLog.add("AOD created preview=$preview")
        // A Back swipe (or an edge-gesture app) must never close the AOD. Only triple-tapping the clock does.
        onBackPressedDispatcher.addCallback(this) { AodLog.add("Back gesture ignored") }
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
        setContent { AodScreen(covered = covered, onWake = { AodLog.add("Triple-tap on the clock"); unlock(this) }) }
    }

    override fun onResume() {
        super.onResume()
        AodState.unlocking = false
        AodLog.add("AOD resumed deviceLocked=${km.isDeviceLocked} keyguard=${km.isKeyguardLocked}")
        handler.removeCallbacks(relaunch)
        if (!preview) handler.post(guard)
        if (Prefs.hideCovered) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    override fun onPause() {
        super.onPause()
        AodLog.add("AOD paused finishing=$isFinishing deviceLocked=${km.isDeviceLocked} keyguard=${km.isKeyguardLocked}")
        handler.removeCallbacks(guard)
        if (!isFinishing) handler.postDelayed(relaunch, 400)
        sm.unregisterListener(this)
    }

    override fun finish() {
        selfFinished = true
        AodLog.add("AOD finish() called from: " + Throwable().stackTrace.drop(1).take(5).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName })
        super.finish()
    }

    override fun onUserLeaveHint() {
        AodLog.add("User left the AOD (Home or recents gesture)")
        super.onUserLeaveHint()
    }

    private var tDown = 0L
    private var x0 = 0f
    private var y0 = 0f

    /** Logs each touch as one line (distance, duration, and whether it began at a screen edge). */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tDown = e.eventTime
                x0 = e.rawX
                y0 = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dm = resources.displayMetrics
                val m = 24 * dm.density
                val edge = when {
                    x0 < m -> "left-edge"
                    x0 > dm.widthPixels - m -> "right-edge"
                    y0 < m * 2 -> "top-edge"
                    y0 > dm.heightPixels - m * 2 -> "bottom-edge"
                    else -> ""
                }
                AodLog.add("touch ${if (e.actionMasked == MotionEvent.ACTION_UP) "up" else "cancel"} dx=${(e.rawX - x0).toInt()} dy=${(e.rawY - y0).toInt()} ${e.eventTime - tDown}ms $edge")
            }
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        AodLog.add("AOD top-resumed=$isTopResumedActivity")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AodLog.add("AOD trimMemory level=$level")
    }

    override fun onStop() {        AodLog.add("AOD stopped finishing=$isFinishing")
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AodLog.add("AOD window focus=$hasFocus")
    }

    override fun onDestroy() {
        AodLog.add("AOD destroyed finishing=$isFinishing selfFinished=$selfFinished")
        // Closed by the system although nobody here asked: put it back (e.g. it was closed by a swipe or the lock screen).
        if (isFinishing && !selfFinished && !preview) {
            val app = applicationContext
            Handler(Looper.getMainLooper()).postDelayed({ AodState.maybeRelaunch(app, "was closed by the system (nobody asked it to close)") }, 300)
        }
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
