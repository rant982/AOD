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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
                Surface(Modifier.fillMaxSize()) { SettingsScreen() }
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

    val listener = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val postNotif = remember(tick) { Build.VERSION.SDK_INT < 33 || hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val calendar = remember(tick) { hasPerm(ctx, Manifest.permission.READ_CALENDAR) }
    val location = remember(tick) { hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) }
    val ignoringBattery = remember(tick) { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }

    var enabled by remember { mutableStateOf(Prefs.enabled) }
    var showBlocked by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("My AOD", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable always-on display", Modifier.weight(1f))
                Switch(enabled, {
                    enabled = it
                    Prefs.enabled = it
                    val i = Intent(ctx, AodService::class.java)
                    if (it) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
                })
            }
        }
        item {
            Button({ ctx.startActivity(Intent(ctx, AodActivity::class.java)) }) { Text("Preview (triple-tap to close)") }
        }

        item { Header("Permissions") }
        item { PermRow("Notification access (required)", listener) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        item {
            PermRow("Draw over other apps (required)", overlay) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }
        item { PermRow("Show service notification", postNotif) { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        item { PermRow("Calendar", calendar) { permLauncher.launch(Manifest.permission.READ_CALENDAR) } }
        item { PermRow("Location (for weather)", location) { permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) } }
        item {
            PermRow("Unrestricted battery (keeps it alive)", ignoringBattery) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
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
        item { SliderRow("Brightness", Prefs.brightness, 0.01f..0.6f) { Prefs.brightness = it } }

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
        item { SwitchRow("Text previews", Prefs.showPreview) { Prefs.showPreview = it } }
        item { SwitchRow("Edge lighting on new notification", Prefs.edgeLight) { Prefs.edgeLight = it } }
        item { OutlinedButton({ showBlocked = true }) { Text("Choose apps to hide (${Prefs.blocked.size} hidden)") } }

        item { Header("Behaviour") }
        item { SwitchRow("Pixel shift (burn-in protection)", Prefs.pixelShift) { Prefs.pixelShift = it } }
        item { SwitchRow("Go black when face-down or in pocket", Prefs.hideCovered) { Prefs.hideCovered = it } }
        item { Text("Triple-tap the screen to wake the phone. Press power to turn the screen fully off.", style = MaterialTheme.typography.bodySmall) }
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
