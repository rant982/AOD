package com.aod.personal

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** level: 0 = required (the app will not run without it), 1 = recommended, 2 = optional feature. */
class Check(
    val title: String,
    val why: String,
    val ok: Boolean,
    val level: Int,
    val intent: Intent? = null,
    val permission: String? = null
) {
    val required get() = level == 0
}

object Requirements {
    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun checks(ctx: Context): List<Check> {
        val l = mutableListOf<Check>()

        // Required
        l += Check(
            "Notification access", "Lets the AOD read your notifications.",
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName), 0,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        )
        l += Check(
            "Draw over other apps", "Lets the AOD appear when the screen turns off.",
            Settings.canDrawOverlays(ctx), 0,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        )
        val helper = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${ctx.packageName}/") == true
        val lockAfter = try {
            Settings.Secure.getLong(ctx.contentResolver, "lock_screen_lock_after_timeout", 5000L)
        } catch (e: Exception) {
            5000L
        }
        l += Check(
            "Phone locks as soon as the screen turns off",
            "Do ONE of these: set Settings > Security > Screen lock > 'Automatically lock' to Immediately, or turn on the My AOD lock helper (Accessibility). Neither stops face or fingerprint unlock.",
            helper || lockAfter <= 1000L, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        l += Check(
            "Screen lock (PIN, pattern or password)", "Without one, locking the phone protects nothing.",
            ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure, 0,
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        )

        // Recommended
        l += Check(
            "Notifications allowed", "Shows the small 'AOD is running' notification that keeps the service alive.",
            Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS), 1,
            permission = Manifest.permission.POST_NOTIFICATIONS
        )
        l += Check(
            "Unrestricted battery", "Stops Android from killing the AOD in the background.",
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName), 1,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        )
        l += Check(
            "Proximity sensor", "Detects pocket or face-down so the screen goes black and ignores touches. Your phone has none if this fails.",
            ctx.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_PROXIMITY) != null, 1
        )

        // Optional features
        if (Prefs.showCalendar) l += Check(
            "Calendar access", "For the next-event line. Turn that line off in Info lines if you don't want it.",
            granted(ctx, Manifest.permission.READ_CALENDAR), 2, permission = Manifest.permission.READ_CALENDAR
        )
        if (Prefs.showWeather) l += Check(
            "Location (weather)", "Or enter coordinates under Info lines.",
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ||
                (Prefs.lat.toDoubleOrNull() != null && Prefs.lon.toDoubleOrNull() != null),
            2, permission = Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return l
    }

    fun missingRequired(ctx: Context) = checks(ctx).filter { it.required && !it.ok }
}
