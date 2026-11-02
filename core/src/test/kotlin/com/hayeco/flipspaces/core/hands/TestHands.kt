package com.hayeco.flipspaces.core.hands

/** Synthetic hands in normalised image coordinates, palm facing the camera, fingers up. */
object TestHands {
    /**
     * A hand with its wrist at ([cx], [cy]) and [size] from wrist to middle knuckle.
     * [extended] says which of index, middle, ring, pinky are straight; [thumbGap] is the thumb tip
     * to index tip distance over hand size.
     */
    fun hand(
        cx: Float = 0.5f,
        cy: Float = 0.8f,
        size: Float = 0.2f,
        extended: List<Boolean> = listOf(true, true, true, true),
        thumbGap: Float = 1.0f,
        timestampMs: Long = 0,
    ): HandFrame {
        val p = MutableList(HandFrame.COUNT) { Landmark(cx, cy) }
        val columns = listOf(-0.3f, -0.1f, 0.1f, 0.3f)
        val mcps = listOf(HandFrame.INDEX_MCP, HandFrame.MIDDLE_MCP, HandFrame.RING_MCP, HandFrame.PINKY_MCP)
        for (f in 0 until 4) {
            val x = cx + columns[f] * size
            val mcp = mcps[f]
            p[mcp] = Landmark(x, cy - size)
            if (extended[f]) {
                p[mcp + 1] = Landmark(x, cy - 1.4f * size)
                p[mcp + 2] = Landmark(x, cy - 1.7f * size)
                p[mcp + 3] = Landmark(x, cy - 2.0f * size)
            } else {
                // Curled: the tip folds back down towards the palm.
                p[mcp + 1] = Landmark(x, cy - 1.3f * size)
                p[mcp + 2] = Landmark(x, cy - 1.1f * size)
                p[mcp + 3] = Landmark(x, cy - 0.85f * size)
            }
        }
        p[HandFrame.THUMB_CMC] = Landmark(cx - 0.2f * size, cy - 0.2f * size)
        p[HandFrame.THUMB_MCP] = Landmark(cx - 0.4f * size, cy - 0.4f * size)
        p[HandFrame.THUMB_IP] = Landmark(cx - 0.5f * size, cy - 0.6f * size)
        // Thumb tip placed thumbGap * size away from the index tip, out to the left.
        val indexTip = p[HandFrame.INDEX_TIP]
        p[HandFrame.THUMB_TIP] = Landmark(indexTip.x - thumbGap * size, indexTip.y)
        return HandFrame(p, timestampMs)
    }

    fun openPalm(cx: Float = 0.5f, cy: Float = 0.8f, t: Long = 0) = hand(cx, cy, thumbGap = 1.6f, timestampMs = t)

    fun pointing(cx: Float = 0.5f, cy: Float = 0.8f, t: Long = 0) =
        hand(cx, cy, extended = listOf(true, false, false, false), thumbGap = 0.8f, timestampMs = t)

    fun pinching(cx: Float = 0.5f, cy: Float = 0.8f, t: Long = 0) =
        hand(cx, cy, extended = listOf(true, false, false, false), thumbGap = 0.05f, timestampMs = t)

    fun fist(t: Long = 0) = hand(extended = listOf(false, false, false, false), thumbGap = 0.6f, timestampMs = t)
}
