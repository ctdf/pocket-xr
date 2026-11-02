package com.hayeco.flipspaces.core.hands

/** Coarse hand shapes the gesture layer cares about. */
enum class HandPose { OPEN_PALM, POINT, PINCH, FIST, OTHER }

/**
 * Classifies a hand from finger extension. A finger counts as extended when its tip is clearly
 * farther from the wrist than its middle joint, which holds at any hand rotation in the image.
 */
object HandPoseClassifier {
    /** Tip must be this much farther from the wrist than the PIP joint. */
    const val EXTENDED_FACTOR = 1.15f

    /** Thumb tip to index knuckle, over hand size, above which the thumb is spread out. */
    const val THUMB_SPREAD = 0.55f

    fun extendedFingers(hand: HandFrame): List<Boolean> {
        val wrist = hand[HandFrame.WRIST]
        return HandFrame.FINGERS.map { (pip, tip) ->
            wrist.distanceTo(hand[tip]) > wrist.distanceTo(hand[pip]) * EXTENDED_FACTOR
        }
    }

    fun thumbSpread(hand: HandFrame): Boolean =
        hand[HandFrame.THUMB_TIP].distanceTo(hand[HandFrame.INDEX_MCP]) / hand.handSize > THUMB_SPREAD

    fun classify(hand: HandFrame, pinching: Boolean): HandPose {
        if (pinching) return HandPose.PINCH
        val fingers = extendedFingers(hand)
        val extended = fingers.count { it }
        return when {
            extended == 4 && thumbSpread(hand) -> HandPose.OPEN_PALM
            fingers[0] && extended == 1 -> HandPose.POINT
            extended == 0 -> HandPose.FIST
            else -> HandPose.OTHER
        }
    }
}
