package com.hayeco.flipspaces.addon

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.Point

/**
 * 1.1.0: the link Pocket XR binds ([IAddon]), guarded by the ADDON_LINK signature permission in the manifest. Calls
 * reach [PointerAccess] and [NotesListener] while they are on; the cursor window and touch calls run on the main
 * thread, in the order they came. What changes goes back through the one callback Pocket XR set.
 */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IAddon.Stub() {
        override fun version() = VERSION

        override fun setCallback(cb: IAddonCallback?) {
            callback = cb
            cb?.asBinder()?.linkToDeath({ if (callback === cb) callback = null }, 0)
            Log.i(TAG, "Pocket XR linked")
            send { it.pointer(PointerAccess.current != null) }
            send { it.notesState(NotesListener.current != null) }
            NotesListener.current?.let { l -> send { it.notes(l.rows()) } }
        }

        override fun pointerOn() = PointerAccess.current != null

        override fun notesOn() = NotesListener.current != null

        override fun overlayOn(displayId: Int) = onPointer { it.overlayOn(displayId) }

        override fun cursor(x: Float, y: Float, zone: Int, pressed: Boolean) = onPointer { it.cursor(x, y, zone, pressed) }

        override fun preview(shown: Boolean, left: Int, top: Int, right: Int, bottom: Int) =
            onPointer { it.preview(if (shown) Bounds(left, top, right, bottom) else null) }

        override fun hand(known: Boolean, present: Boolean, pinchProgress: Float, openPalm: Boolean, source: String?) =
            onPointer { it.hand(if (known) CursorOverlayView.Hand(present, pinchProgress, openPalm) else null, source.orEmpty()) }

        override fun flash(message: String?, millis: Long) = onPointer { it.flash(message.orEmpty(), millis) }

        override fun dock(items: Bundle?) = onPointer { it.dock(items ?: Bundle()) }

        override fun spaceUp(up: Boolean) = onPointer { it.spaceUp(up) }

        override fun tap(displayId: Int, x: Float, y: Float) = onPointer { it.tap(displayId, Point(x, y)) }

        override fun dragStart(displayId: Int, x: Float, y: Float) = onPointer { it.dragStart(displayId, Point(x, y)) }

        override fun dragTo(x: Float, y: Float) = onPointer { it.dragTo(Point(x, y)) }

        override fun dragEnd(x: Float, y: Float) = onPointer { it.dragEnd(Point(x, y)) }

        override fun drag(displayId: Int, fromX: Float, fromY: Float, toX: Float, toY: Float) =
            onPointer { it.drag(displayId, Point(fromX, fromY), Point(toX, toY)) }

        override fun back() = PointerAccess.current?.back() ?: false

        override fun windows(displayId: Int): Bundle = PointerAccess.current?.windows(displayId) ?: Bundle()

        override fun pressDexFullScreen(displayId: Int): String =
            PointerAccess.current?.pressDexFullScreen(displayId) ?: "not pressed: the add-on's pointer service is off"

        override fun dismissNote(key: String?) {
            key ?: return
            runCatching { NotesListener.current?.cancelNotification(key) }
        }

        override fun clearNotes() {
            runCatching { NotesListener.current?.cancelAllNotifications() }
        }

        override fun playingPackages(): Array<String>? {
            val l = NotesListener.current ?: return null
            return runCatching { l.playingPackages() }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "PocketXrAddonLink"

        /** [IAddon.version]: raised when a call is added to the link. */
        const val VERSION = 1

        private val main = Handler(Looper.getMainLooper())

        @Volatile private var callback: IAddonCallback? = null

        private fun onPointer(block: (PointerAccess) -> Unit) {
            main.post { PointerAccess.current?.let(block) }
        }

        private fun send(block: (IAddonCallback) -> Unit) {
            val cb = callback ?: return
            try {
                block(cb)
            } catch (e: RemoteException) {
                Log.i(TAG, "Pocket XR went away: ${e.message}")
                if (callback === cb) callback = null
            }
        }

        fun pointerChanged(on: Boolean) = send { it.pointer(on) }

        fun problem(message: String?) = send { it.problem(message) }

        fun notesChanged(on: Boolean) = send { it.notesState(on) }

        fun notes(rows: Bundle) = send { it.notes(rows) }
    }
}
