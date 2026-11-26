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
    val permission: String? = null,
    val fixLabel: String = "Fix",
    val extraIntent: Intent? = null,
    val extraLabel: String? = null,
    /** false: still shown as required in the app, but never stops the background service starting. */
    val blocksService: Boolean = true,
    /** Set for steps Android can't detect: the user confirms them by hand ("I've done this"). */
    val manualKey: String? = null,
    /** Tried in order when the user taps the fix button (phone-maker settings pages differ between models). */
    val intents: List<Intent> = emptyList()
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
            "Do ONE of these (neither stops face or fingerprint unlock):\n" +
                "1) Tap 'Lock helper', scroll down to 'My AOD Lock Helper' under Downloaded services and switch it on. If the switch is greyed out: Settings > Apps > My AOD > the three dots > Allow restricted settings, then try again.\n" +
                "2) Or tap 'Auto-lock setting' and set 'Automatically lock' to Immediately.",
            helper || lockAfter <= 1000L, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            fixLabel = "Lock helper",
            extraIntent = Intent(Settings.ACTION_SECURITY_SETTINGS),
            extraLabel = "Auto-lock setting",
            blocksService = false
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
        // Battery: the one thing Android lets us check (Android's own setting, and background restriction).
        val restricted = try {
            ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        } catch (e: Exception) {
            false
        }
        l += Check(
            "Battery: no restrictions", "Stops Android from limiting or killing the AOD in the background.",
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) && !restricted, 0,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
            blocksService = false
        )

        // Phone-maker settings (Vivo, Oppo, Xiaomi, Samsung...) can still stop the app. Android can't see them, so the user confirms.
        val maker = Build.MANUFACTURER.lowercase()
        val vivo = maker.contains("vivo") || maker.contains("iqoo")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        fun comp(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
        val confirmed = Prefs.confirmedSteps
        l += Check(
            "Phone's own battery manager: allow high background power",
            if (vivo) "Vivo's battery manager can still stop the app. Settings > Battery > Background power consumption management > My AOD > Allow high background power consumption. Then tap I've done this."
            else "Your phone maker's battery manager can still stop the app. Allow unrestricted or high background power for My AOD. Then tap I've done this.",
            "bgpower" in confirmed, 0, null, null, "Open settings", null, null, false, "bgpower",
            if (vivo) listOf(
                comp("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                comp("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
                details
            ) else listOf(details)
        )
        l += Check(
            "Autostart: allow My AOD to start by itself",
            if (vivo) "Settings > Apps > Autostart (or iManager > App manager > Autostart) > turn on My AOD. Then tap I've done this."
            else "Allow My AOD to start by itself / run in the background in your phone's app settings. Then tap I've done this.",
            "autostart" in confirmed, 0, null, null, "Open settings", null, null, false, "autostart",
            if (vivo) listOf(
                comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                details
            ) else listOf(details)
        )
        l += Check(
            "Locked in Recents",
            "Open the Recents screen, then press and hold (or pull down on) the My AOD card and choose Lock. This stops the phone clearing it. Then tap I've done this.",
            "recentslock" in confirmed, 0, null, null, "Fix", null, null, false, "recentslock"
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

    /** What actually stops the service starting. The lock check is only a warning here: the AOD itself refuses to show over an unlocked phone. */
    fun missingForService(ctx: Context) = checks(ctx).filter { it.required && !it.ok && it.blocksService }
}
