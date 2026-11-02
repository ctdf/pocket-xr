package com.hayeco.flipspaces.pointer

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.spatial.SpaceSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where every input provider sends its pointer signals. It hands them, on the main thread, to
 * the Pocket XR pointer accessibility service, which owns the cursor, the overlay and the
 * touch injection on the glasses. Without the service nothing reaches the glasses, and the
 * setup screen says so.
 */
object PointerHub {
    private val main = Handler(Looper.getMainLooper())
    private var service: PointerService? = null

    private val _connected = MutableStateFlow(false)
    /** The accessibility service is enabled and bound. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _displayId = MutableStateFlow<Int?>(null)
    /** The glasses display the cursor is on, or null when there is none. */
    val displayId: StateFlow<Int?> = _displayId.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** The last thing that went wrong with injection or the overlay, word for word. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun submit(signal: PointerSignal, source: HandProviderKind) {
        // While the Space is on the glasses, the pointer is its 3D ray, not the DeX cursor.
        if (SpaceSession.active) {
            SpaceSession.submit(signal)
            return
        }
        main.post {
            val s = service
            if (s == null) {
                if (signal is PointerSignal.Down || (signal as? PointerSignal.ForHand)?.signal is PointerSignal.Down) report("Turn on the Pocket XR pointer accessibility service to click on the glasses.")
                return@post
            }
            s.onSignal(signal, source)
        }
    }

    fun handStatus(status: HandStatus?, source: HandProviderKind) {
        main.post { service?.onHandStatus(status, source) }
    }

    internal fun attach(s: PointerService) {
        service = s
        _connected.value = true
    }

    internal fun detach(s: PointerService) {
        if (service === s) {
            service = null
            _connected.value = false
            _displayId.value = null
        }
    }

    /**
     * 0.5.9: presses DeX's own full screen button on the Space's window on [displayId]
     * ([PointerService.pressDexFullScreen]); [done] gets what happened, on the main thread.
     */
    /** 0.5.10: the words of the reply when the service is off, which the phone prompt looks for. */
    const val SERVICE_OFF = "the Pocket XR pointer accessibility service is off"

    fun pressDexFullScreen(displayId: Int, done: (String) -> Unit) {
        main.post {
            val s = service
            done(s?.pressDexFullScreen(displayId) ?: "not pressed: $SERVICE_OFF, so nothing may press DeX's buttons; turn it on in Settings, Accessibility")
        }
    }

    internal fun displayChanged(id: Int?) {
        _displayId.value = id
    }

    internal fun report(problem: String?) {
        _problem.value = problem
    }

    /** Whether the user has the service switched on in Settings (it may not be bound yet). */
    fun enabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, PointerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
