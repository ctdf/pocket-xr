package com.hayeco.flipspaces.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.hayeco.flipspaces.core.spatial.NoteRow
import com.hayeco.flipspaces.platform.ShizukuBridge

/**
 * 0.5.46 (Mike: "a Notifications button so users can see their phone notifications in an extremely well designed way"):
 * the phone's notifications for the Space's Notifications page. Android hands them to [Listener] once notification access
 * is on; the page asks for it with one press, which turns it on through Shizuku (`cmd notification allow_listener`).
 */
object PhoneNotifications {
    private const val TAG = "FlipSpacesNotes"

    @Volatile private var listener: Listener? = null
    @Volatile var rows: List<NoteRow> = emptyList()
        private set

    /** Bumped on every change, for the page to paint again. */
    @Volatile var version = 0L
        private set

    private val icons = HashMap<String, Bitmap?>()

    fun component(context: Context) = ComponentName(context, Listener::class.java)

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Blocking (Shizuku): turns notification access on for Pocket XR; null when it worked, else why not. */
    fun grant(context: Context): String? {
        val r = ShizukuBridge.shell(listOf("cmd", "notification", "allow_listener", component(context).flattenToString()))
        Log.i(TAG, "notification access asked for through Shizuku: exit ${r.exitCode} ${r.output.take(200)}")
        return if (r.exitCode == 0) null else r.output.ifBlank { "Shizuku is not running" }
    }

    /** Opens [key]'s notification as the app would (its content intent); false when it has none. */
    fun open(key: String): Boolean {
        val l = listener ?: return false
        val sbn = runCatching { l.activeNotifications?.firstOrNull { it.key == key } }.getOrNull() ?: return false
        return runCatching { sbn.notification.contentIntent?.send(); true }.getOrDefault(false)
    }

    fun packageOf(key: String): String? = rows.firstOrNull { it.key == key }?.packageName

    fun dismiss(key: String) {
        runCatching { listener?.cancelNotification(key) }
    }

    fun clearAll() {
        runCatching { listener?.cancelAllNotifications() }
    }

    /** The app's icon, round, 96 px, cached (main or GL thread). */
    fun icon(context: Context, pkg: String): Bitmap? = synchronized(icons) {
        icons.getOrPut(pkg) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(pkg)
                val size = 96
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                c.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(layer))
                c.drawBitmap(layer, 0f, 0f, p)
                out
            }.getOrNull()
        }
    }

    private fun refresh(l: Listener) {
        val active = runCatching { l.activeNotifications?.toList() }.getOrNull().orEmpty()
        val pm = l.packageManager
        rows = active
            .filter { !it.isOngoing || it.notification.category == Notification.CATEGORY_CALL }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { it.packageName != l.packageName }
            .mapNotNull { sbn -> row(sbn, pm) }
            .sortedByDescending { it.postedMs }
        version++
    }

    private fun row(sbn: StatusBarNotification, pm: android.content.pm.PackageManager): NoteRow? {
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        return NoteRow(sbn.key, app, sbn.packageName, title, text, sbn.postTime, sbn.isClearable)
    }

    /** Android's side: notification access's listener. */
    class Listener : NotificationListenerService() {
        override fun onListenerConnected() {
            listener = this
            refresh(this)
            Log.i(TAG, "notification listener connected: ${rows.size} notifications")
        }

        override fun onListenerDisconnected() {
            if (listener === this) listener = null
        }

        override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh(this)

        override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh(this)
    }
}
