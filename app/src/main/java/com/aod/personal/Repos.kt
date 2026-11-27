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

data class NotifItem(val key: String, val pkg: String, val app: String, val title: String, val text: String, val icon: Bitmap?, val intent: PendingIntent?, val color: Int = 0, val appIcon: Bitmap? = null, val full: String = "", val reply: android.app.Notification.Action? = null, val suggestions: List<String> = emptyList())
data class MediaInfo(val title: String, val artist: String)
data class Weather(val tempC: Float, val code: Int, val feelsC: Float? = null, val hiC: Float? = null, val loC: Float? = null)
data class CalEvent(val title: String, val begin: Long, val end: Long, val id: Long = 0)

object NotifRepo {
    val items = MutableStateFlow<List<NotifItem>>(emptyList())
    val media = MutableStateFlow<MediaInfo?>(null)
    /** Emits the package name of each genuinely new notification (for edge lighting and the icon flash). */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 4)
}

object CalendarRepo {
    private fun key(id: Long, begin: Long) = "$id|$begin"

    /** Hides this appointment from the AOD (it is not deleted from the calendar). */
    fun dismiss(e: CalEvent) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        Prefs.dismissedEvents = Prefs.dismissedEvents.filter { (it.substringAfter('|').toLongOrNull() ?: 0L) > cutoff }.toSet() + key(e.id, e.begin)
    }

    /** Opens the appointment itself in the calendar app. */
    fun viewIntent(e: CalEvent): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)

    fun next(ctx: Context): CalEvent? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + 24 * 3600_000L)
        return try {
            ctx.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID),
                null, null, "begin ASC"
            )?.use { c ->
                var result: CalEvent? = null
                val dismissed = Prefs.dismissedEvents
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue
                    val end = c.getLong(2)
                    if (end <= now) continue
                    if (key(c.getLong(4), c.getLong(1)) in dismissed) continue
                    result = CalEvent(c.getString(0) ?: "(no title)", c.getLong(1), end, c.getLong(4))
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
    fun load(ctx: Context, force: Boolean = false): Weather? {
        val age = System.currentTimeMillis() - Prefs.wTime
        if (!force && Prefs.wCode >= 0 && age < 30 * 60_000L) return cached()
        val (lat, lon) = location(ctx) ?: return cached()
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,apparent_temperature" +
                    "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cur = root.getJSONObject("current")
            Prefs.wTemp = cur.getDouble("temperature_2m").toFloat()
            Prefs.wCode = cur.getInt("weather_code")
            Prefs.wFeels = if (cur.has("apparent_temperature")) cur.getDouble("apparent_temperature").toFloat() else -999f
            val daily = root.optJSONObject("daily")
            Prefs.wHi = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.toFloat() ?: -999f
            Prefs.wLo = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.toFloat() ?: -999f
            Prefs.wTime = System.currentTimeMillis()
            cached()
        } catch (e: Exception) {
            cached()
        }
    }

    private fun cached(): Weather? {
        if (Prefs.wCode < 0) return null
        fun v(x: Float) = if (x <= -900f || x.isNaN()) null else x
        return Weather(Prefs.wTemp, Prefs.wCode, v(Prefs.wFeels), v(Prefs.wHi), v(Prefs.wLo))
    }

    fun description(code: Int) = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light showers"
        81 -> "showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unsettled"
    }

    /** style 0: "3°C" (temperature only); 1: "☁️ 3°C and cloudy"; 2: adds feels-like and today's high and low. */
    fun format(w: Weather, fahrenheit: Boolean, style: Int): String {
        fun t(c: Float) = if (fahrenheit) "${Math.round(c * 9f / 5f + 32)}°F" else "${Math.round(c)}°C"
        if (style == 0) return t(w.tempC)
        val base = "${emoji(w.code)} ${t(w.tempC)}"
        return buildString {
            append("$base and ${description(w.code)}")
            if (style >= 2) {
                w.feelsC?.let { append(", feels like ${t(it)}") }
                if (w.hiC != null && w.loC != null) append(" · High ${t(w.hiC)}, low ${t(w.loC)}")
            }
        }
    }

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
