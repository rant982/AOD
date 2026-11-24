package com.aod.personal

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@Composable
fun AodScreen(covered: Boolean, onWake: () -> Unit) {
    val ctx = LocalContext.current
    val accent = remember { Color.hsv(Prefs.hue, Prefs.sat, 1f) }
    val dim = accent.copy(alpha = 0.65f)
    val family = when (Prefs.font) {
        0 -> FontFamily.SansSerif
        1 -> FontFamily.Serif
        2 -> FontFamily.Monospace
        else -> FontFamily.Cursive
    }
    val scale = Prefs.clockScale

    // Time, ticking on the minute
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000 + 50)
        }
    }

    // Battery
    var battery by remember { mutableStateOf(Pair(-1, false)) }
    DisposableEffect(Unit) {
        fun parse(i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scaleMax = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            battery = Pair(level * 100 / scaleMax, st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = parse(i)
        }
        parse(ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { ctx.unregisterReceiver(r) }
    }

    // Weather and calendar
    var weather by remember { mutableStateOf<Weather?>(null) }
    LaunchedEffect(Unit) {
        while (Prefs.showWeather) {
            weather = withContext(Dispatchers.IO) { WeatherRepo.load(ctx) }
            delay(30 * 60_000L)
        }
    }
    var event by remember { mutableStateOf<CalEvent?>(null) }
    LaunchedEffect(Unit) {
        while (Prefs.showCalendar) {
            event = withContext(Dispatchers.IO) { CalendarRepo.next(ctx) }
            delay(10 * 60_000L)
        }
    }

    // Notifications and music
    val notifs by NotifRepo.items.collectAsState()
    val media by NotifRepo.media.collectAsState()

    // Edge lighting
    val edge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (Prefs.edgeLight) NotifRepo.events.collect {
            repeat(3) {
                edge.animateTo(1f, tween(500))
                edge.animateTo(0f, tween(500))
            }
        }
    }

    // Pixel shift
    var shift by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(Unit) {
        while (Prefs.pixelShift) {
            delay(60_000)
            shift = IntOffset(Random.nextInt(-30, 31), Random.nextInt(-60, 61))
        }
    }
    val shiftAnim by animateIntOffsetAsState(shift, tween(3000), label = "shift")

    val activity = ctx as? Activity
    var expanded by remember { mutableStateOf<String?>(null) }
    var showVersion by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(10_000)
        showVersion = false
    }

    // Triple tap to wake
    val taps = remember { mutableListOf<Long>() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val t = System.currentTimeMillis()
                    taps.removeAll { t - it > 800 }
                    taps.add(t)
                    if (taps.size >= 3) onWake()
                })
            }
            .drawBehind {
                if (edge.value > 0f) drawRoundRect(
                    brush = Brush.sweepGradient(listOf(accent, Color.Cyan, Color.Magenta, accent)),
                    alpha = edge.value,
                    cornerRadius = CornerRadius(48.dp.toPx()),
                    style = Stroke(16.dp.toPx())
                )
            }
    ) {
        if (!covered && showVersion) Text(
            "v${appVersion(ctx)}", color = dim.copy(alpha = 0.5f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp)
        )
        if (!covered) Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp)
                .offset { shiftAnim },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Logo(accent)

            val h = SimpleDateFormat(if (Prefs.use24h) "HH" else "h", Locale.getDefault()).format(Date(now))
            val m = SimpleDateFormat("mm", Locale.getDefault()).format(Date(now))
            when (Prefs.clockStyle) {
                0 -> Text("$h:$m", color = accent, fontFamily = family, fontWeight = FontWeight.Light, fontSize = (88 * scale).sp)
                1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h, color = accent, fontFamily = family, fontWeight = FontWeight.Bold, fontSize = (110 * scale).sp, lineHeight = (100 * scale).sp)
                    Text(m, color = dim, fontFamily = family, fontWeight = FontWeight.Light, fontSize = (110 * scale).sp, lineHeight = (100 * scale).sp)
                }
                else -> Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(h) }
                        withStyle(SpanStyle(fontWeight = FontWeight.Light, color = dim)) { append(":$m") }
                    },
                    fontFamily = family, fontSize = (88 * scale).sp
                )
            }

            if (Prefs.showDate) {
                val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd")
                Line(SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)), dim, 18)
            }

            val bits = buildList {
                if (Prefs.showBattery && battery.first >= 0) add((if (battery.second) "⚡" else "🔋") + " ${battery.first}%")
                weather?.takeIf { Prefs.showWeather }?.let {
                    val t = if (Prefs.fahrenheit) "${Math.round(it.tempC * 9f / 5f + 32)}°F" else "${Math.round(it.tempC)}°C"
                    add("${WeatherRepo.emoji(it.code)} $t")
                }
            }
            if (bits.isNotEmpty()) Line(bits.joinToString("   "), dim, 16)

            event?.takeIf { Prefs.showCalendar }?.let {
                val time = if (it.begin <= now) "Now" else SimpleDateFormat(if (Prefs.use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(it.begin))
                Line("📅 $time  ${it.title}", dim, 14)
            }

            media?.takeIf { Prefs.showMusic }?.let {
                Line("♪ ${it.title}" + if (it.artist.isNotBlank()) " – ${it.artist}" else "", dim, 14)
            }

            if (Prefs.showNotifs && notifs.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    notifs.distinctBy { it.pkg }.take(8).forEach { n ->
                        n.icon?.let {
                            Image(
                                it.asImageBitmap(), null,
                                Modifier
                                    .tapOrTriple(
                                        onTap = { expanded = if (expanded == n.key) null else n.key },
                                        onTriple = { activity?.let { a -> unlock(a) } }
                                    )
                                    .padding(10.dp)
                                    .size(26.dp),
                                colorFilter = ColorFilter.tint(if (expanded == n.key) accent else dim)
                            )
                        }
                    }
                }
                val shown = if (Prefs.showPreview) notifs.take(3) else notifs.filter { it.key == expanded }
                Column(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    shown.forEach { n ->
                        val open = n.key == expanded
                        val txt = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
                        Text(
                            "${n.app} · $txt", color = dim.copy(alpha = if (open) 0.9f else 0.55f), fontSize = if (open) 15.sp else 13.sp,
                            maxLines = if (open) 12 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier
                                .tapOrTriple(
                                    onTap = { expanded = n.key },
                                    onTriple = { activity?.let { a -> openNotification(a, n) } }
                                )
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, color: Color, size: Int) {
    Text(text, color = color, fontSize = size.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Logo(accent: Color) {
    val ctx = LocalContext.current
    when (Prefs.logoMode) {
        1 -> {
            val bmp = remember {
                runCatching { BitmapFactory.decodeFile(File(ctx.filesDir, "logo.png").path)?.asImageBitmap() }.getOrNull()
            }
            bmp?.let { Image(it, null, Modifier.size(72.dp).padding(bottom = 12.dp)) }
        }
        2 -> if (Prefs.logoText.isNotBlank()) Text(
            Prefs.logoText, color = accent.copy(alpha = 0.8f), fontSize = 20.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

/** Single tap -> onTap; three quick taps -> onTriple. Consumes the taps so the global wake gesture doesn't fire. */
private fun Modifier.tapOrTriple(onTap: () -> Unit, onTriple: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val triple by rememberUpdatedState(onTriple)
    val times = remember { mutableListOf<Long>() }
    pointerInput(Unit) {
        detectTapGestures(onTap = {
            val t = System.currentTimeMillis()
            times.removeAll { t - it > 800 }
            times.add(t)
            if (times.size >= 3) {
                times.clear()
                triple()
            } else if (times.size == 1) tap()
        })
    }
}

/** Unlock if needed, open the notification's app, and close the AOD. */
private fun openNotification(activity: Activity, n: NotifItem) {
    val go = {
        try {
            val pi: PendingIntent? = n.intent
            if (pi != null) pi.send()
            else activity.packageManager.getLaunchIntentForPackage(n.pkg)?.let { activity.startActivity(it) }
        } catch (e: Exception) {
        }
        activity.finish()
    }
    unlockThen(activity, go)
}

/** Ask for PIN/biometric if the phone is locked, then run [after]. */
fun unlockThen(activity: Activity, after: () -> Unit) {
    val km = activity.getSystemService(KeyguardManager::class.java)
    if (km.isKeyguardLocked) {
        km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = after()
        })
    } else after()
}

fun unlock(activity: Activity) = unlockThen(activity) { activity.finish() }

fun appVersion(c: Context): String =
    try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
