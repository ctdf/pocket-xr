package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import kotlin.math.sin

/**
 * Builds hands MediaPipe could have returned: a pointing hand (index out, other fingers
 * curled, so it never reads as an open palm) whose aim point ([HandFrame.steadyPoint]) lands on
 * a given display pixel through [airBox], with world landmarks (metres) whose thumb to index
 * ratio is exactly the given one, as Mike's logs measure it.
 */
class SyntheticHands(val displayWidth: Int, val displayHeight: Int, val airBox: AirBox = AirBox(), val size: Float = 0.12f) {
    /** A hand aiming at display pixel [at] with pinch ratio [ratio], captured at [tMs]. */
    fun hand(at: Point, ratio: Float, tMs: Long, right: Boolean = true): HandFrame {
        val shape = shape(ratio)
        val steady = HandFrame(shape, 0).steadyPoint
        val target = imagePoint(at)
        val dx = target.x - steady.x
        val dy = target.y - steady.y
        val image = shape.map { Landmark(it.x + dx, it.y + dy, it.z) }
        // World landmarks: the same shape in metres around the wrist, 8.5 cm wrist to index knuckle.
        val wrist = shape[HandFrame.WRIST]
        val k = WRIST_TO_INDEX_M / wrist.distanceTo(shape[HandFrame.INDEX_MCP])
        val world = shape.map { Landmark((it.x - wrist.x) * k, (it.y - wrist.y) * k, 0f) }
        return HandFrame(image, tMs, rightHand = right, score = 0.95f, world = world)
    }

    /** Where in the camera image a hand must aim from to reach display pixel [p] (the inverse of [AirBox.toDisplay]). */
    fun imagePoint(p: Point): Landmark {
        val nx = (p.x / (displayWidth - 1)).coerceIn(0f, 1f)
        val ny = (p.y / (displayHeight - 1)).coerceIn(0f, 1f)
        val x = if (airBox.mirror) 1f - nx else nx
        return Landmark(airBox.left + x * (airBox.right - airBox.left), airBox.top + ny * (airBox.bottom - airBox.top))
    }

    private fun shape(ratio: Float): List<Landmark> {
        val cx = 0.5f
        val cy = 0.5f
        val p = MutableList(HandFrame.COUNT) { Landmark(cx, cy) }
        val columns = listOf(-0.3f, -0.1f, 0.1f, 0.3f)
        val mcps = listOf(HandFrame.INDEX_MCP, HandFrame.MIDDLE_MCP, HandFrame.RING_MCP, HandFrame.PINKY_MCP)
        for (f in 0 until 4) {
            val x = cx + columns[f] * size
            val mcp = mcps[f]
            p[mcp] = Landmark(x, cy - size)
            if (f == 0) {
                p[mcp + 1] = Landmark(x, cy - 1.4f * size)
                p[mcp + 2] = Landmark(x, cy - 1.7f * size)
                p[mcp + 3] = Landmark(x, cy - 2.0f * size)
            } else {
                p[mcp + 1] = Landmark(x, cy - 1.3f * size)
                p[mcp + 2] = Landmark(x, cy - 1.1f * size)
                p[mcp + 3] = Landmark(x, cy - 0.85f * size)
            }
        }
        p[HandFrame.THUMB_CMC] = Landmark(cx - 0.2f * size, cy - 0.2f * size)
        p[HandFrame.THUMB_MCP] = Landmark(cx - 0.4f * size, cy - 0.4f * size)
        p[HandFrame.THUMB_IP] = Landmark(cx - 0.5f * size, cy - 0.6f * size)
        val bone = p[HandFrame.WRIST].distanceTo(p[HandFrame.INDEX_MCP])
        val tip = p[HandFrame.INDEX_TIP]
        p[HandFrame.THUMB_TIP] = Landmark(tip.x - ratio * bone, tip.y)
        return p
    }

    private companion object {
        const val WRIST_TO_INDEX_M = 0.085f
    }
}

/**
 * Writes replay frames at [frameMs] apart (30 fps like the glasses camera): each step places
 * zero, one or two hands, each a display pixel and a pinch ratio.
 */
class ReplayScript(val hands: SyntheticHands, val frameMs: Long = 33) {
    private val frames = mutableListOf<ReplayFrame>()
    var t = 0L
        private set

    /** One frame with these hands (aim pixel, ratio); none for a frame with no hand. */
    fun frame(vararg placed: Pair<Point, Float>) {
        frames += ReplayFrame(t, placed.mapIndexed { i, (p, r) -> hands.hand(p, r, t, right = i == 0) })
        t += frameMs
    }

    /** [count] frames at [at] with a pixel or two of tremor, like a real hand held still. */
    fun hold(at: Point, ratio: Float, count: Int) = repeat(count) { i ->
        frame(Point(at.x + 1.5f * sin(i * 1.7f), at.y + 1.5f * sin(i * 2.3f + 1f)) to ratio)
    }

    /** [count] frames moving evenly from [from] to [to] (ending on [to]), at [ratio]. */
    fun move(from: Point, to: Point, count: Int, ratio: Float) = repeat(count) { i ->
        frame(lerp(from, to, (i + 1f) / count) to ratio)
    }

    /** One frame per ratio, the hand going evenly from [from] to [to]. */
    fun ratios(from: Point, to: Point, ratios: List<Float>) = ratios.forEachIndexed { i, r ->
        frame(lerp(from, to, (i + 1f) / ratios.size) to r)
    }

    fun noHand(count: Int) = repeat(count) { frame() }

    fun build(): List<ReplayFrame> = frames.toList()

    companion object {
        fun lerp(a: Point, b: Point, k: Float) = Point(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
    }
}

/**
 * The built-in debug replay scenarios (0.3.6), for emulator testing without the glasses. Each
 * takes display pixels as parameters (the app turns panel coordinates into display pixels
 * first). Ratios are world ratios, shaped like Mike's natural pinch in the 0.3.5 log: the hand
 * rests at about 0.48, a pinch bottoms out at 0.32 to 0.38 and opens only to 0.45 to 0.55.
 */
object ReplayScenarios {
    /** Mike's resting finger gap, the bottom of his pinch, and how far it reopens. */
    val CLOSE = listOf(0.47f, 0.42f, 0.36f, 0.33f, 0.32f, 0.32f, 0.33f, 0.32f)
    val OPEN = listOf(0.38f, 0.45f, 0.49f, 0.52f, 0.55f)
    const val REST = 0.48f
    const val HELD = 0.3f

    val names = listOf("tap", "log-pinch-4", "log-pinch-5", "log-pinch-6", "log-pinch-10", "log-pinch-14", "drag", "resize", "stretch", "stretch-dropouts")

    /** What each scenario does and its parameters, for the docs and the debug receiver's log. */
    val help = mapOf(
        "tap" to "point at x,y (default the display centre) and pinch once",
        "log-pinch-4" to "pinch 4 of the 0.3.5 log: 1026,768, ratio bottoming at 0.32",
        "log-pinch-5" to "pinch 5 of the 0.3.5 log: 1042,773, ratio bottoming at 0.36",
        "log-pinch-6" to "pinch 6 of the 0.3.5 log: 1036,799, ratio bottoming at 0.38",
        "log-pinch-10" to "pinch 10 of the 0.3.5 log: a pinch while the hand sweeps right fast (1039,954 to 1616,857)",
        "log-pinch-14" to "pinch 14 of the 0.3.5 log: a pinch right after a 650 px hand jump (1643,553 to 993,437), then a drag",
        "drag" to "pinch at x,y (a title bar), drag to x2,y2, release",
        "resize" to "pinch at x,y (a corner handle), drag to x2,y2, release",
        "stretch" to "two hands pinch either side of x,y, gap (default 300) px apart, and pull to gap2 (default 700) px",
        "stretch-dropouts" to "0.5.2: Mike's two hand resize with tracking dropouts: pinch either side of x,y gap px apart, pull toward gap2 " +
            "(default 1800, past the largest size), lose one hand for 4 frames and both for 3, come back pinched, squeeze the hands " +
            "together (below the smallest size, almost one ray), release",
    )

    fun build(name: String, params: Map<String, Float>, hands: SyntheticHands): List<ReplayFrame> {
        val w = hands.displayWidth.toFloat()
        val h = hands.displayHeight.toFloat()
        fun p(key: String, default: Float) = params[key] ?: default
        val s = ReplayScript(hands)
        when (name) {
            "tap" -> {
                val at = Point(p("x", w / 2f), p("y", h / 2f))
                tap(s, at)
            }
            "log-pinch-4" -> logged(s, aim = Point(1026f, 768f), onset = Point(1024f, 775f), end = Point(1038f, 762f), bottom = 0.32f, from = 0.47f, endRatio = 0.45f)
            "log-pinch-5" -> logged(s, aim = Point(1042f, 773f), onset = Point(1041f, 775f), end = Point(1038f, 757f), bottom = 0.36f, from = 0.47f, endRatio = 0.49f)
            "log-pinch-6" -> logged(s, aim = Point(1036f, 799f), onset = Point(1034f, 796f), end = Point(1036f, 798f), bottom = 0.38f, from = 0.48f, endRatio = 0.39f)
            "log-pinch-10" -> {
                // 201 ms before the onset the cursor was at 1039,954; the hand swept right through
                // the whole pinch: onset at 1309,846, press at 1512,842, release at 1616,857.
                val start = Point(1039f, 954f)
                s.hold(start, 1.03f, 20)
                s.ratios(start, Point(1309f, 846f), listOf(0.98f, 0.9f, 0.8f, 0.68f, 0.55f, 0.43f))
                s.ratios(Point(1309f, 846f), Point(1512f, 842f), listOf(0.3f, 0.2f, 0.15f))
                s.ratios(Point(1512f, 842f), Point(1616f, 857f), listOf(0.15f, 0.4f, 0.67f))
                s.hold(Point(1616f, 857f), 0.8f, 10)
            }
            "log-pinch-14" -> {
                // The hand was at 1643,553, then 134 ms later at 993,437 as the fingers closed;
                // pressed at 936,490, dragged to 1102,475 and let go at 1281,507.
                val start = Point(1643f, 553f)
                s.hold(start, 0.65f, 20)
                s.ratios(start, Point(993f, 437f), listOf(0.65f, 0.6f, 0.5f, 0.37f))
                s.ratios(Point(993f, 437f), Point(936f, 490f), listOf(0.3f, 0.24f, 0.19f, 0.19f))
                s.ratios(Point(936f, 490f), Point(1102f, 475f), listOf(0.19f, 0.2f, 0.2f))
                s.ratios(Point(1102f, 475f), Point(1281f, 507f), listOf(0.3f, 0.57f))
                s.hold(Point(1281f, 507f), 0.65f, 10)
            }
            "drag", "resize" -> {
                val from = if (name == "drag") Point(p("x", w / 2f), p("y", h * 0.2f)) else Point(p("x", w * 0.7f), p("y", h * 0.75f))
                val to = if (name == "drag") Point(p("x2", from.x + w * 0.15f), p("y2", from.y + h * 0.2f)) else Point(p("x2", from.x + w * 0.15f), p("y2", from.y + h * 0.15f))
                drag(s, from, to)
            }
            "stretch" -> {
                val c = Point(p("x", w / 2f), p("y", h / 2f))
                val g0 = p("gap", 300f) / 2f
                val g1 = p("gap2", 700f) / 2f
                fun both(gap: Float, ratio: Float) = s.frame(Point(c.x + gap, c.y) to ratio, Point(c.x - gap, c.y) to ratio)
                repeat(20) { both(g0, REST) }
                for (r in CLOSE) both(g0, r)
                repeat(6) { both(g0, HELD) }
                repeat(20) { i -> both(g0 + (g1 - g0) * (i + 1) / 20f, HELD) }
                repeat(6) { both(g1, HELD) }
                for (r in OPEN) both(g1, r)
                repeat(10) { both(g1, REST) }
            }
            "stretch-dropouts" -> {
                val c = Point(p("x", w / 2f), p("y", h / 2f))
                val g0 = p("gap", 300f) / 2f
                val g1 = p("gap2", 1800f) / 2f
                fun both(gap: Float, ratio: Float) = s.frame(Point(c.x + gap, c.y) to ratio, Point(c.x - gap, c.y) to ratio)
                repeat(20) { both(g0, REST) }
                for (r in CLOSE) both(g0, r)
                repeat(6) { both(g0, HELD) }
                val half = (g0 + g1) / 2f
                repeat(10) { i -> both(g0 + (half - g0) * (i + 1) / 10f, HELD) }
                // One hand drops out mid stretch, then both.
                repeat(4) { s.frame(Point(c.x + half, c.y) to HELD) }
                s.noHand(3)
                // Back, still pinched: past the largest size, then squeezed almost onto one ray.
                repeat(6) { i -> both(half + (g1 - half) * (i + 1) / 6f, HELD) }
                repeat(10) { i -> both(g1 - (g1 - 4f) * (i + 1) / 10f, HELD) }
                for (r in OPEN) both(4f, r)
                repeat(10) { both(4f, REST) }
            }
            else -> throw IllegalArgumentException("Unknown scenario \"$name\"; built in: ${names.joinToString()}")
        }
        return s.build()
    }

    /** Approach [at] from 150 px to the left, settle, then one natural pinch. */
    private fun tap(s: ReplayScript, at: Point) {
        val approach = Point(at.x - 150f, at.y)
        s.hold(approach, REST, 5)
        s.move(approach, at, 15, REST)
        s.hold(at, REST, 15)
        for (r in CLOSE) s.hold(at, r, 1)
        for (r in OPEN) s.hold(at, r, 1)
        s.hold(at, REST, 15)
    }

    /**
     * A pinch as the 0.3.5 log recorded it: aimed at [aim], the closing onset with the hand at
     * [onset] at a ratio of [bottom] (from [from]), the fingers staying near the bottom for the
     * 400 ms the old lock waited, then [endRatio] with the hand at [end], and opening.
     */
    private fun logged(s: ReplayScript, aim: Point, onset: Point, end: Point, bottom: Float, from: Float, endRatio: Float) {
        s.hold(aim, from, 30)
        s.ratios(aim, onset, listOf(from, (from + bottom) / 2f + 0.03f, bottom))
        val hover = listOf(0f, 0.01f, 0.02f, 0.01f, 0f, 0.01f, 0.02f, 0.02f, 0.01f, 0.01f, 0.02f)
        s.ratios(onset, end, hover.map { bottom + it })
        s.ratios(end, end, listOf(endRatio, maxOf(endRatio, 0.5f), 0.55f))
        s.hold(end, 0.52f, 10)
    }

    /** Pinch at [from], hold past the drag time, move to [to] over 20 frames, release there. */
    private fun drag(s: ReplayScript, from: Point, to: Point) {
        s.hold(from, REST, 20)
        for (r in CLOSE) s.hold(from, r, 1)
        s.hold(from, HELD, 10)
        s.move(from, to, 20, HELD)
        s.hold(to, HELD, 6)
        for (r in OPEN) s.hold(to, r, 1)
        s.hold(to, REST, 10)
    }
}
