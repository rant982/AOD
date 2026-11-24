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

    private lateinit var msm: MediaSessionManager
    private lateinit var cn: ComponentName
    private var watched: List<MediaController> = emptyList()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it ?: emptyList()) }
    private val controllerCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
    }

    /** The system keeps this listener alive, so use it as a watchdog: restart the AOD service if it has died. */
    private fun ensureService() {
        Prefs.init(this)
        if (Prefs.enabled && !AodService.running) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, AodService::class.java).putExtra("reason", "keeper")
                )
            } catch (e: Exception) {
            }
        }
    }

    override fun onListenerConnected() {
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
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        watched.forEach { it.unregisterCallback(controllerCb) }
        watched = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ensureService()
        val n = sbn.notification
        if (sbn.packageName != packageName && n.fullScreenIntent == null &&
            n.category != Notification.CATEGORY_CALL && n.category != Notification.CATEGORY_ALARM
        ) AodState.lastNotifAt = System.currentTimeMillis()
        refresh(true)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh(false)

    private fun refresh(emit: Boolean) {
        Prefs.init(this)
        val blocked = Prefs.blocked
        val old = NotifRepo.items.value
        val list = try {
            activeNotifications.orEmpty()
        } catch (e: Exception) {
            emptyArray()
        }.filter { sbn ->
            val n = sbn.notification
            sbn.packageName != packageName &&
                sbn.packageName !in blocked &&
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
                intent = sbn.notification.contentIntent,
                icon = try {
                    sbn.notification.smallIcon?.loadDrawable(this)?.toBitmap(64, 64)
                } catch (e: Exception) {
                    null
                }
            )
        }
        NotifRepo.items.value = list
        if (emit && list.any { n -> old.none { it.key == n.key && it.text == n.text } }) NotifRepo.events.tryEmit(Unit)
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
