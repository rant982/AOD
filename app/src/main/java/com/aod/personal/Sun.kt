package com.aod.personal

import android.content.Context
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset for where the phone is, worked out on the phone (no internet needed). */
object Sun {
    private var lastRefresh = 0L

    /** The phone's last known position (saved, so it still works with no signal), refreshed at most every 30 minutes. */
    fun location(ctx: Context): Pair<Double, Double>? {
        val now = System.currentTimeMillis()
        if (Prefs.sunLat.isEmpty() || now - lastRefresh > 30 * 60_000L) {
            lastRefresh = now
            WeatherRepo.location(ctx)?.let {
                Prefs.sunLat = it.first.toString()
                Prefs.sunLon = it.second.toString()
            }
        }
        val la = Prefs.sunLat.toDoubleOrNull()
        val lo = Prefs.sunLon.toDoubleOrNull()
        return if (la != null && lo != null) la to lo else null
    }

    class Day(val rise: Long?, val set: Long?, val polarNight: Boolean, val midnightSun: Boolean)

    /** Sunrise and sunset of the solar day that [nowMs] falls in (the one whose midday is closest). */
    fun day(lat: Double, lon: Double, nowMs: Long): Day {
        val jd = nowMs / 86_400_000.0 + 2440587.5
        val base = ceil(jd - 2451545.0 - 0.0009 + 69.184 / 86400.0).toLong()
        var best: Day? = null
        var bestGap = Double.MAX_VALUE
        for (n in base - 1..base + 1) {
            val r = one(lat, lon, n.toDouble())
            val transit = r.third
            val gap = abs(transit - jd)
            if (gap < bestGap) {
                bestGap = gap
                best = r.first
            }
        }
        return best!!
    }

    private fun rad(d: Double) = d * PI / 180.0

    /** NOAA "sunrise equation" for Julian cycle n. Returns (day, unused, solar-noon Julian date). */
    private fun one(lat: Double, lon: Double, n: Double): Triple<Day, Unit, Double> {
        val jStar = n + 0.0009 - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360.0
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180.0 + 102.9372) % 360.0
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val sinDec = sin(rad(lambda)) * sin(rad(23.4397))
        val cosDec = cos(Math.asin(sinDec))
        val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sinDec) / (cos(rad(lat)) * cosDec)
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).toLong()
        val day = when {
            cosW > 1.0 -> Day(null, null, polarNight = true, midnightSun = false)
            cosW < -1.0 -> Day(null, null, polarNight = false, midnightSun = true)
            else -> {
                val w = Math.toDegrees(acos(cosW))
                Day(ms(jTransit - w / 360.0), ms(jTransit + w / 360.0), polarNight = false, midnightSun = false)
            }
        }
        return Triple(day, Unit, jTransit)
    }

    /** True after sunset and before sunrise at the phone's location; null if the location isn't known. */
    fun isDark(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean? {
        val (la, lo) = location(ctx) ?: return null
        val d = day(la, lo, nowMs)
        if (d.polarNight) return true
        if (d.midnightSun) return false
        return nowMs < d.rise!! || nowMs > d.set!!
    }
}
