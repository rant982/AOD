package com.aod.personal

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.location.LocationManager
import android.provider.CalendarContract
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class NotifItem(val key: String, val pkg: String, val app: String, val title: String, val text: String, val icon: Bitmap?, val intent: PendingIntent?)
data class MediaInfo(val title: String, val artist: String)
data class Weather(val tempC: Float, val code: Int)
data class CalEvent(val title: String, val begin: Long, val end: Long)

object NotifRepo {
    val items = MutableStateFlow<List<NotifItem>>(emptyList())
    val media = MutableStateFlow<MediaInfo?>(null)
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
}

object CalendarRepo {
    fun next(ctx: Context): CalEvent? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + 24 * 3600_000L)
        return try {
            ctx.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, "begin ASC"
            )?.use { c ->
                var result: CalEvent? = null
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue
                    val end = c.getLong(2)
                    if (end <= now) continue
                    result = CalEvent(c.getString(0) ?: "(no title)", c.getLong(1), end)
                    break
                }
                result
            }
        } catch (e: Exception) {
            null
        }
    }
}

object WeatherRepo {
    /** Returns cached weather if fresh, otherwise fetches from Open-Meteo. Call off the main thread. */
    fun load(ctx: Context): Weather? {
        val age = System.currentTimeMillis() - Prefs.wTime
        if (Prefs.wCode >= 0 && age < 30 * 60_000L) return Weather(Prefs.wTemp, Prefs.wCode)
        val (lat, lon) = location(ctx) ?: return cached()
        return try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            Prefs.wTemp = cur.getDouble("temperature_2m").toFloat()
            Prefs.wCode = cur.getInt("weather_code")
            Prefs.wTime = System.currentTimeMillis()
            Weather(Prefs.wTemp, Prefs.wCode)
        } catch (e: Exception) {
            cached()
        }
    }

    private fun cached() = if (Prefs.wCode >= 0) Weather(Prefs.wTemp, Prefs.wCode) else null

    private fun location(ctx: Context): Pair<Double, Double>? {
        val la = Prefs.lat.trim().toDoubleOrNull()
        val lo = Prefs.lon.trim().toDoubleOrNull()
        if (la != null && lo != null) return la to lo
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
        } catch (e: SecurityException) {
            null
        }
    }

    fun emoji(code: Int) = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
