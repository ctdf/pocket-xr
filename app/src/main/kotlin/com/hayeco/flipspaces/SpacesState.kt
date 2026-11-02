package com.hayeco.flipspaces

import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.DisplayInsets
import com.hayeco.flipspaces.core.LaunchDenial
import com.hayeco.flipspaces.core.SlotGrid
import com.hayeco.flipspaces.core.WindowSize
import com.hayeco.flipspaces.core.spatial.FlatBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Launch choices shared by the phone screen and the glasses home, kept for the life of the
 * process. M1 cannot see when another app's window closes, so slots are only freed by the user.
 */
object SpacesState {
    val grid = SlotGrid()

    data class Choices(
        val selectedSlot: Int = 0,
        val size: WindowSize = WindowSize.STANDARD,
        val newInstance: Boolean = false,
        val autoAdvance: Boolean = true,
        val occupied: Map<Int, AppEntry> = emptyMap(),
        val preferredDisplayId: Int? = null,
    )

    /** What the launch path has learned about the glasses display. */
    data class Hosting(
        /** Display the glasses home activity is running on, or null. */
        val homeDisplayId: Int? = null,
        /** Android refused our own home activity on a display: the mirror mode signal. */
        val denial: LaunchDenial? = null,
        /** System bar space the home activity measured on its display (the DeX taskbar). */
        val insets: Map<Int, DisplayInsets> = emptyMap(),
    )

    /** An app tapped on the phone while the home activity was still starting on the glasses. */
    data class PendingLaunch(val app: AppEntry, val displayId: Int, val atMillis: Long)

    private val _choices = MutableStateFlow(Choices())
    val choices: StateFlow<Choices> = _choices.asStateFlow()

    private val _hosting = MutableStateFlow(Hosting())
    val hosting: StateFlow<Hosting> = _hosting.asStateFlow()

    /** Results that arrive after the tap returned (from the glasses home or Shizuku), for the phone snackbar. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    @Volatile
    private var pending: PendingLaunch? = null

    /** Why the glasses show the flat DeX home instead of the Space, for both screens; null otherwise. */
    private val _banner = MutableStateFlow<FlatBanner?>(null)
    val banner: StateFlow<FlatBanner?> = _banner.asStateFlow()

    fun setBanner(banner: FlatBanner?) {
        _banner.value = banner
    }

    fun selectSlot(index: Int) = _choices.update { it.copy(selectedSlot = index) }
    fun selectSize(size: WindowSize) = _choices.update { it.copy(size = size) }
    fun setNewInstance(on: Boolean) = _choices.update { it.copy(newInstance = on) }
    fun setAutoAdvance(on: Boolean) = _choices.update { it.copy(autoAdvance = on) }
    fun preferDisplay(id: Int) = _choices.update { it.copy(preferredDisplayId = id) }
    fun clearSlots() = _choices.update { it.copy(occupied = emptyMap(), selectedSlot = 0) }

    /** Records [app] in [slot] and, if auto advance is on, moves the selection to the next free slot. */
    fun placed(slot: Int, app: AppEntry) = _choices.update { current ->
        val occupied = current.occupied + (slot to app)
        val next = if (current.autoAdvance) grid.nextFreeSlot(occupied.keys)?.index ?: slot else slot
        current.copy(occupied = occupied, selectedSlot = next)
    }

    fun homeStarted(displayId: Int) = _hosting.update {
        it.copy(homeDisplayId = displayId, denial = it.denial?.takeIf { d -> d.displayId != displayId })
    }

    fun homeStopped(displayId: Int) = _hosting.update {
        if (it.homeDisplayId == displayId) it.copy(homeDisplayId = null) else it
    }

    fun recordDenial(denial: LaunchDenial) = _hosting.update { it.copy(denial = denial) }
    fun clearDenial() = _hosting.update { it.copy(denial = null) }
    fun reportInsets(displayId: Int, insets: DisplayInsets) = _hosting.update { it.copy(insets = it.insets + (displayId to insets)) }

    fun post(message: String) {
        _messages.value = message
    }

    fun messageShown() {
        _messages.value = null
    }

    fun queue(launch: PendingLaunch) {
        pending = launch
    }

    /** The queued launch for [displayId] if it is fresh (the home took under 15 seconds to come up). */
    fun takePending(displayId: Int, nowMillis: Long): PendingLaunch? {
        val p = pending ?: return null
        pending = null
        return p.takeIf { it.displayId == displayId && nowMillis - it.atMillis < 15_000 }
    }
}
