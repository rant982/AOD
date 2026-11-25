package com.aod.personal

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        AodLog.init(c)
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("aod", Context.MODE_PRIVATE)
    }

    private class B(val k: String, val d: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = sp.edit().putBoolean(k, value).apply()
    }
    private class I(val k: String, val d: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = sp.edit().putInt(k, value).apply()
    }
    private class F(val k: String, val d: Float) : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) = sp.edit().putFloat(k, value).apply()
    }
    private class L(val k: String, val d: Long) : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = sp.edit().putLong(k, value).apply()
    }
    private class S(val k: String, val d: String) : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = sp.edit().putString(k, value).apply()
    }

    val ALL_BLOCKS = listOf("LOGO", "CLOCK", "DATE", "BATTERY", "WEATHER", "CALENDAR", "MUSIC", "NOTIFS")

    /** Top-to-bottom order of everything on the AOD. Unknown entries are dropped and missing ones appended. */
    var order by S("order", ALL_BLOCKS.joinToString(","))

    fun orderList(): List<String> {
        val saved = order.split(",").filter { it in ALL_BLOCKS }.distinct()
        return saved + ALL_BLOCKS.filter { it !in saved }
    }

    var enabled by B("enabled", false)
    var lastStart by S("lastStart", "")

    // Appearance
    var clockStyle by I("clockStyle", 0)
    var font by I("font", 0)
    var use24h by B("use24h", true)
    var hue by F("hue", 0f)
    var sat by F("sat", 0f)
    var clockScale by F("clockScale", 1f)
    var nightDim by B("nightDim", true)
    var nightBrightness by F("nightBrightness", 0.03f)
    var logoMode by I("logoMode", 0) // 0 none, 1 image, 2 text
    var logoText by S("logoText", "")

    // Info lines
    var showDate by B("showDate", true)
    var showBattery by B("showBattery", true)
    var showWeather by B("showWeather", true)
    var fahrenheit by B("fahrenheit", false)
    var lat by S("lat", "")
    var lon by S("lon", "")
    var showCalendar by B("showCalendar", true)
    var showMusic by B("showMusic", true)

    // Notifications
    var showNotifs by B("showNotifs", true)
    var iconStyle by I("iconStyle", 0) // 0 mono, 1 brand colour, 2 app icon
    var showPreview by B("alwaysPreview", false)
    var edgeLight by B("edgeLight", true)
    var flashIcon by B("flashIcon", true)
    var blocked: Set<String>
        get() = sp.getStringSet("blocked", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("blocked", v).apply()

    /** Notifications hidden for good, stored as "package|title". */
    var muted: Set<String>
        get() = sp.getStringSet("muted", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("muted", v).apply()

    // Colours: ARGB, 0 = automatic (follows the main colour)
    var clockColor by I("clockColor", 0)
    var clockMinColor by I("clockMinColor", 0)
    var logoColor by I("logoColor", 0)
    var infoColor by I("infoColor", 0)

    // 0 short, 1 with description, 2 full (feels like, high and low)
    var weatherStyle by I("weatherStyle", 1)
    var wFeels by F("wFeels", -999f)
    var wHi by F("wHi", -999f)
    var wLo by F("wLo", -999f)

    var blockGap by F("blockGap", 8f) // dp between items on the AOD
    var shiftAmount by I("shiftAmount", 1) // 0 small, 1 medium, 2 large

    // Behaviour
    var pixelShift by B("pixelShift", true)
    var powerOff by B("powerOff", false)
    var hideCovered by B("hideCovered", true)

    // Weather cache
    var wTemp by F("wTemp", 0f)
    var wCode by I("wCode", -1)
    var wTime by L("wTime", 0L)
}
