package com.aod.personal

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

class NotifService : NotificationListenerService() {

    private val appIconCache = HashMap<String, android.graphics.Bitmap?>()
    private val lastLogged = HashMap<String, Long>()

    companion object {
        @Volatile private var instance: NotifService? = null

        /** Clears a notification from the phone (same as swiping it away in the shade). */
        /** Re-applies the hide rules straight away. */
        fun refreshNow() {
            instance?.refresh(false)
        }

        /** Clears several notifications at once (all alerts from one app). */
        fun dismissAll(keys: List<String>) {
            try {
                instance?.cancelNotifications(keys.toTypedArray())
            } catch (e: Exception) {
            }
        }

        fun dismiss(key: String) {
            try {
                instance?.cancelNotification(key)
            } catch (e: Exception) {
            }
        }
    }

    private lateinit var msm: MediaSessionManager
    private lateinit var cn: ComponentName
    private var watched: List<MediaController> = emptyList()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it ?: emptyList()) }
    private val controllerCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
    }

    /** The system keeps this listener alive, so use it as a watchdog: restart the AOD service if it has died. */
    private var lastEnsure = 0L

    private fun ensureService() {
        Prefs.init(this)
        val now = System.currentTimeMillis()
        if (now - lastEnsure < 30_000) return
        if (Prefs.enabled && !AodService.running) {
            lastEnsure = now
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, AodService::class.java).putExtra("reason", "keeper")
                )
            } catch (e: Exception) {
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        Prefs.init(this)
        ensureService()
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        cn = ComponentName(this, NotifService::class.java)
        refresh(false)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
            watch(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        watched.forEach { it.unregisterCallback(controllerCb) }
        watched = emptyList()
    }

    /** Suggested replies: the phone's own smart replies for this message (on-device, nothing is sent anywhere), plus any the app offers. */
    private fun suggestionsFor(sbn: StatusBarNotification): List<String> {
        val out = mutableListOf<String>()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val r = NotificationListenerService.Ranking()
                if (currentRanking.getRanking(sbn.key, r)) r.smartReplies?.forEach { out.add(it.toString()) }
            }
        } catch (e: Exception) {
        }
        try {
            sbn.notification.actions?.forEach { a -> a.remoteInputs?.forEach { ri -> ri.choices?.forEach { out.add(it.toString()) } } }
        } catch (e: Exception) {
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 60 }.distinct().take(4)
    }

    /** The whole message: the expanded text, an inbox list, or all the lines of a chat, whichever the app provides. */
    @Suppress("DEPRECATION")
    private fun fullText(ex: android.os.Bundle): String {
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (big.isNotBlank()) return big
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }.orEmpty()
        if (lines.isNotBlank()) return lines
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { m ->
            (m as? android.os.Bundle)?.let { b ->
                val t = b.getCharSequence("text")?.toString().orEmpty()
                val who = b.getCharSequence("sender")?.toString()
                if (t.isBlank()) null else if (who.isNullOrBlank()) t else "$who: $t"
            }
        }?.joinToString("\n").orEmpty()
        return msgs
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Our own foreground/alert notification must never trigger anything (that caused a restart loop).
        if (sbn.packageName == packageName) return
        ensureService()
        val n = sbn.notification
        // Log each app's posts at most once a minute, so a chatty app can't push the useful lines out of the log.
        val nowMs = System.currentTimeMillis()
        if (nowMs - (lastLogged[sbn.packageName] ?: 0L) > 60_000L) {
            lastLogged[sbn.packageName] = nowMs
            val x = n.extras
            AodLog.add(
                "notification posted pkg=${sbn.packageName} category=${n.category} " +
                    "text=${x.getCharSequence(Notification.EXTRA_TEXT)?.length ?: 0} big=${x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.length ?: 0} " +
                    "lines=${x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.size ?: 0} msgs=${x.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0} " +
                    "full=${fullText(x).length}"
            )
        }
        if (n.fullScreenIntent != null || n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) {
            AodState.lastFullScreenAt = System.currentTimeMillis()
        } else if (sbn.packageName != packageName) {
            AodState.lastNotifAt = System.currentTimeMillis()
        }
        refresh(true)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh(false)

    fun refresh(emit: Boolean) {
        Prefs.init(this)
        val blocked = Prefs.blocked
        val muted = Prefs.muted
        val old = NotifRepo.items.value
        val list = try {
            activeNotifications.orEmpty()
        } catch (e: Exception) {
            emptyArray()
        }.filter { sbn ->
            val n = sbn.notification
            sbn.packageName != packageName &&
                sbn.packageName !in blocked &&
                "${sbn.packageName}|${n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()}" !in muted &&
                n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                n.flags and Notification.FLAG_ONGOING_EVENT == 0 &&
                n.category != Notification.CATEGORY_TRANSPORT
        }.sortedByDescending { it.postTime }.map { sbn ->
            val ex = sbn.notification.extras
            NotifItem(
                key = sbn.key,
                pkg = sbn.packageName,
                app = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                } catch (e: Exception) {
                    sbn.packageName
                },
                title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty(),
                full = fullText(ex),
                reply = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true },
                suggestions = suggestionsFor(sbn),
                // The app's own "Mark as read" (e.g. WhatsApp): marks the chat read on every device, unlike clearing it here.
                markRead = sbn.notification.actions?.firstOrNull { a ->
                    (android.os.Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                        a.title?.toString()?.contains("mark as read", ignoreCase = true) == true
                },
                intent = sbn.notification.contentIntent,
                color = sbn.notification.color,
                appIcon = appIconCache.getOrPut(sbn.packageName) { try {
                    packageManager.getApplicationIcon(sbn.packageName).toBitmap(64, 64)
                } catch (e: Exception) {
                    null
                } },
                icon = try {
                    sbn.notification.smallIcon?.loadDrawable(this)?.toBitmap(64, 64)
                } catch (e: Exception) {
                    null
                }
            )
        }
        NotifRepo.items.value = list
        val fresh = if (emit) list.firstOrNull { n -> old.none { it.key == n.key } } else null
        if (fresh != null) NotifRepo.events.tryEmit(fresh.pkg)
    }

    private fun watch(list: List<MediaController>) {
        watched.forEach { it.unregisterCallback(controllerCb) }
        watched = list
        list.forEach { it.registerCallback(controllerCb) }
        updateMedia()
    }

    private fun updateMedia() {
        val c = watched.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        NotifRepo.media.value = if (c == null || title.isNullOrBlank()) null
        else MediaInfo(title, md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
    }
}
