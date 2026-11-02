package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide

/**
 * 0.5.11: one cursor dot, the active hand's. Mike: "sometimes a pinch shows 2 ray cast cursor
 * dots". Up to 0.5.10 every hand that aimed got a dot, including one MediaPipe had lost or
 * renamed, which coasts for up to 450 ms before it is dropped. Now the dot goes to the hand that
 * holds a press or grab (the one that pinched last when both do), else the hand that had it, until
 * the other one clearly aims (moves over [SWITCH_DEG_PER_S] and three times faster), else the
 * right. Pure, so the renderer and the tests share it.
 */
class CursorChoice {
    /** The hand whose dot shows; null with no hand aiming. */
    var active: HandSide? = null
        private set

    /** [aiming]: the hands that aim now; [busy]: holding a press or grab. Returns a log line when the dot changes hand. */
    fun pick(aiming: List<HandSide>, busy: (HandSide) -> Boolean, speedDegPerS: (HandSide) -> Float = { 0f }): String? {
        val before = active
        val pressing = aiming.filter(busy)
        active = when {
            aiming.isEmpty() -> null
            pressing.size == 1 -> pressing[0]
            pressing.size > 1 -> before?.takeIf { it in pressing } ?: pressing[0]
            before != null && before in aiming -> aiming.firstOrNull { it != before && speedDegPerS(it) > SWITCH_DEG_PER_S && speedDegPerS(it) > 3f * speedDegPerS(before) } ?: before
            else -> aiming.firstOrNull { it == HandSide.RIGHT } ?: aiming[0]
        }
        val now = active
        if (now == before || now == null) return null
        val hidden = aiming.filter { it != now }
        return "Cursor: one dot, the ${now.label} hand's" + if (hidden.isEmpty()) "" else " (the ${hidden.joinToString { it.label }} hand's not drawn)"
    }

    companion object {
        const val SWITCH_DEG_PER_S = 30f
    }
}
