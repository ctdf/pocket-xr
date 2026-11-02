package com.hayeco.flipspaces.core.hands

/**
 * 0.5.8: which MediaPipe hand landmarker gets the next frame in two hand mode.
 *
 * MediaPipe runs its palm detector on every frame while fewer than numHands hands are tracked.
 * With "Track two hands" on and one hand in view (most of the time), that is the palm detector
 * plus the landmark model on every frame: Mike's 0.5.6 log had tracking at 15 fps, about 30
 * frames dropped a second. So two landmarkers run: [Use.ONE] follows one hand (palm detector off
 * while it tracks, like one hand mode), [Use.TWO] follows two. While fewer than two hands are
 * tracked, ONE gets the frames and TWO gets one frame in [searchEvery], which looks for the
 * second hand. Once TWO sees two hands it gets every frame (both followed, palm detector off),
 * until it sees fewer than two for [dropAfter] frames in a row.
 */
class PalmSearch(val searchEvery: Int = 4, val dropAfter: Int = 2) {
    enum class Use { ONE, TWO }

    init {
        require(searchEvery >= 1 && dropAfter >= 1) { "Frame counts must be at least 1" }
    }

    /** TWO follows two hands. */
    var both = false
        private set

    private var sinceSearch = 0
    private var fewer = 0

    /** Palm detector frames handed to TWO while fewer than two hands were tracked, for the log. */
    var searches = 0L
        private set

    /** The landmarker for the next frame. */
    fun next(): Use {
        if (both) return Use.TWO
        sinceSearch++
        if (sinceSearch >= searchEvery) {
            sinceSearch = 0
            searches++
            return Use.TWO
        }
        return Use.ONE
    }

    /** [used]'s result had [hands] hands. True when [both] changed. */
    fun result(used: Use, hands: Int): Boolean {
        val was = both
        if (used == Use.TWO) {
            if (hands >= 2) {
                both = true
                fewer = 0
            } else if (both && ++fewer >= dropAfter) {
                both = false
                fewer = 0
                sinceSearch = 0
            }
        }
        return both != was
    }

    fun reset() {
        both = false
        sinceSearch = 0
        fewer = 0
    }
}
