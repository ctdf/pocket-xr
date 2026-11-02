package com.hayeco.flipspaces.core

/** Intent flags the Android side must add. Kept as names so core has no Android imports. */
enum class LaunchFlag { NEW_TASK, MULTIPLE_TASK }

/** Everything needed to build the Intent and ActivityOptions for one launch. */
data class LaunchSpec(
    val app: AppEntry,
    val displayId: Int,
    val slot: Slot,
    /** Null when freeform is off: the app then opens full screen on the glasses display. */
    val bounds: Bounds?,
    val flags: Set<LaunchFlag>,
) {
    val windowed: Boolean get() = bounds != null
}

/**
 * Turns "this app, that slot, this size" into a [LaunchSpec] for the chosen display. [insets]
 * keeps windows clear of system bars such as the DeX taskbar.
 */
class LaunchPlanner(private val gridFor: (DisplayCandidate) -> SlotGrid = { SlotGrid.forDensity(it.densityDpi) }) {

    fun plan(
        app: AppEntry,
        display: DisplayCandidate,
        slotIndex: Int,
        size: WindowSize,
        freeformReady: Boolean,
        newInstance: Boolean = false,
        insets: DisplayInsets = DisplayInsets.NONE,
    ): LaunchSpec {
        require(DisplaySelector.isEligible(display)) { "Display ${display.id} is not an external display" }
        val grid = gridFor(display).withInsets(insets)
        val slot = grid.slot(slotIndex)
        val bounds = if (freeformReady) grid.windowBounds(display.widthPx, display.heightPx, slot, size) else null
        val flags = buildSet {
            add(LaunchFlag.NEW_TASK)
            if (newInstance) add(LaunchFlag.MULTIPLE_TASK)
        }
        return LaunchSpec(app, display.id, slot, bounds, flags)
    }
}
