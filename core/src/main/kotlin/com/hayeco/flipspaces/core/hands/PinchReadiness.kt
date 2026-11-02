package com.hayeco.flipspaces.core.hands

/**
 * The per frame confidence gate (0.5.0), a pinch ready flag as far as MediaPipe
 * allows: it has no per joint confidence, so a frame is
 * ready for a pinch when
 *
 * - MediaPipe's handedness score is at least [minScore] (it drops on blurred, half seen hands),
 * - the pinch fingers are inside the picture with [margin] to spare (past the edge MediaPipe
 *   only guesses them), and
 * - the hand's size in the picture did not jump by more than [maxSizeJump] since the last frame
 *   (a tracking glitch, or the landmarks snapping onto the other hand).
 *
 * While a hand is not ready no pinch starts and the pinch value reads 0; a press already down
 * holds through [holdMs] of not ready frames, then ends the way a lost hand does.
 *
 * 0.5.4: one not ready frame alone is skipped by [HandGestureRecognizer] (the cursor and the
 * pinch hold still for it); only a second in a row makes the hand not ready. Mike's 0.5.3 log had
 * real pinches blocked by "hand size jumped 44% in one frame" and by "thumb or index at the edge
 * of the picture". The margin is now 0: a finger MediaPipe puts inside the picture is in it.
 *
 * 0.5.8: the bottom edge. Mike's 0.5.6 two hand log had 32 "thumb or index at the edge of the
 * picture" frames with his hand low in the camera (y 894 to 982 of 1080), and presses that held
 * for 150 ms and then ended. The camera's 51 degree vertical field puts a relaxed hand there, and
 * MediaPipe places a tip it cannot see a little below the last row. A pinch finger up to
 * [bottomSlack] below the picture is accepted when the hand is confident (score at least
 * [edgeScore]) and its wrist and knuckles are inside the picture; the sides and the top stay
 * strict. A press holds through [holdMs] (was 150) of not ready frames, the same 250 ms a lock
 * holds through a lost hand ([PinchLockSettings.lockLostMs]).
 */
data class PinchReadiness(
    val minScore: Float = 0.5f,
    val margin: Float = 0f,
    val maxSizeJump: Float = 0.4f,
    val holdMs: Long = 250,
    val bottomSlack: Float = 0.08f,
    /**
     * 0.5.14: 0.7 (was 0.8), and the sides and the top get [sideSlack]
     * too. 28 of Mike's 65 pinch starts in the 0.5.13 test were "not ready" with a tip at the edge,
     * from hands MediaPipe scored 0.61 to 0.95 (p50 0.88). The palm must still be in the picture.
     */
    val edgeScore: Float = 0.7f,
    val sideSlack: Float = 0.05f,
) {
    /**
     * Null when [hand] is ready; otherwise why not, for the log. [lastSize] is the last good
     * frame's [HandFrame.handSize]. 0.5.4: [skippedSize] is the size of a frame skipped just
     * before this one; a size that agrees with it is a real change (the hand came closer), not a
     * glitch, and is ready.
     */
    fun reason(hand: HandFrame, lastSize: Float?, skippedSize: Float? = null): String? = when {
        hand.score < minScore -> "hand score %.2f below %.2f".fmt(hand.score, minScore)
        !hand.pinchFingersInFrame(margin) && !lowButConfident(hand) -> "thumb or index at the edge of the picture"
        jumped(hand.handSize, lastSize) && (skippedSize == null || jumped(hand.handSize, skippedSize)) ->
            "hand size jumped %.0f%% in one frame".fmt(kotlin.math.abs(hand.handSize / lastSize!! - 1f) * 100f)
        else -> null
    }

    /**
     * 0.5.8: only the bottom edge is crossed, by at most [bottomSlack], by a confident hand whose
     * palm is in the picture. 0.5.14: the sides and the top by at most [sideSlack].
     */
    fun lowButConfident(hand: HandFrame): Boolean =
        bottomSlack > 0f && hand.score >= edgeScore && hand.palmInFrame() &&
            HandFrame.PINCH_FINGERS.all { val p = hand[it]; p.x in (margin - sideSlack)..(1f - margin + sideSlack) && p.y >= margin - sideSlack && p.y <= 1f + bottomSlack }

    private fun jumped(size: Float, before: Float?): Boolean =
        before != null && before > 1e-4f && kotlin.math.abs(size / before - 1f) > maxSizeJump

    companion object {
        /** Lets every frame through, as before 0.5.0. */
        val OFF = PinchReadiness(minScore = 0f, margin = -10f, maxSizeJump = Float.MAX_VALUE, bottomSlack = 0f)

        /** 0.5.13's gate (the sides and top strict, edge score 0.8), for the before and after tests. */
        val V0513 = PinchReadiness(edgeScore = 0.8f, sideSlack = 0f)
    }
}

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)
