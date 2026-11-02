package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.17 cover screen: one of the phone's own screens as Android lists it (the glasses, presentation
 * displays and Pocket XR's panel displays left out by the caller).
 */
data class PhoneScreen(val displayId: Int, val widthPx: Int, val heightPx: Int, val densityDpi: Int, val on: Boolean) {
    val widthDp: Float get() = widthPx * 160f / densityDpi.coerceAtLeast(1)
    val heightDp: Float get() = heightPx * 160f / densityDpi.coerceAtLeast(1)
}

/**
 * 0.5.18: which of Android's displays are the phone's own screens. The
 * Flip7's Flex Window is display 1, "Built-in Screen", type INTERNAL, with FLAG_PRESENTATION;
 * 0.5.17 left out every presentation display, so the cover was never found ("cover display not
 * found" in every reading), a closed phone read UNKNOWN instead of FOLDED, and neither plugging in
 * closed nor folding brought the trackpad to the cover. Now a built-in screen is kept whatever its
 * presentation flag; the glasses (EXTERNAL), Pocket XR's panels and private displays are not.
 */
object PhoneScreenFilter {
    const val TYPE_INTERNAL = 1
    const val FLAG_PRIVATE = 1 shl 2
    const val FLAG_PRESENTATION = 1 shl 3

    /** One display as DisplayManager lists it; [type] is Android's hidden display type, null when it cannot be read. */
    data class Listed(val displayId: Int, val flags: Int, val type: Int?, val isGlasses: Boolean, val isOwnPanel: Boolean)

    /** True for one of the phone's own screens (the main screen or the cover). */
    fun keep(d: Listed, defaultDisplayId: Int = 0, presentationCover: Boolean = true): Boolean {
        if (d.isGlasses || d.isOwnPanel || d.flags and FLAG_PRIVATE != 0) return false
        // Without the type, only the default display is surely built in (0.5.17's rule).
        val builtIn = if (d.type != null) d.type == TYPE_INTERNAL else d.displayId == defaultDisplayId
        if (!builtIn) return false
        // 0.5.17 dropped presentation displays; a built-in one is the Flip7's cover. False is 0.5.17.
        return presentationCover || d.flags and FLAG_PRESENTATION == 0
    }
}

/** Open (the main screen in use), folded (the cover screen in use), or not known (every screen off). */
enum class Posture { OPEN, FOLDED, UNKNOWN }

/** The posture, the screen in use and the cover screen's display, when known. */
data class PostureReading(val posture: Posture, val inUseDisplayId: Int?, val coverDisplayId: Int?) {
    /** The same screen in use, whatever else changed. */
    fun sameUse(o: PostureReading?): Boolean = o != null && posture == o.posture && inUseDisplayId == o.inUseDisplayId
}

/**
 * 0.5.17 cover screen: which screen of the Flip is in use, from the phone's screens alone. Two
 * models are covered, as nothing public says which the Flip7 uses: the cover screen as a display
 * of its own (the main one goes off when folded), or one display that changes size when folded.
 * A screen is the cover when it is small and nearly square ([isCoverSize]: the Flex Window is 399 x
 * 441 dp, the main screen 360 x 840).
 */
object FoldPosture {
    const val COVER_MAX_LONG_DP = 560f
    const val COVER_MAX_RATIO = 1.4f

    fun isCoverSize(widthDp: Float, heightDp: Float): Boolean {
        val long = maxOf(widthDp, heightDp)
        val short = minOf(widthDp, heightDp).coerceAtLeast(1f)
        return long < COVER_MAX_LONG_DP && long / short < COVER_MAX_RATIO
    }

    fun read(screens: List<PhoneScreen>): PostureReading {
        val cover = screens.filter { isCoverSize(it.widthDp, it.heightDp) }
        val main = screens.filterNot { isCoverSize(it.widthDp, it.heightDp) }
        val mainOn = main.firstOrNull { it.on }
        val coverOn = cover.firstOrNull { it.on }
        return when {
            mainOn != null -> PostureReading(Posture.OPEN, mainOn.displayId, cover.firstOrNull()?.displayId)
            coverOn != null -> PostureReading(Posture.FOLDED, coverOn.displayId, coverOn.displayId)
            else -> PostureReading(Posture.UNKNOWN, null, cover.firstOrNull()?.displayId)
        }
    }
}

/** Where the phone's trackpad is now: its display and whether it is in front (resumed). */
data class TrackpadPlace(val displayId: Int, val inFront: Boolean)

/** What to do with the phone's trackpad. Every step but [Keep] cancels the gesture under way. */
sealed interface Handover {
    val why: String

    /** Start the trackpad on [displayId] ([cover]: its cover layout); first close the one on [replaceOn]. */
    data class Show(val displayId: Int, val cover: Boolean, val replaceOn: Int?, override val why: String) : Handover

    /** The trackpad stays where it is; its layout follows the screen (cover or main). */
    data class Relayout(val cover: Boolean, override val why: String) : Handover

    data class Keep(override val why: String) : Handover
}

/**
 * 0.5.17 cover screen: the Space session across a fold or an unfold. The Space on the glasses is
 * never closed here (there is no step for it): only the phone's trackpad moves, to whichever screen
 * is in use.
 *
 * - The Space starting with the phone closed (the glasses plugged in folded) puts the trackpad on
 *   the cover screen at once.
 * - A posture change counts once it has held [settleMs] (a half fold passes through both).
 * - Folded, the cover trackpad is the only way to drive the Space, so folding always brings it,
 *   even if it had been closed on the main screen. Unfolded, it moves to the main screen only if it
 *   was wanted (open, not left by the user).
 * - Every screen off (the power key) moves nothing.
 */
class FoldHandover(private val settleMs: Long = SETTLE_MS) {
    /** The session wants the trackpad on the phone. */
    var wanted = false
        private set

    /** A Space session is on; nothing here ends it. */
    var sessionActive = false
        private set

    private var settled: PostureReading? = null
    private var pending: PostureReading? = null
    private var pendingSince = 0L

    fun spaceStarted(r: PostureReading, pad: TrackpadPlace?, nowMs: Long): Handover {
        sessionActive = true
        wanted = pad != null
        settled = r
        pending = null
        pendingSince = nowMs
        return decide(r, pad, "the Space started")
    }

    fun spaceEnded() {
        sessionActive = false
        wanted = false
    }

    /** The trackpad was opened on the phone (by the user, or by a step here). */
    fun trackpadOpened() {
        wanted = true
    }

    /** The user left the trackpad (Back, Return to Home Screen): it is not brought back unfolded. */
    fun trackpadLeftByUser() {
        wanted = false
    }

    /** The screens changed; [step] decides once the new reading has held. */
    fun observe(r: PostureReading, nowMs: Long) {
        if (r.sameUse(settled)) {
            pending = null
            return
        }
        if (!r.sameUse(pending)) {
            pending = r
            pendingSince = nowMs
        }
    }

    /** The step for a posture that has now held [settleMs], once; null meanwhile. */
    fun step(pad: TrackpadPlace?, nowMs: Long): Handover? {
        val p = pending ?: return null
        if (nowMs - pendingSince < settleMs) return null
        pending = null
        settled = p
        return decide(p, pad, if (p.posture == Posture.FOLDED) "folded" else if (p.posture == Posture.OPEN) "unfolded" else "screens off")
    }

    private fun decide(r: PostureReading, pad: TrackpadPlace?, why: String): Handover {
        if (!sessionActive) return Handover.Keep("$why: no Space session")
        val inUse = r.inUseDisplayId
        if (r.posture == Posture.UNKNOWN || inUse == null) return Handover.Keep("$why: every phone screen is off; the Space and the trackpad stay as they are")
        val cover = r.posture == Posture.FOLDED
        if (cover) wanted = true
        if (!wanted) return Handover.Keep("$why: the trackpad was not open, so it is not opened on the main screen")
        return when {
            pad == null -> Handover.Show(inUse, cover, null, "$why: the trackpad opens on the ${if (cover) "cover" else "main"} screen (display $inUse)")
            pad.displayId == inUse && pad.inFront -> Handover.Relayout(cover, "$why: the trackpad stays on display $inUse with the ${if (cover) "cover" else "main"} layout")
            pad.displayId == inUse -> Handover.Show(inUse, cover, null, "$why: the trackpad is brought back in front on display $inUse")
            else -> Handover.Show(inUse, cover, pad.displayId, "$why: the trackpad moves from display ${pad.displayId} to display $inUse")
        }
    }

    companion object {
        const val SETTLE_MS = 350L
    }
}
