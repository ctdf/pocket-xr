package com.hayeco.flipspaces.addon

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 1.1.0: notification access, now in the add-on. Android binds it once notification access is on (Pocket XR turns it
 * on through Shizuku, `cmd notification allow_listener`). It sends the phone's notifications to Pocket XR for the
 * Space's Notifications page ([LinkService.notes]), dismisses them when asked, and says which apps play media (only a
 * listener's own app may read the media sessions).
 */
class NotesListener : NotificationListenerService() {
    override fun onListenerConnected() {
        current = this
        LinkService.notesChanged(true)
        send()
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (current === this) current = null
        LinkService.notesChanged(false)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = send()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = send()

    /** The rows as [IAddonCallback.notes] describes them. */
    fun rows(): Bundle {
        val active = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        val shown = active
            .filter { !it.isOngoing || it.notification.category == Notification.CATEGORY_CALL }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { it.packageName != packageName && it.packageName != POCKET_XR }
            .mapNotNull { row(it) }
            .sortedByDescending { it.posted }
        return Bundle().apply {
            putStringArray("keys", shown.map { it.key }.toTypedArray())
            putStringArray("apps", shown.map { it.app }.toTypedArray())
            putStringArray("packages", shown.map { it.pkg }.toTypedArray())
            putStringArray("titles", shown.map { it.title }.toTypedArray())
            putStringArray("texts", shown.map { it.text }.toTypedArray())
            putLongArray("posted", shown.map { it.posted }.toLongArray())
            putBooleanArray("clearable", shown.map { it.clearable }.toBooleanArray())
        }
    }

    fun playingPackages(): Array<String> {
        val m = getSystemService(MediaSessionManager::class.java) ?: return emptyArray()
        return m.getActiveSessions(ComponentName(this, NotesListener::class.java))
            .filter { val s = it.playbackState?.state; s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING }
            .map { it.packageName }
            .distinct()
            .toTypedArray()
    }

    private fun send() = LinkService.notes(rows())

    private class Row(val key: String, val app: String, val pkg: String, val title: String, val text: String, val posted: Long, val clearable: Boolean)

    private fun row(sbn: StatusBarNotification): Row? {
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val pm = packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        return Row(sbn.key, app, sbn.packageName, title, text, sbn.postTime, sbn.isClearable)
    }

    companion object {
        private const val TAG = "PocketXrAddonNotes"

        /** Pocket XR's own notifications are not shown on its page. */
        private const val POCKET_XR = "com.hayeco.flipspaces"

        @Volatile var current: NotesListener? = null
            private set
    }
}
