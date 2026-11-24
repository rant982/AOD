package com.aod.personal

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Does one thing: performs the normal "lock screen" action (same as the power button).
 * Unlike DevicePolicyManager.lockNow(), this does NOT force a PIN/password, so face and fingerprint keep working.
 * It reads nothing from the screen.
 */
class LockHelperService : AccessibilityService() {
    companion object {
        @Volatile var instance: LockHelperService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

fun lockPhone(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean =
    LockHelperService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false
