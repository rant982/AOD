package com.aod.personal

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small on-device event log (no notification text) so odd behaviour can be diagnosed afterwards. */
object AodLog {
    private var file: File? = null

    private var started = false

    fun init(c: Context) {
        if (file == null) file = File(c.applicationContext.filesDir, "aod-events.log")
        if (!started) {
            started = true
            add("process started pid=${android.os.Process.myPid()}")
            // Record any crash before the app dies.
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                add("CRASH in ${t.name}: $e " + e.stackTrace.take(8).joinToString(" | ") { it.toString() })
                previous?.uncaughtException(t, e)
            }
            logPreviousExits(c.applicationContext)
        }
    }

    private fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED (killed)"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR (not responding)"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER (phone froze the app)"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "reason $r"
    }

    /** Android keeps a record of why the app's previous process ended: log any we haven't logged yet. */
    private fun logPreviousExits(c: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val lp = c.getSharedPreferences("aodlog", Context.MODE_PRIVATE)
            val last = lp.getLong("lastExit", 0L)
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            val fresh = am.getHistoricalProcessExitReasons(c.packageName, 0, 6).filter { it.timestamp > last }.sortedBy { it.timestamp }
            for (e in fresh) {
                add("PREVIOUS PROCESS ENDED at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timestamp))}: ${reasonName(e.reason)} importance=${e.importance} ${e.description.orEmpty()}")
            }
            fresh.maxOfOrNull { it.timestamp }?.let { lp.edit().putLong("lastExit", it).apply() }
        } catch (e: Exception) {
            add("could not read exit reasons: ${e.message}")
        }
    }

    @Synchronized
    fun add(msg: String) {
        val f = file ?: return
        try {
            f.appendText(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + msg + "\n")
            if (f.length() > 60_000) f.writeText(f.readLines().takeLast(300).joinToString("\n") + "\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun read(): String = try {
        file?.readLines()?.takeLast(300)?.joinToString("\n") ?: ""
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear() {
        try {
            file?.writeText("")
        } catch (e: Exception) {
        }
    }
}
