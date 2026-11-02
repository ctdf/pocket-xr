package com.hayeco.flipspaces.core.hands

import kotlin.math.sqrt

/**
 * Whether an open hand shows its palm to the glasses camera (0.4.1), so the launcher's open
 * palm only opens for a palm turned up towards the glasses, not for a hand that happens to be
 * open with its back to the camera (resting on a desk, waving at someone).
 *
 * A right hand and a left hand are mirror images, so which way the palm faces follows from
 * which hand it is and the turn from the index knuckle to the pinky knuckle around the wrist
 * in the picture. MediaPipe names the hand as if the picture were mirrored (a selfie camera);
 * with that naming a "Right" hand shows its palm when the turn is clockwise on screen (y down,
 * index knuckle on the left of the pinky's with the fingers up) and a "Left" hand when it is
 * counter clockwise. The glasses camera is not mirrored, so MediaPipe calls Mike's right hand
 * "Left"; the rule holds either way because it only uses MediaPipe's own naming.
 *
 * [minOpen] is the sine of the angle between wrist to index knuckle and wrist to pinky
 * knuckle: a hand seen edge on has its knuckles in a line and does not count either way.
 */
object PalmFacing {
    const val MIN_OPEN = 0.2f

    /** Signed turn from index knuckle to pinky knuckle around the wrist, over both lengths: -1..1. */
    fun turn(hand: HandFrame): Float {
        val w = hand[HandFrame.WRIST]
        val i = hand[HandFrame.INDEX_MCP]
        val p = hand[HandFrame.PINKY_MCP]
        val ax = i.x - w.x
        val ay = i.y - w.y
        val bx = p.x - w.x
        val by = p.y - w.y
        val len = sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by)
        if (len < 1e-9f) return 0f
        return (ax * by - ay * bx) / len
    }

    /**
     * True when the palm faces the camera. A hand MediaPipe could not name ([HandFrame.rightHand]
     * null) is taken as facing, as before 0.4.1, since the picture alone cannot tell.
     */
    fun towardCamera(hand: HandFrame, minOpen: Float = MIN_OPEN): Boolean {
        val right = hand.rightHand ?: return true
        val t = turn(hand)
        return if (right) t > minOpen else t < -minOpen
    }
}
