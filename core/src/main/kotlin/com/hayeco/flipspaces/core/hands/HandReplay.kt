package com.hayeco.flipspaces.core.hands

import java.util.Locale

/**
 * One frame of a hand replay (0.3.6, debug builds): what MediaPipe would have returned for a
 * camera frame captured [tMs] after the replay started. No hands means no hand was seen.
 */
data class ReplayFrame(val tMs: Long, val hands: List<HandFrame>)

class ReplayFormatException(val lineNumber: Int, message: String) : IllegalArgumentException("Line $lineNumber: $message")

/**
 * The hand replay text format, so an emulator without the glasses camera can feed scripted
 * landmarks into the same pipeline as the camera (after MediaPipe, before the filter,
 * recognizer and pointer). Plain text, one item per line, `#` starts a comment:
 *
 * ```
 * flipspaces-hand-replay v1
 * frame 0                      # capture time in ms since the start, never decreasing
 * hand right 0.97              # right, left or unknown; optional score (default 1)
 * image x,y,z x,y,z ...        # 21 normalised image landmarks, MediaPipe order
 * world x,y,z x,y,z ...        # optional: 21 world landmarks in metres
 * frame 33                     # a frame with no hand lines: no hand seen
 * ```
 *
 * Up to two `hand` blocks per frame. [decode] rejects anything else with the line number.
 */
object ReplayCodec {
    const val HEADER = "flipspaces-hand-replay v1"
    const val MAX_HANDS = 2

    fun decode(text: String): List<ReplayFrame> {
        val frames = mutableListOf<ReplayFrame>()
        var t: Long? = null
        val hands = mutableListOf<HandFrame>()
        var side: Boolean? = null
        var score = 1f
        var image: List<Landmark>? = null
        var world: List<Landmark>? = null
        var inHand = false
        var sawHeader = false

        fun closeHand(line: Int) {
            if (!inHand) return
            val img = image ?: throw ReplayFormatException(line, "hand without an image line")
            hands += HandFrame(img, t!!, side, score, world)
            inHand = false
            image = null
            world = null
        }

        fun closeFrame(line: Int) {
            closeHand(line)
            t?.let { frames += ReplayFrame(it, hands.toList()) }
            hands.clear()
        }

        text.lineSequence().forEachIndexed { index, rawLine ->
            val n = index + 1
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            if (!sawHeader) {
                if (line != HEADER) throw ReplayFormatException(n, "expected \"$HEADER\"")
                sawHeader = true
                return@forEachIndexed
            }
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "frame" -> {
                    closeFrame(n)
                    val ms = parts.getOrNull(1)?.toLongOrNull() ?: throw ReplayFormatException(n, "frame needs a time in ms")
                    if (ms < 0 || (frames.isNotEmpty() && ms < frames.last().tMs)) throw ReplayFormatException(n, "frame times must not go back")
                    t = ms
                }
                "hand" -> {
                    if (t == null) throw ReplayFormatException(n, "hand before the first frame")
                    closeHand(n)
                    if (hands.size >= MAX_HANDS) throw ReplayFormatException(n, "at most $MAX_HANDS hands a frame")
                    side = when (parts.getOrNull(1)?.lowercase(Locale.ROOT)) {
                        "right" -> true
                        "left" -> false
                        "unknown", null -> null
                        else -> throw ReplayFormatException(n, "hand is right, left or unknown")
                    }
                    score = parts.getOrNull(2)?.let { it.toFloatOrNull() ?: throw ReplayFormatException(n, "bad score $it") } ?: 1f
                    inHand = true
                }
                "image", "world" -> {
                    if (!inHand) throw ReplayFormatException(n, "${parts[0]} outside a hand")
                    val points = parts.drop(1).map { point(it, n) }
                    if (points.size != HandFrame.COUNT) throw ReplayFormatException(n, "${parts[0]} needs ${HandFrame.COUNT} points, got ${points.size}")
                    if (parts[0] == "image") image = points else world = points
                }
                else -> throw ReplayFormatException(n, "unknown line \"${parts[0]}\"")
            }
        }
        if (!sawHeader) throw ReplayFormatException(1, "empty replay")
        closeFrame(text.lines().size)
        return frames
    }

    fun encode(frames: List<ReplayFrame>): String = buildString {
        append(HEADER).append('\n')
        for (f in frames) {
            append("frame ").append(f.tMs).append('\n')
            for (h in f.hands) {
                append("hand ").append(
                    when (h.rightHand) {
                        true -> "right"
                        false -> "left"
                        null -> "unknown"
                    },
                ).append(' ').append(num(h.score)).append('\n')
                append("image ").append(h.landmarks.joinToString(" ") { triple(it) }).append('\n')
                h.world?.let { w -> append("world ").append(w.joinToString(" ") { triple(it) }).append('\n') }
            }
        }
    }

    private fun point(token: String, line: Int): Landmark {
        val v = token.split(',')
        if (v.size != 3) throw ReplayFormatException(line, "a point is x,y,z, got \"$token\"")
        val (x, y, z) = v.map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: throw ReplayFormatException(line, "bad number in \"$token\"") }
        return Landmark(x, y, z)
    }

    private fun triple(p: Landmark) = "${num(p.x)},${num(p.y)},${num(p.z)}"

    private fun num(v: Float) = String.format(Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
