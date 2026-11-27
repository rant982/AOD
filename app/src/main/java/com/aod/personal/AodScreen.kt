package com.aod.personal

import android.app.Activity
import android.app.NotificationManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.app.ActivityOptions
import android.net.Uri
import android.os.Build
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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

@OptIn(ExperimentalLayoutApi::class)
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
    val scope = rememberCoroutineScope()
    var weatherStatus by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf<Weather?>(null) }
    LaunchedEffect(Unit) {
        while (Prefs.showWeather) {
            weather = withContext(Dispatchers.IO) { WeatherRepo.load(ctx) }
            delay(30 * 60_000L)
        }
    }
    // Weather: tap once = full detail and a fresh forecast; tap again (within 10 seconds) = open the forecast.
    var weatherExpanded by remember { mutableStateOf(false) }
    var batteryExpanded by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf<String?>(null) } // key of the notification whose whole text is showing
    LaunchedEffect(weatherExpanded) {
        if (weatherExpanded) {
            delay(10_000)
            weatherExpanded = false
        }
    }
    val refreshWeather: () -> Unit = {
        if (weatherStatus.isEmpty()) {
            weatherStatus = "  ↻ updating…"
            scope.launch {
                val before = Prefs.wTime
                val fresh = withContext(Dispatchers.IO) { WeatherRepo.load(ctx, force = true) }
                if (fresh != null) weather = fresh
                weatherStatus = if (Prefs.wTime > before) "" else "  (couldn't update)"
                if (weatherStatus.isNotEmpty()) {
                    delay(3_000)
                    weatherStatus = ""
                }
            }
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
        var lastEdge = 0L
        if (Prefs.edgeLight) NotifRepo.events.collect {
            if (System.currentTimeMillis() - lastEdge < 8000) return@collect
            lastEdge = System.currentTimeMillis()
            repeat(3) {
                edge.animateTo(1f, tween(500))
                edge.animateTo(0f, tween(500))
            }
        }
    }

    // Icon flash: the new notification's icon pulses bigger and brighter for about 3 seconds.
    val flash = remember { Animatable(0f) }
    var flashPkg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (Prefs.flashIcon) NotifRepo.events.collectLatest { pkg ->
            flashPkg = pkg
            repeat((Prefs.flashSeconds / 0.7f).toInt().coerceAtLeast(1)) { // one pulse takes 0.7 s
                flash.animateTo(1f, tween(350))
                flash.animateTo(0f, tween(350))
            }
            flashPkg = null
        }
    }

    // Night = Do Not Disturb is on. The backlight is lowered by the activity; here the clock, icons and text themselves are
    // dimmed too, because lowering only the backlight can still look very bright on some phones.
    var night by remember { mutableStateOf(isDndOn(ctx)) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                night = isDndOn(c)
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    val dimK = if (night && Prefs.nightDim) Prefs.nightContent.coerceIn(0.05f, 1f) else 1f

    // Pixel shift (burn-in protection): jump to a new random spot as soon as the AOD appears, then every 45 seconds.
    val density = LocalDensity.current
    var shift by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(Unit) {
        if (!Prefs.pixelShift) return@LaunchedEffect
        val (hx, vy) = when (Prefs.shiftAmount) {
            0 -> 12 to 24
            2 -> 40 to 80
            else -> 24 to 48
        }
        while (true) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val night = nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            // By day it only moves up and down (sideways looks untidy); at night it moves sideways too.
            val dx = if (night) with(density) { Random.nextInt(-hx, hx + 1).dp.roundToPx() } else 0
            val dy = with(density) { Random.nextInt(-vy, vy + 1).dp.roundToPx() }
            shift = IntOffset(dx, dy)
            AodState.shiftNote = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "  moved to ($dx, $dy) px (${if (night) "night: up/down and sideways" else "day: up/down only"})"
            delay(45_000)
        }
    }
    val shiftAnim by animateIntOffsetAsState(shift, tween(2500), label = "shift")

    val activity = ctx as? Activity
    var expanded by remember { mutableStateOf<String?>(null) }
    var showVersion by remember { mutableStateOf(true) }
    // "Hidden WhatsApp. Undo": shown for 7 seconds after a Never-this-app / Never-this-notification tap. No confirmation is ever asked.
    var undo by remember { mutableStateOf<UndoInfo?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(7_000)
            undo = null
        }
    }
    LaunchedEffect(Unit) {
        delay(10_000)
        showVersion = false
    }


    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                if (edge.value > 0f) drawRoundRect(
                    brush = Brush.sweepGradient(listOf(accent, Color.Cyan, Color.Magenta, accent)),
                    alpha = edge.value * dimK,
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
                .offset { shiftAnim }
                .graphicsLayer { alpha = dimK },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Prefs.blockGap.dp, Alignment.CenterVertically)
        ) {
            val order = remember { Prefs.orderList() }
            val h = SimpleDateFormat(if (Prefs.use24h) "HH" else "h", Locale.getDefault()).format(Date(now))
            val m = SimpleDateFormat("mm", Locale.getDefault()).format(Date(now))
            val hourC = colorOr(Prefs.clockColor, accent)
            val minC = when {
                Prefs.clockMinColor != 0 -> Color(Prefs.clockMinColor)
                Prefs.clockStyle == 0 -> hourC
                else -> hourC.copy(alpha = 0.65f)
            }
            val infoC = colorOr(Prefs.infoColor, dim)
            // Battery current (like the Ampere app) and charging type, re-read every minute.
            val bat = remember(now, battery) { batteryInfo(ctx) }
            val batteryText = if (Prefs.showBattery && battery.first >= 0) batteryLine(battery.first, battery.second, bat, Prefs.showMilliamps, batteryExpanded) else null
            val weatherText = weather?.takeIf { Prefs.showWeather }?.let { WeatherRepo.format(it, Prefs.fahrenheit, if (weatherExpanded) 2 else Prefs.weatherStyle) }

            // Blocks are drawn in the order chosen in Settings.
            order.forEachIndexed { idx, key ->
                when (key) {
                    "LOGO" -> Logo(accent)

                    "CLOCK" -> {
                        // Triple-tap the clock to reach the lock screen. Nothing else on the screen unlocks.
                        Box(Modifier.tapOrTriple(onTap = {}, onTriple = onWake).padding(8.dp)) {
                            when (Prefs.clockStyle) {
                                0 -> Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = hourC)) { append(h) }
                                        withStyle(SpanStyle(color = minC)) { append(":$m") }
                                    },
                                    fontFamily = family, fontWeight = FontWeight.Light, fontSize = (88 * scale).sp
                                )
                                1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(h, color = hourC, fontFamily = family, fontWeight = FontWeight.Bold, fontSize = (110 * scale).sp, lineHeight = (100 * scale).sp)
                                    Text(m, color = minC, fontFamily = family, fontWeight = FontWeight.Light, fontSize = (110 * scale).sp, lineHeight = (100 * scale).sp)
                                }
                                else -> Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hourC)) { append(h) }
                                        withStyle(SpanStyle(fontWeight = FontWeight.Light, color = minC)) { append(":$m") }
                                    },
                                    fontFamily = family, fontSize = (88 * scale).sp
                                )
                            }
                        }
                    }

                    "DATE" -> if (Prefs.showDate) {
                        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd")
                        Line(SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)), infoC, 18)
                    }

                    // Just the percentage; tap once for the charging detail, tap again to hide it.
                    "BATTERY" -> batteryText?.let {
                        Line(it, infoC, 16, Modifier.onTapOnly { batteryExpanded = !batteryExpanded }.padding(vertical = 4.dp))
                    }

                    "WEATHER" -> weatherText?.let { w ->
                        Line(
                            w + weatherStatus, infoC, 16,
                            Modifier
                                .onTapOnly {
                                    if (!weatherExpanded) {
                                        weatherExpanded = true
                                        if (System.currentTimeMillis() - Prefs.wTime > 2 * 60_000L) refreshWeather()
                                    } else {
                                        weatherExpanded = false
                                        activity?.let { act -> openWeather(act) }
                                    }
                                }
                                .padding(vertical = 4.dp)
                        )
                    }

                    // Swipe the next appointment away, or triple-tap it to open it in the calendar.
                    "CALENDAR" -> event?.takeIf { Prefs.showCalendar }?.let { ev ->
                        val time = if (ev.begin <= now) "Now" else SimpleDateFormat(if (Prefs.use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ev.begin))
                        Line(
                            "📅 $time  ${ev.title}", infoC, 14,
                            Modifier
                                .swipeAway {
                                    CalendarRepo.dismiss(ev)
                                    event = null
                                    scope.launch { event = withContext(Dispatchers.IO) { CalendarRepo.next(ctx) } }
                                }
                                .tapOrTriple(onTap = {}, onTriple = { activity?.let { a -> openCalendarEvent(a, ev) } })
                                .padding(vertical = 6.dp)
                        )
                    }

                    "MUSIC" -> media?.takeIf { Prefs.showMusic }?.let {
                        Line("♪ ${it.title}" + if (it.artist.isNotBlank()) " – ${it.artist}" else "", infoC, 14)
                    }

                    "NOTIFS" -> if (Prefs.showNotifs && notifs.isNotEmpty()) {
                        val expandedPkg = notifs.firstOrNull { it.key == expanded }?.pkg
                        FlowRow(
                            Modifier.padding(top = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                        ) {
                            notifs.distinctBy { it.pkg }.take(24).forEach { n ->
                                (if (Prefs.iconStyle == 2) (n.appIcon ?: n.icon) else n.icon)?.let {
                                    val flashing = n.pkg == flashPkg
                                    val selected = n.pkg == expandedPkg
                                    val count = notifs.count { x -> x.pkg == n.pkg }
                                    Box(contentAlignment = Alignment.TopEnd) {
                                    Image(
                                        it.asImageBitmap(), null,
                                        Modifier
                                            .tapOrTriple(
                                                onTap = { expanded = if (selected) null else n.key },
                                                onTriple = { activity?.let { a -> openNotification(a, n) } } // triple-tap an icon to open its app
                                            )
                                            .alpha(if (Prefs.iconStyle != 0 && !selected) 0.8f else 1f)
                                            .padding(10.dp)
                                            .size(26.dp)
                                            .drawBehind {
                                                // A white ring around the icon for the few seconds after a new alert.
                                                if (flashing) drawCircle(
                                                    Color.White.copy(alpha = 0.55f + 0.45f * flash.value),
                                                    radius = size.minDimension / 2 + 7.dp.toPx(),
                                                    style = Stroke(width = 2.5.dp.toPx())
                                                )
                                            }
                                            .graphicsLayer {
                                                val k = if (flashing) flash.value else 0f
                                                scaleX = 1f + 0.7f * k
                                                scaleY = 1f + 0.7f * k
                                            },
                                        colorFilter = if (flashing && Prefs.iconStyle == 0) ColorFilter.tint(Color.White) else iconTint(n, selected, accent, dim)
                                    )
                                    // Several alerts from one app: show how many.
                                    if (count > 1 && Prefs.showBadge) Text(
                                        "$count", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 4.dp, end = 4.dp).background(accent, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                    }
                                }
                            }
                        }
                        val shown = if (Prefs.showPreview) notifs.take(3) else notifs.filter { it.key == expanded }
                        Column(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            shown.forEach { n ->
                                val open = n.key == expanded
                                val txt = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
                                // Tap an open message once to read all of it (and again to fold it back up).
                                val fullTxt = listOf(n.title, n.full).filter { it.isNotBlank() }.joinToString(": ")
                                val hasMore = n.full.isNotBlank() && n.full.trim() != n.text.trim() && fullTxt.length > txt.length
                                val all = open && showFull == n.key && hasMore
                                Text(
                                    "${n.app} · ${if (all) fullTxt else txt}" + if (open && hasMore && !all) " ▾" else "",
                                    color = dim.copy(alpha = if (open) 0.9f else 0.55f), fontSize = if (open) 15.sp else 13.sp,
                                    maxLines = if (all) 60 else if (open) 3 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .swipeAway {
                                            // Clears this one; the next alert from the same app (if any) comes up.
                                            val next = notifs.firstOrNull { x -> x.pkg == n.pkg && x.key != n.key }?.key
                                            NotifService.dismiss(n.key)
                                            if (expanded == n.key) expanded = next
                                        }
                                        .tapOrTriple(
                                            onTap = {
                                                if (open) {
                                                    showFull = if (showFull == n.key) null else n.key
                                                    AodLog.add("message tapped pkg=${n.pkg} hasMore=$hasMore textLen=${n.text.length} fullLen=${n.full.length}")
                                                } else expanded = n.key
                                            },
                                            onTriple = { activity?.let { a -> openNotification(a, n) } }
                                        )
                                        .padding(vertical = 6.dp, horizontal = 8.dp)
                                )
                                if (open) FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val same = notifs.filter { x -> x.pkg == n.pkg }
                                    val pos = same.indexOfFirst { x -> x.key == n.key } + 1
                                    HideChip("Clear this", dim, primary = true) {
                                        val next = same.firstOrNull { x -> x.key != n.key }?.key
                                        NotifService.dismiss(n.key)
                                        expanded = next
                                    }
                                    if (same.size > 1) {
                                        HideChip("Clear all ${same.size}", dim) {
                                            NotifService.dismissAll(same.map { x -> x.key })
                                            expanded = null
                                        }
                                        HideChip("Next ($pos/${same.size})", dim, "›") { expanded = same[pos % same.size].key }
                                    }
                                    HideChip("Never this app", dim) {
                                        Prefs.blocked = Prefs.blocked + n.pkg
                                        NotifService.refreshNow()
                                        expanded = null
                                        undo = UndoInfo("Hidden: ${n.app}") {
                                            Prefs.blocked = Prefs.blocked - n.pkg
                                            NotifService.refreshNow()
                                        }
                                    }
                                    HideChip("Never this notification", dim) {
                                        Prefs.muted = Prefs.muted + "${n.pkg}|${n.title}"
                                        NotifService.refreshNow()
                                        expanded = null
                                        undo = UndoInfo("Hidden: this notification") {
                                            Prefs.muted = Prefs.muted - "${n.pkg}|${n.title}"
                                            NotifService.refreshNow()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        undo?.let { u ->
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 24.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(u.text, color = dim, fontSize = 13.sp)
                HideChip("Undo", dim, "↶") {
                    u.restore()
                    undo = null
                }
            }
        }
    }
}

private class UndoInfo(val text: String, val restore: () -> Unit)

@Composable
private fun Line(text: String, color: Color, size: Int, modifier: Modifier = Modifier) {
    // Long lines (weather description, calendar titles) carry on to a second or third row.
    Text(
        text, color = color, fontSize = size.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth()
    )
}

fun colorOr(v: Int, default: Color): Color = if (v == 0) default else Color(v)

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
            Prefs.logoText, color = colorOr(Prefs.logoColor, accent.copy(alpha = 0.8f)), fontSize = 20.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

/** Single tap -> onTap; the set number of quick taps (2 by default) -> onTriple. Consumes the taps so the global wake gesture doesn't fire. */
private fun Modifier.tapOrTriple(onTap: () -> Unit, onTriple: () -> Unit, taps: Int = Prefs.openTaps): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val triple by rememberUpdatedState(onTriple)
    val times = remember { mutableListOf<Long>() }
    pointerInput(Unit) {
        detectTapGestures(onTap = {
            val t = System.currentTimeMillis()
            times.removeAll { t - it > 800 }
            times.add(t)
            if (times.size >= taps) {
                times.clear()
                triple()
            } else if (times.size == 1) tap()
        })
    }
}

/**
 * Triple-tap on the text: remember which message to open, reveal the real lock screen, and let the service
 * open it as soon as you unlock (face, fingerprint or PIN, whichever you use).
 */
private fun openCalendarEvent(activity: Activity, e: CalEvent) =
    openIntentAfterUnlock(activity, CalendarRepo.viewIntent(e), "calendar line")

private fun openWeather(activity: Activity) {
    val pkg = Prefs.weatherApp
    val intent = (if (pkg.isNotEmpty()) activity.packageManager.getLaunchIntentForPackage(pkg) else null)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather"))
    openIntentAfterUnlock(activity, intent, "weather")
}

/** Same rules as opening an app from a notification: unlock first (face, fingerprint or PIN), then open it. */
private fun openIntentAfterUnlock(activity: Activity, intent: Intent, what: String) {
    AodLog.add("Triple-tap on the $what")
    val km = activity.getSystemService(KeyguardManager::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun start() {
        try {
            activity.startActivity(intent)
        } catch (ex: Exception) {
            AodLog.add("Could not open $what: ${ex.message}")
        }
        activity.finish()
    }
    when {
        activity.intent.getBooleanExtra("preview", false) -> start()
        km.isKeyguardLocked -> {
            AodState.pendingIntent = intent
            AodState.pendingAt = System.currentTimeMillis()
            AodState.suppressRelaunchUntil = System.currentTimeMillis() + 30_000
            activity.finish()
        }
        else -> authenticateThen(activity) { start() }
    }
}

private fun openNotification(activity: Activity, n: NotifItem) {
    AodLog.add("Triple-tap on notification text pkg=${n.pkg}")
    val km = activity.getSystemService(KeyguardManager::class.java)
    when {
        activity.intent.getBooleanExtra("preview", false) -> {
            openNotificationNow(activity, n)
            activity.finish()
        }
        km.isKeyguardLocked -> {
            AodState.pendingOpen = n
            AodState.pendingAt = System.currentTimeMillis()
            AodState.suppressRelaunchUntil = System.currentTimeMillis() + 30_000
            activity.finish()
        }
        else -> authenticateThen(activity) {
            openNotificationNow(activity, n)
            activity.finish()
        }
    }
}

fun openNotificationNow(ctx: Context, n: NotifItem) {
    try {
        val pi: PendingIntent? = n.intent
        if (pi != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, o.toBundle())
            } else pi.send()
        } else {
            ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    } catch (e: Exception) {
    }
}

/**
 * Triple-tap anywhere else: close the AOD so the real lock screen shows. While the AOD covers the lock screen
 * Android doesn't listen for face or fingerprint; once it's gone, both work as normal, with PIN only as a fallback.
 */
fun unlock(activity: Activity) {
    val km = activity.getSystemService(KeyguardManager::class.java)
    if (activity.intent.getBooleanExtra("preview", false)) {
        activity.finish()
        return
    }
    if (km.isKeyguardLocked) {
        // Heading into the phone: land on Home after unlocking, and keep the AOD from coming back meanwhile.
        val until = System.currentTimeMillis() + 30_000
        AodState.wantHomeUntil = until
        AodState.suppressRelaunchUntil = until
        activity.finish()
    } else {
        authenticateThen(activity) {
            goHome(activity)
            activity.finish()
        }
    }
}

/** The home screen, whatever launcher is in use. */
fun goHome(ctx: Context) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AodLog.add("Could not open the home screen: ${e.message}")
    }
}

/**
 * The lock screen is already gone (for example face unlock went straight to the home screen), so ask for face,
 * fingerprint or PIN before letting anyone out of the AOD.
 */
fun authenticateThen(activity: Activity, after: () -> Unit) {
    if (Build.VERSION.SDK_INT < 30) {
        after()
        return
    }
    AodState.unlocking = true
    try {
        BiometricPrompt.Builder(activity)
            .setTitle("Unlock")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    AodState.unlocking = false
                    AodLog.add("Unlock check passed")
                    after()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    AodState.unlocking = false
                    AodLog.add("Unlock check cancelled/failed ($errorCode)")
                }
            })
    } catch (e: Exception) {
        AodState.unlocking = false
        AodLog.add("Unlock check could not start: ${e.message}")
    }
}

@Composable
private fun HideChip(label: String, color: Color, symbol: String = "✕", primary: Boolean = false, onClick: () -> Unit) {
    // The usual choice ("Clear this") is filled and bolder so it stands out from the rest.
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .then(if (primary) Modifier.background(color.copy(alpha = 0.22f), shape) else Modifier)
            .border(if (primary) 1.5.dp else 1.dp, color.copy(alpha = if (primary) 0.9f else 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (primary) 16.dp else 12.dp, vertical = if (primary) 8.dp else 6.dp)
    ) {
        Text(
            "$symbol $label", color = color.copy(alpha = if (primary) 1f else 0.8f),
            fontSize = if (primary) 14.sp else 12.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal
        )
    }
}

fun appVersion(c: Context): String =
    try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

/** Drag sideways past about 100dp and let go to dismiss. Short drags spring back. */
private fun Modifier.swipeAway(onDismiss: () -> Unit): Modifier = composed {
    val dismiss by rememberUpdatedState(onDismiss)
    var dx by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    this
        .offset { IntOffset(dx.roundToInt(), 0) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (abs(dx) > threshold) dismiss()
                    dx = 0f
                },
                onDragCancel = { dx = 0f },
                onHorizontalDrag = { _, d -> dx += d }
            )
        }
}

/** Mono: tinted to the AOD colour. Brand colour: the app's own accent colour (lightened if too dark). App icon: untouched. */
private fun iconTint(n: NotifItem, selected: Boolean, accent: Color, dim: Color): ColorFilter? = when (Prefs.iconStyle) {
    1 -> {
        val base = if (n.color != 0) Color(n.color or 0xFF000000.toInt()) else accent
        ColorFilter.tint(if (base.luminance() < 0.3f) lerp(base, Color.White, 0.5f) else base)
    }
    2 -> if (n.appIcon != null) null else ColorFilter.tint(if (selected) accent else dim)
    else -> ColorFilter.tint(if (selected) accent else dim)
}

/** Plain single-tap handler (no counting). */
private fun Modifier.onTapOnly(action: () -> Unit): Modifier = composed {
    val act by rememberUpdatedState(action)
    pointerInput(Unit) { detectTapGestures(onTap = { act() }) }
}

/** True while Do Not Disturb (any mode other than "allow all") is on. */
fun isDndOn(c: Context): Boolean {
    val f = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter
    return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
}

class BatInfo(val wireless: Boolean, val full: Boolean, val ma: Int?)

/** Instantaneous battery current in mA, signed: positive while charging, negative on battery (null if the phone doesn't say). */
fun batteryInfo(ctx: Context): BatInfo {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val full = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
    val raw = try {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    } catch (e: Exception) {
        Int.MIN_VALUE
    }
    // Most phones report microamps; a few report milliamps. Real currents never reach 20,000 mA.
    val magnitude = if (raw == Int.MIN_VALUE) null else abs(if (abs(raw.toLong()) >= 20_000L) raw / 1000 else raw)
    // Phones disagree about the sign of that number (some report charging as negative), so the direction comes from the
    // battery status instead: + while charging, - on battery, like the Ampere app.
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val sign = when {
        status == BatteryManager.BATTERY_STATUS_CHARGING -> 1
        status == BatteryManager.BATTERY_STATUS_DISCHARGING -> -1
        plugged != 0 -> 1 // full, or plugged in but not charging
        else -> -1
    }
    val ma = magnitude?.let { sign * it }
    return BatInfo(plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS, full, ma)
}

/**
 * One symbol for the state: 🔋 on battery or full, 🐢 slow (under 1000 mA), 🔌 normal, ⚡ fast (3000 mA+), 🚀 super fast (6000 mA+).
 * Then the percentage and the signed current, e.g. "⚡ 67%  +3450 mA" or "🔋 100%  -420 mA".
 */
fun batteryLine(level: Int, charging: Boolean, b: BatInfo, showMa: Boolean, expanded: Boolean = true): String {
    val ma = b.ma
    val symbol = when {
        !charging || b.full -> "🔋"
        ma == null -> "⚡"
        abs(ma) < 50 -> "🔋"
        abs(ma) < 1000 -> "🐢"
        abs(ma) < 3000 -> "🔌"
        abs(ma) < 6000 -> "⚡"
        else -> "🚀"
    }
    if (!expanded) return "$symbol $level%"
    val current = if (showMa && ma != null) "  " + (if (ma == 0) "0" else String.format(Locale.US, "%+d", ma)) + " mA" else ""
    val wireless = if (charging && b.wireless) "  wireless" else ""
    return "$symbol $level%$current$wireless"
}
