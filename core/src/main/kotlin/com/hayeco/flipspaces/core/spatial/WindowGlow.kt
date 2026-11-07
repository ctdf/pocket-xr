package com.hayeco.flipspaces.core.spatial

/**
 * 1.2.2 (a user: "maybe the ability to reduce opacity or intensity of the blue drop shadow around the windows"): Spatial
 * windows' Window glow setting, the strength of the accent glow round the focused window, from off (0 %) to the look it
 * always had (100 %, the default). It scales the glow's opacity only; its width and colour stay.
 */
object WindowGlow {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100
    const val DEFAULT_PERCENT = MAX_PERCENT

    /** [percent] kept within off and full. */
    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** The factor the glow's opacity is multiplied by at [percent]: 0 off, 1 the full look. */
    fun scale(percent: Int): Float = clamp(percent) / MAX_PERCENT.toFloat()

    /** The glow's opacity [alpha] at [percent], within 0 and 1. */
    fun alpha(alpha: Float, percent: Int): Float = (alpha * scale(percent)).coerceIn(0f, 1f)

    /** Whether the glow is drawn at all at [percent]. */
    fun shown(percent: Int): Boolean = clamp(percent) > MIN_PERCENT
}
