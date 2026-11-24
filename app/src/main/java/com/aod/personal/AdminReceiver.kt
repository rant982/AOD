package com.aod.personal

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/** Only used so the app may call lockNow(). It has no other device-admin policies. */
class AdminReceiver : DeviceAdminReceiver()

fun lockPhone(ctx: Context) {
    val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    if (dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))) {
        try {
            dpm.lockNow()
        } catch (e: SecurityException) {
        }
    }
}
