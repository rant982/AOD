package com.aod.personal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub for a newer build and installs it from inside the app. */
object Updater {
    const val API = "https://api.github.com/repos/rant982/AOD/releases/latest"
    const val PAGE = "https://github.com/rant982/AOD/releases/latest"

    class Latest(val build: Int, val name: String, val url: String, val notes: String = "")

    @Suppress("DEPRECATION")
    fun installedBuild(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    /** The bullet list of changes from a release's notes (without the technical footer). */
    private fun notesOf(body: String): String {
        val bullets = body.lines().map { it.trim() }.filter { it.startsWith("- ") }.joinToString("\n") { "\u2022 " + it.removePrefix("- ") }
        // Older releases have a single line of text instead of a list.
        return bullets.ifBlank { body.replace(Regex("\\[signing:[^\\]]*\\]"), "").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").trim() }
    }

    class Release(val name: String, val date: String, val notes: String)

    /** The most recent releases with their notes, newest first. Blocking: call off the main thread. */
    fun history(): Pair<List<Release>, String?> = try {
        val c = URL("https://api.github.com/repos/rant982/AOD/releases?per_page=20").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MyAOD-updater")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (c.responseCode != 200) emptyList<Release>() to "GitHub answered ${c.responseCode}"
        else {
            val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
            val out = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                Release(r.optString("name", r.optString("tag_name")), r.optString("published_at").take(10), notesOf(r.optString("body")))
            }
            out to null
        }
    } catch (e: Exception) {
        emptyList<Release>() to (e.message ?: "Couldn't reach GitHub")
    }

    /** (latest release, error). Blocking: call off the main thread. */
    fun check(): Pair<Latest?, String?> = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MyAOD-updater")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (c.responseCode != 200) {
            null to "GitHub answered ${c.responseCode}" + if (c.responseCode == 404) " (is the repository private?)" else ""
        } else {
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.getString("tag_name")
            val build = Regex("\\d+").findAll(tag).lastOrNull()?.value?.toIntOrNull()
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    break
                }
            }
            if (build == null || url == null) null to "The latest release has no installable file"
            else Latest(build, j.optString("name", tag), url, notesOf(j.optString("body"))) to null
        }
    } catch (e: Exception) {
        null to (e.message ?: "Couldn't reach GitHub")
    }

    /** Downloads the APK straight into an install session. Returns an error message, or null once the installer has it. */
    fun install(ctx: Context, latest: Latest, onProgress: (Int) -> Unit): String? {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        // Android 12+ can skip the confirmation for later updates once this app has installed itself once.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        return try {
            val c = URL(latest.url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "MyAOD-updater")
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (c.responseCode !in 200..299) {
                session.abandon()
                return "Download failed (${c.responseCode})"
            }
            val total = c.contentLengthLong
            val out = session.openWrite("my-aod.apk", 0, if (total > 0) total else -1)
            try {
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
                session.fsync(out)
            } finally {
                out.close()
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
            session.commit(pending.intentSender)
            AodLog.add("update to build ${latest.build} downloaded; handed to the installer")
            null
        } catch (e: Exception) {
            try {
                session.abandon()
            } catch (_: Exception) {
            }
            AodLog.add("update failed: ${e.message}")
            e.message ?: "Update failed"
        } finally {
            session.close()
        }
    }
}

/** Receives the installer's progress; when it needs the user's OK it shows Android's Install prompt. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AodLog.add("update installed")
            else -> AodLog.add("update not installed: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
