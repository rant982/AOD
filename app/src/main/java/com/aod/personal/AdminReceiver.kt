package com.aod.personal

import android.app.admin.DeviceAdminReceiver

/** Only used so the app may call lockNow(). It has no other device-admin policies. */
class AdminReceiver : DeviceAdminReceiver()
