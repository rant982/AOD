package com.aod.personal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsScreen()
                        Text(
                            "v${appVersion(this@MainActivity)}",
                            Modifier.align(Alignment.TopEnd).padding(16.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun hasPerm(ctx: Context, p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun saveLogo(ctx: Context, uri: Uri) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return
    FileOutputStream(File(ctx.filesDir, "logo.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            saveLogo(ctx, uri)
            Prefs.logoMode = 1
            tick++
        }
    }

    val checks = remember(tick) { Requirements.checks(ctx) }
    val missing = checks.filter { it.required && !it.ok }
    val ready = missing.isEmpty()
    val running = remember(tick) { AodService.running }

    var enabled by remember { mutableStateOf(Prefs.enabled) }
    var showBlocked by remember { mutableStateOf(false) }
    var showWeatherApp by remember { mutableStateOf(false) }

    // Never leave the AOD switched on if setup has broken (e.g. a permission revoked).
    LaunchedEffect(tick) {
        if (Prefs.enabled && !ready) {
            Prefs.enabled = false
            enabled = false
            ctx.stopService(Intent(ctx, AodService::class.java))
        } else if (Prefs.enabled && ready && !AodService.running) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AodService::class.java))
        }
    }

    val checklist: LazyListScope.() -> Unit = {
        for ((level, title) in listOf(0 to "Required: the app will not run without these", 1 to "Recommended", 2 to "Optional features")) {
            val group = checks.filter { it.level == level }
            if (group.isEmpty()) continue
            item { Header(title) }
            items(group) { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (c.ok) "✅ " else if (c.required) "⛔ " else "⚠️ ") + c.title)
                        Text(c.why, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!c.ok && (c.permission != null || c.intent != null)) Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button({
                            if (c.permission != null) permLauncher.launch(c.permission) else ctx.startActivity(c.intent)
                        }) { Text(c.fixLabel) }
                        c.extraIntent?.let { ei -> OutlinedButton({ ctx.startActivity(ei) }) { Text(c.extraLabel ?: "More") } }
                    }
                }
            }
            if (level == 1) item {
                Text(
                    "Also check Settings → Security → Smart Lock and turn off On-body detection. Otherwise Android can keep the phone unlocked in your pocket. The app can't detect this setting.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("My AOD", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            val (bg, head, body) = when {
                !ready -> Triple(Color(0xFF5C1A1A), "⛔ AOD can't run yet", "Fix the ${missing.size} required item${if (missing.size == 1) "" else "s"} below. Until then it stays switched off.")
                !enabled -> Triple(Color(0xFF1F3D2B), "✅ All required checks passed", "Turn on \"Enable always-on display\" below.")
                running -> Triple(Color(0xFF1F3D2B), "✅ All required checks passed", "AOD is running. Switch the screen off and it should appear. Use the lock test below to confirm.")
                else -> Triple(Color(0xFF5A4A16), "⚠️ Enabled, but the service isn't running", "Switch Enable off and on again.")
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg)) {
                Column(Modifier.padding(16.dp)) {
                    Text(head, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                    val todo = checks.count { !it.ok && it.level == 1 }
                    if (ready && todo > 0) Text(
                        "⚠️ $todo recommended item${if (todo == 1) "" else "s"} still to do. See the setup checks at the bottom.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text("Last screen-off: ${AodState.lastEvent}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Text("Service started: ${Prefs.lastStart.ifEmpty { "not yet" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Pixel shift: ${AodState.shiftNote}", style = MaterialTheme.typography.bodySmall)
                    Text("Night mode: ${if (isDndOn(ctx)) "ON (Do Not Disturb is on)" else "off (Do Not Disturb is off)"}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "After a phone restart, unlock once (Android requires it) and the AOD starts by itself. If it doesn't, allow Auto-launch / background activity for My AOD under App settings > Battery.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ tick++ }) { Text("Re-run checks") }
                        OutlinedButton({
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("App settings") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable always-on display", Modifier.weight(1f))
                Switch(enabled, {
                    enabled = it
                    Prefs.enabled = it
                    val i = Intent(ctx, AodService::class.java)
                    if (it) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
                    tick++
                }, enabled = ready || enabled)
            }
        }
        item {
            Column {
                Button(
                    { lockPhone(ctx) },
                    enabled = ready && enabled && running
                ) { Text("Lock test: lock phone now") }
                Text(
                    "Locks the phone right now. The AOD should appear within a second and a quick double-tap on the clock should show your lock screen, where face or fingerprint work. If the screen stays off, check \"Last screen-off\" above.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            OutlinedButton({ ctx.startActivity(Intent(ctx, AodActivity::class.java).putExtra("preview", true)) }) { Text("Preview (tap the clock ${Prefs.openTaps} times to close)") }
        }

        // When anything required is missing the checklist sits right here; once all is well it moves to the bottom.
        if (!ready) checklist()

        item { Header("Clock") }
        item {
            var style by remember { mutableIntStateOf(Prefs.clockStyle) }
            Chips("Style", listOf("Single line", "Stacked", "Bold + light"), style) { style = it; Prefs.clockStyle = it }
        }
        item {
            var font by remember { mutableIntStateOf(Prefs.font) }
            Chips("Font", listOf("Sans", "Serif", "Mono", "Script"), font) { font = it; Prefs.font = it }
        }
        item { SwitchRow("24-hour clock", Prefs.use24h) { Prefs.use24h = it } }
        item { SliderRow("Colour", Prefs.hue, 0f..360f) { Prefs.hue = it } }
        item { SliderRow("Colour saturation (left = white)", Prefs.sat, 0f..1f) { Prefs.sat = it } }
        item { ColorSetting("Time colour (hours)", Prefs.clockColor, "Same as main colour") { Prefs.clockColor = it } }
        item { ColorSetting("Minutes colour", Prefs.clockMinColor, "Automatic") { Prefs.clockMinColor = it } }
        item { SliderRow("Clock size", Prefs.clockScale, 0.6f..1.4f) { Prefs.clockScale = it } }
        item { SliderRow("Spacing between items", Prefs.blockGap, 0f..60f) { Prefs.blockGap = it } }
        item { Text("Daytime brightness follows your phone's brightness setting (including automatic).", style = MaterialTheme.typography.bodySmall) }
        item { SwitchRow("Dim at night when Do Not Disturb is on", Prefs.nightDim) { Prefs.nightDim = it } }
        item { SliderRow("Night brightness (left = dimmest that is still visible)", Prefs.nightBrightness.coerceAtLeast(0.02f), 0.02f..0.2f) { Prefs.nightBrightness = it } }
        item { SliderRow("Night: how bright the clock and icons are (left = darker)", Prefs.nightContent, 0.1f..1f) { Prefs.nightContent = it } }

        item { Header("Screen order (top to bottom)") }
        item { OrderEditor() }

        item { Header("Logo") }
        item {
            var mode by remember(tick) { mutableIntStateOf(Prefs.logoMode) }
            Chips("Show", listOf("None", "Image", "Text"), mode) { mode = it; Prefs.logoMode = it }
        }
        item { OutlinedButton({ imagePicker.launch("image/*") }) { Text("Choose logo image") } }
        item {
            var text by remember { mutableStateOf(Prefs.logoText) }
            OutlinedTextField(text, { text = it; Prefs.logoText = it }, Modifier.fillMaxWidth(), label = { Text("Logo text") }, singleLine = true)
        }
        item { ColorSetting("Logo text colour", Prefs.logoColor, "Automatic") { Prefs.logoColor = it } }

        item { Header("Info lines") }
        item { ColorSetting("Info line colour (date, battery, weather, calendar, music)", Prefs.infoColor, "Automatic") { Prefs.infoColor = it } }
        item { SwitchRow("Date", Prefs.showDate) { Prefs.showDate = it } }
        item { SwitchRow("Battery", Prefs.showBattery) { Prefs.showBattery = it } }
        item {
            var detail by remember { mutableIntStateOf(Prefs.chargeDetail) }
            Chips("Charging detail (🐢 slow, ⚡ normal, ⚡⚡ fast, ⚡⚡⚡ super fast; 🛜 wireless, 🔌 cable)", listOf("Icons", "Icons + watts"), detail) { detail = it; Prefs.chargeDetail = it }
        }
        item { SwitchRow("Weather", Prefs.showWeather) { Prefs.showWeather = it } }
        item { SwitchRow("Use °F", Prefs.fahrenheit) { Prefs.fahrenheit = it } }
        item {
            var style by remember { mutableIntStateOf(Prefs.weatherStyle) }
            Chips("Weather detail", listOf("Temp only", "With description", "Full"), style) { style = it; Prefs.weatherStyle = it }
        }
        item {
            val appLabel = remember(tick, showWeatherApp) {
                val pkg = Prefs.weatherApp
                if (pkg.isEmpty()) "Google weather (browser)" else try {
                    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    "Google weather (browser)"
                }
            }
            Column {
                Text("On the AOD, tap the weather once for full detail and a fresh forecast, then tap again to open the forecast.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ showWeatherApp = true }) { Text("Forecast opens in: $appLabel") }
            }
        }
        item {
            var lat by remember { mutableStateOf(Prefs.lat) }
            var lon by remember { mutableStateOf(Prefs.lon) }
            Column {
                Text("Weather location override (blank = use phone location)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lat, { lat = it; Prefs.lat = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true)
                    OutlinedTextField(lon, { lon = it; Prefs.lon = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true)
                }
            }
        }
        item { SwitchRow("Next calendar event", Prefs.showCalendar) { Prefs.showCalendar = it } }
        item { SwitchRow("Music now playing", Prefs.showMusic) { Prefs.showMusic = it } }

        item { Header("Notifications") }
        item { SwitchRow("Notification icons", Prefs.showNotifs) { Prefs.showNotifs = it } }
        item {
            var iconStyle by remember { mutableIntStateOf(Prefs.iconStyle) }
            Chips("Icon style", listOf("Mono", "Brand colour", "App icon"), iconStyle) { iconStyle = it; Prefs.iconStyle = it }
        }
        item { SwitchRow("Always show notification text (off = tap an icon to read it)", Prefs.showPreview) { Prefs.showPreview = it } }
        item { SwitchRow("Edge lighting on new notification", Prefs.edgeLight) { Prefs.edgeLight = it } }
        item { SwitchRow("Flash the icon of a new notification", Prefs.flashIcon) { Prefs.flashIcon = it } }
        item { SwitchRow("Count badge when an app has several notifications", Prefs.showBadge) { Prefs.showBadge = it } }
        item { OutlinedButton({ showBlocked = true }) { Text("Choose apps to hide (${Prefs.blocked.size} hidden)") } }
        item { Text("Tip: tap an icon on the AOD, then 'Never this app' or 'Never this notification'.", style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedButton({
                Prefs.muted = emptySet()
                NotifService.refreshNow()
                tick++
            }) { Text("Un-hide muted notifications (${Prefs.muted.size})") }
        }

        item { Header("Behaviour") }
        item {
            var taps by remember { mutableIntStateOf(Prefs.openTaps) }
            Chips("Taps to leave the AOD (clock, icons, calendar line)", listOf("2 taps", "3 taps"), taps - 2) { taps = it + 2; Prefs.openTaps = it + 2 }
        }
        item { SwitchRow("Pixel shift (burn-in protection)", Prefs.pixelShift) { Prefs.pixelShift = it } }
        item {
            var amount by remember { mutableIntStateOf(Prefs.shiftAmount) }
            Chips("Pixel shift distance", listOf("Small", "Medium", "Large"), amount) { amount = it; Prefs.shiftAmount = it }
        }
        item { SwitchRow("Go black when face-down or in pocket", Prefs.hideCovered) { Prefs.hideCovered = it } }
        item { SwitchRow("Pocket guard: ignore all touches when covered, just pulled out, or rubbed by fabric or a palm", Prefs.pocketGuard) { Prefs.pocketGuard = it } }
        item { SwitchRow("Power button turns the screen fully off (otherwise the AOD comes straight back)", Prefs.powerOff) { Prefs.powerOff = it } }
        item {
            val tapWord = if (Prefs.openTaps == 2) "Double-tap" else "Triple-tap"
            Text(
                "Tap an icon (e.g. WhatsApp) to show its text. $tapWord an icon or its text to open that app (swipe the text sideways to clear it). $tapWord the clock to go to your home screen. $tapWord the calendar line to open that appointment, or swipe it away. Tap the weather once for full detail, again to open the forecast. Taps anywhere else do nothing, so the pocket is safe.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        item { Header("Diagnostics") }
        item {
            val logText = remember(tick) { AodLog.read().lines().takeLast(12).joinToString("\n") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "If the AOD ever switches to the lock or home screen by itself, tap Copy event log straight afterwards and paste it to Claude. It holds app names and lock states only, never notification text.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AOD event log", AodLog.read()))
                        android.widget.Toast.makeText(ctx, "Event log copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Copy event log") }
                    OutlinedButton({
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AodLog.read())
                        ctx.startActivity(Intent.createChooser(send, "Share event log"))
                    }) { Text("Share") }
                    OutlinedButton({
                        AodLog.clear()
                        tick++
                    }) { Text("Clear") }
                }
                Text(
                    logText.ifBlank { "(no events yet)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
        if (ready) {
            item { Header("Setup checks (all required items passed)") }
            checklist()
        }
    }

    if (showBlocked) BlockedAppsDialog { showBlocked = false }
    if (showWeatherApp) WeatherAppDialog { showWeatherApp = false }
}

@Composable
private fun BlockedAppsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var blocked by remember { mutableStateOf(Prefs.blocked) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("Done") } },
        title = { Text("Hide notifications from") },
        text = {
            LazyColumn {
                items(apps) { (pkg, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in blocked, {
                            blocked = if (it) blocked + pkg else blocked - pkg
                            Prefs.blocked = blocked
                        })
                        Text(label)
                    }
                }
            }
        }
    )
}

@Composable
private fun Header(t: String) = Text(t, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun PermRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (granted) "✅ " else "⚠️ ") + label, Modifier.weight(1f))
        if (!granted) Button(onGrant) { Text("Grant") }
    }
}

@Composable
private fun SwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(v, { v = it; onChange(it) })
    }
}

@Composable
private fun SliderRow(label: String, initial: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(initial) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(v, { v = it; onChange(it) }, valueRange = range)
    }
}

@Composable
private fun Chips(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o -> FilterChip(selected == i, { onSelect(i) }, { Text(o) }) }
        }
    }
}


private fun blockLabel(key: String) = when (key) {
    "LOGO" -> "Logo"
    "CLOCK" -> "Time"
    "DATE" -> "Date"
    "BATTERY" -> "Battery"
    "WEATHER" -> "Weather"
    "CALENDAR" -> "Next calendar event"
    "MUSIC" -> "Music"
    else -> "Notifications (icons and text)"
}

@Composable
private fun OrderEditor() {
    var order by remember { mutableStateOf(Prefs.orderList()) }
    fun save(l: List<String>) {
        order = l
        Prefs.order = l.joinToString(",")
    }
    Column {
        Text("Use the arrows to move items. Items you've switched off don't show.", style = MaterialTheme.typography.bodySmall)
        order.forEachIndexed { i, key ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.  ${blockLabel(key)}", Modifier.weight(1f))
                TextButton({ save(order.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }, enabled = i > 0) { Text("▲") }
                TextButton({ save(order.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }, enabled = i < order.size - 1) { Text("▼") }
            }
        }
        OutlinedButton({ save(Prefs.ALL_BLOCKS) }) { Text("Reset order") }
    }
}


private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFFFFE9C4, 0xFF9E9E9E, 0xFFFF5252, 0xFFFF9800, 0xFFFFC107, 0xFFFFEB3B,
    0xFF66BB6A, 0xFF4DD0A1, 0xFF26C6DA, 0xFF5C9DFF, 0xFFB388FF, 0xFFFF80AB
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSetting(label: String, initial: Int, autoLabel: String, onChange: (Int) -> Unit) {
    var cur by remember { mutableIntStateOf(initial) }
    var hue by remember { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                cur = 0
                onChange(0)
            }) { Text(if (cur == 0) "✓ $autoLabel" else autoLabel) }
            SWATCHES.forEach { c ->
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Color(c), CircleShape)
                        .border(if (cur == c) 3.dp else 1.dp, if (cur == c) Color.White else Color.Gray, CircleShape)
                        .clickable {
                            cur = c
                            onChange(c)
                        }
                )
            }
        }
        Text("Or slide for any colour", style = MaterialTheme.typography.bodySmall)
        Slider(hue, {
            hue = it
            val c = android.graphics.Color.HSVToColor(floatArrayOf(it, 1f, 1f))
            cur = c
            onChange(c)
        }, valueRange = 0f..360f)
    }
}


@Composable
private fun WeatherAppDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("Cancel") } },
        title = { Text("Open the forecast in") },
        text = {
            LazyColumn {
                item {
                    TextButton({
                        Prefs.weatherApp = ""
                        onClose()
                    }) { Text("Google weather (browser)") }
                }
                items(apps) { (pkg, label) ->
                    TextButton({
                        Prefs.weatherApp = pkg
                        onClose()
                    }) { Text(label) }
                }
            }
        }
    )
}
