package com.aod.personal

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small on-device event log (no notification text) so odd behaviour can be diagnosed afterwards. */
object AodLog {
    private var file: File? = null

    fun init(c: Context) {
        if (file == null) file = File(c.applicationContext.filesDir, "aod-events.log")
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
