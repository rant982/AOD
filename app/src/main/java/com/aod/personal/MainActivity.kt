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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
                running -> Triple(Color(0xFF1F3D2B), "✅ AOD is running", "Switch the screen off and it should appear. Use the lock test below to confirm.")
                else -> Triple(Color(0xFF5A4A16), "⚠️ Enabled, but the service isn't running", "Switch Enable off and on again.")
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg)) {
                Column(Modifier.padding(16.dp)) {
                    Text(head, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                    Text("Last screen-off: ${AodState.lastEvent}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton({ tick++ }, Modifier.padding(top = 8.dp)) { Text("Re-run checks") }
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
                    "Locks the phone right now. The AOD should appear within a second and a triple-tap should show your lock screen, where face or fingerprint work. If the screen stays off, check \"Last screen-off\" above.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            OutlinedButton({ ctx.startActivity(Intent(ctx, AodActivity::class.java).putExtra("preview", true)) }) { Text("Preview (triple-tap the clock to close)") }
        }

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
        item { SliderRow("Clock size", Prefs.clockScale, 0.6f..1.4f) { Prefs.clockScale = it } }
        item { Text("Daytime brightness follows your phone's brightness setting (including automatic).", style = MaterialTheme.typography.bodySmall) }
        item { SwitchRow("Dim at night when Do Not Disturb is on", Prefs.nightDim) { Prefs.nightDim = it } }
        item { SliderRow("Night brightness (left = lowest)", Prefs.nightBrightness, 0f..0.2f) { Prefs.nightBrightness = it } }

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

        item { Header("Info lines") }
        item { SwitchRow("Date", Prefs.showDate) { Prefs.showDate = it } }
        item { SwitchRow("Battery", Prefs.showBattery) { Prefs.showBattery = it } }
        item { SwitchRow("Weather", Prefs.showWeather) { Prefs.showWeather = it } }
        item { SwitchRow("Use °F", Prefs.fahrenheit) { Prefs.fahrenheit = it } }
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
        item { SwitchRow("Always show notification text (off = tap an icon to read it)", Prefs.showPreview) { Prefs.showPreview = it } }
        item { SwitchRow("Edge lighting on new notification", Prefs.edgeLight) { Prefs.edgeLight = it } }
        item { OutlinedButton({ showBlocked = true }) { Text("Choose apps to hide (${Prefs.blocked.size} hidden)") } }

        item { Header("Behaviour") }
        item { SwitchRow("Pixel shift (burn-in protection)", Prefs.pixelShift) { Prefs.pixelShift = it } }
        item { SwitchRow("Go black when face-down or in pocket", Prefs.hideCovered) { Prefs.hideCovered = it } }
        item { SwitchRow("Power button turns the screen fully off (otherwise the AOD comes straight back)", Prefs.powerOff) { Prefs.powerOff = it } }
        item { Text("Tap an icon (e.g. WhatsApp) to show its text. Triple-tap that text to open the message. Triple-tap the clock to reach the lock screen. Taps anywhere else do nothing, so the pocket is safe.", style = MaterialTheme.typography.bodySmall) }
    }

    if (showBlocked) BlockedAppsDialog { showBlocked = false }
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
