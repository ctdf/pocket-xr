package com.hayeco.flipspaces.core.hands

/**
 * Pinch on and off with hysteresis: a pinch starts when the thumb to index ratio drops below
 * [enterRatio] for [enterFrames] frames in a row and ends only when it rises above [exitRatio]
 * for [exitFrames] frames. The gap between the two thresholds stops jitter from clicking.
 *
 * Tuned in 0.2.1: fingers that clearly touch (below [fastEnterRatio]) start the pinch on the
 * first frame, which takes a frame (33 ms) off every click, while a light touch near the
 * threshold still needs two frames. The exit threshold moved out to 0.42 so a drag does not
 * drop when the fingers loosen a little; a single frame spike never releases.
 */
class PinchDetector(
    enterRatio: Float = 0.25f,
    exitRatio: Float = 0.42f,
    val enterFrames: Int = 2,
    val exitFrames: Int = 2,
    fastEnterRatio: Float = 0.15f,
    /**
     * Since 0.3.6: also release once the ratio has risen this much above the tightest point of
     * the pinch (and is clear of [enterRatio]), for hands whose fingers never open far. Null
     * keeps the plain [exitRatio] rule.
     */
    val releaseRise: Float? = null,
    /**
     * 0.5.0: release at once, on one frame, above this ratio (Meta's PINCH_DISTANCE_STOP_MAX,
     * 10 cm, scaled to MediaPipe's compressed range). Null keeps the frame count rule only.
     */
    val hardRelease: Float? = null,
    /**
     * 0.5.0, the fast release: once the ratio has risen on [fastReleaseFrames] frames in a row
     * and is this far above the tightest point of the pinch (and clear of [enterRatio]), the
     * pinch ends on that frame. Mike's 0.4.8 log had taps released 1.8 to 1.9 s late; fingers
     * that are clearly opening no longer wait for the fixed release threshold. Null turns it off.
     */
    val fastReleaseRise: Float? = null,
    val fastReleaseFrames: Int = 2,
    /**
     * 0.5.4: frames in a row above [hardRelease] before it releases. 0.5.0 released on one, so a
     * single bad frame (MediaPipe's fingers snapping apart for 33 ms) ended a press or a drag.
     */
    val hardReleaseFrames: Int = 1,
) {
    var enterRatio: Float = enterRatio
        private set
    var exitRatio: Float = exitRatio
        private set
    var fastEnterRatio: Float = fastEnterRatio
        private set

    init {
        require(enterRatio < exitRatio) { "Enter ratio must be below exit ratio" }
        require(fastEnterRatio <= enterRatio) { "Fast enter ratio must not be above the enter ratio" }
        require(enterFrames >= 1 && exitFrames >= 1 && hardReleaseFrames >= 1) { "Frame counts must be at least 1" }
    }

    /** 0.5.4: frames in a row above [hardRelease]. */
    private var hardStreak = 0

    var pinched: Boolean = false
        private set

    /** The smallest ratio since the pinch started (or since the last [reset]). */
    var tightest: Float = Float.MAX_VALUE
        private set

    private var streak = 0

    /** Frames in a row the ratio rose while pinched, and the ratio before this one. */
    private var rising = 0
    private var last = Float.NaN

    /** Why the last release happened, for the log: "threshold", "hard" or "opening fast". */
    var releasedBy: String = ""
        private set

    /** Feeds one ratio and returns whether the hand is pinching after it. */
    fun update(ratio: Float): Boolean {
        val previous = last
        last = ratio
        if (!pinched && ratio < fastEnterRatio) {
            press(ratio)
            return true
        }
        if (pinched) {
            rising = if (!previous.isNaN() && ratio > previous) rising + 1 else 0
            val hard = hardRelease
            val fast = fastReleaseRise
            hardStreak = if (hard != null && ratio > hard) hardStreak + 1 else 0
            val why = when {
                hard != null && hardStreak >= hardReleaseFrames -> "hard"
                fast != null && rising >= fastReleaseFrames && ratio - tightest >= fast && ratio > enterRatio -> "opening fast"
                else -> null
            }
            if (why != null) {
                release(why)
                return false
            }
        }
        if (pinched) tightest = minOf(tightest, ratio)
        val crossing = if (pinched) releasing(ratio) else ratio < enterRatio
        streak = if (crossing) streak + 1 else 0
        if (streak >= (if (pinched) exitFrames else enterFrames)) {
            if (pinched) release("threshold") else press(ratio)
        }
        return pinched
    }

    private fun releasing(ratio: Float): Boolean {
        if (ratio > exitRatio) return true
        val rise = releaseRise ?: return false
        return ratio - tightest >= rise && ratio > enterRatio
    }

    /** Starts the pinch now, for a press decided outside the thresholds (a pinch lock). */
    fun press(ratio: Float) {
        pinched = true
        streak = 0
        rising = 0
        hardStreak = 0
        tightest = ratio
    }

    /**
     * 0.5.5: takes back a press [update] made on this frame, for a hand with no aim lock yet: the
     * recognizer locks the aim first and presses on a later frame.
     */
    fun unpress() {
        pinched = false
        streak = 0
        rising = 0
        hardStreak = 0
        tightest = Float.MAX_VALUE
    }

    /** Ends the pinch now, for a release decided outside the ratio (the confidence gate, the picture). */
    fun release(why: String) {
        pinched = false
        streak = 0
        rising = 0
        hardStreak = 0
        releasedBy = why
    }

    /**
     * New press and release thresholds (see [PinchCalibration]). The fast press stays at or
     * below the press threshold.
     */
    fun retune(enter: Float, exit: Float) {
        require(enter < exit) { "Enter ratio must be below exit ratio" }
        enterRatio = enter
        exitRatio = exit
        fastEnterRatio = minOf(fastEnterRatio, enter)
    }

    fun reset() {
        pinched = false
        streak = 0
        rising = 0
        hardStreak = 0
        last = Float.NaN
        tightest = Float.MAX_VALUE
    }

    companion object {
        /**
         * The detector the hand recognizer uses since 0.3.6, before [PinchCalibration] has seen
         * any pinches: Mike's 0.3.5 log had natural pinches bottoming at 0.32 to 0.38 (world
         * ratio) that never reached the old 0.25, and fingers that reopened only to 0.45 to 0.49.
         */
        fun natural() = PinchDetector(enterRatio = 0.38f, exitRatio = 0.46f, fastEnterRatio = 0.15f, releaseRise = 0.1f)

        /**
         * 0.5.0's hand pinch: [natural] plus Meta's two release rules scaled to MediaPipe. A hard
         * release above 0.6 (Meta: 10 cm, about 1.2 of the 8.5 cm bone, but MediaPipe's open
         * hands read only 0.53 to 0.65 in Mike's logs) and the fast release: two rising frames
         * 0.08 above the tightest point (Meta releases at min + 1.6 cm, about 0.19 of the bone;
         * MediaPipe compresses that by about half). 0.5.4: the hard release needs two frames,
         * like every other release, so no single bad frame ends a pinch.
         */
        fun quest() = PinchDetector(
            enterRatio = 0.38f,
            exitRatio = 0.46f,
            fastEnterRatio = 0.15f,
            releaseRise = 0.1f,
            hardRelease = 0.6f,
            fastReleaseRise = 0.08f,
            hardReleaseFrames = 2,
        )
    }
}

/**
 * Learns one hand's pinch from its own movements (0.3.6): [open] is the ratio the fingers close
 * from (the "from" of each closing onset), [closed] the tightest ratio of each finished press,
 * both averaged with weight [weight]. The press threshold sits [pressShare] of the way from
 * closed to open and the release threshold [releaseShare] of the way, at least [minGap] above
 * the press, all kept within the limits below so a bad sample cannot make clicks impossible or
 * constant.
 */
class PinchCalibration(
    initialOpen: Float = 0.6f,
    initialClosed: Float = 0.3f,
    val weight: Float = 0.3f,
    val pressShare: Float = 0.5f,
    val releaseShare: Float = 0.6f,
    /** 0.5.4: 0.08 (was 0.06), a clear gap between press and release for MediaPipe's frame to frame noise. */
    val minGap: Float = 0.08f,
    val minPress: Float = 0.22f,
    /**
     * Kept at 0.42 in 0.5.0 (the lock's onset is at 0.45); Mike's 0.4.8 presses that bottomed at
     * 0.40 to 0.41 now read lower on the thumb bone measure ([HandFrame.pinchDistance]).
     */
    val maxPress: Float = 0.42f,
    val maxRelease: Float = 0.55f,
) {
    var open: Float = initialOpen
        private set
    var closed: Float = initialClosed
        private set

    /** The fingers started closing from [ratio]. */
    fun noteOpen(ratio: Float) {
        open = (open + weight * (ratio.coerceIn(0.3f, 1.2f) - open))
    }

    /** A press ended; its tightest ratio was [ratio]. */
    fun noteClosed(ratio: Float) {
        if (!ratio.isFinite()) return
        closed = (closed + weight * (ratio.coerceIn(0.03f, 0.45f) - closed))
    }

    val pressRatio: Float
        get() {
            val span = (open - closed).coerceAtLeast(0.05f)
            // Never closer than 0.08 to the open hand, or resting fingers would click.
            return (closed + pressShare * span).coerceAtMost(open - 0.08f).coerceIn(minPress, maxPress)
        }

    val releaseRatio: Float
        get() {
            val press = pressRatio
            val span = (open - closed).coerceAtLeast(0.05f)
            return maxOf(press + minGap, closed + releaseShare * span).coerceAtMost(maxRelease).coerceAtLeast(press + 0.02f)
        }

    /**
     * 0.5.0, OpenXR's `pinch_ext/value`: 0 with the fingers at this hand's open ratio, 1 at its
     * press ratio, linear in between, so the cursor starts filling as soon as they close.
     */
    fun value(ratio: Float): Float {
        val press = pressRatio
        val span = (open - press).coerceAtLeast(0.05f)
        return (1f - (ratio - press) / span).coerceIn(0f, 1f)
    }

    /** Applies the current thresholds to [detector]. */
    fun tune(detector: PinchDetector) = detector.retune(pressRatio, releaseRatio)
}
