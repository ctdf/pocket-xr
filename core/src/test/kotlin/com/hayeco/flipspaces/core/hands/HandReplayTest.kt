package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandReplayTest {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    private fun points(n: Int = 21, x: Float = 0.5f) = (0 until n).joinToString(" ") { "$x,0.${it + 10},-0.01" }

    private fun expectError(text: String, line: Int, fragment: String) {
        try {
            ReplayCodec.decode(text)
            fail("no error for:\n$text")
        } catch (e: ReplayFormatException) {
            assertEquals(e.message, line, e.lineNumber)
            assertTrue(e.message, e.message!!.contains(fragment))
        }
    }

    @Test
    fun decodesFramesHandsAndEmptyFrames() {
        val text = """
            flipspaces-hand-replay v1
            # Mike's hand, then nothing
            frame 0
            hand right 0.9
            image ${points()}
            world ${points(x = 0.02f)}
            frame 33   # no hand
            frame 66
            hand left
            image ${points()}
            hand unknown 0.5
            image ${points(x = 0.7f)}
        """.trimIndent()
        val frames = ReplayCodec.decode(text)
        assertEquals(listOf(0L, 33L, 66L), frames.map { it.tMs })
        val first = frames[0].hands.single()
        assertEquals(true, first.rightHand)
        assertEquals(0.9f, first.score, 1e-6f)
        assertEquals(0.02f, first.world!![0].x, 1e-6f)
        assertEquals(0.11f, first.landmarks[1].y, 1e-6f)
        assertEquals(-0.01f, first.landmarks[1].z, 1e-6f)
        assertTrue(frames[1].hands.isEmpty())
        assertEquals(2, frames[2].hands.size)
        assertEquals(false, frames[2].hands[0].rightHand)
        assertNull(frames[2].hands[0].world)
        assertNull(frames[2].hands[1].rightHand)
        assertEquals(66L, frames[2].hands[1].timestampMs)
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val frames = ReplayScenarios.build("stretch", emptyMap(), hands).take(40) + ReplayFrame(40 * 33L, emptyList())
        val back = ReplayCodec.decode(ReplayCodec.encode(frames))
        assertEquals(frames.size, back.size)
        for ((a, b) in frames.zip(back)) {
            assertEquals(a.tMs, b.tMs)
            assertEquals(a.hands.size, b.hands.size)
            for ((h, g) in a.hands.zip(b.hands)) {
                assertEquals(h.rightHand, g.rightHand)
                assertEquals(h.pinchRatio, g.pinchRatio, 1e-3f)
                assertEquals(h.steadyPoint.x, g.steadyPoint.x, 1e-4f)
            }
        }
    }

    @Test
    fun rejectsBadReplaysWithTheLineNumber() {
        expectError("", 1, "empty")
        expectError("hello\nframe 0", 1, "expected")
        expectError("flipspaces-hand-replay v1\nhand right", 2, "before the first frame")
        expectError("flipspaces-hand-replay v1\nframe 0\nimage ${points()}", 3, "outside a hand")
        expectError("flipspaces-hand-replay v1\nframe 0\nhand right\nimage ${points(20)}", 4, "needs 21 points, got 20")
        expectError("flipspaces-hand-replay v1\nframe 0\nhand right\nimage ${points().replaceFirst("0.5,", "x,")}", 4, "bad number")
        expectError("flipspaces-hand-replay v1\nframe 0\nhand right\nimage ${points().replaceFirst("0.5,0.10,-0.01", "0.5,0.1")}", 4, "x,y,z")
        expectError("flipspaces-hand-replay v1\nframe 0\nhand right\nframe 33", 4, "hand without an image")
        expectError("flipspaces-hand-replay v1\nframe 33\nframe 0", 3, "must not go back")
        expectError("flipspaces-hand-replay v1\nframe x", 2, "needs a time")
        expectError("flipspaces-hand-replay v1\nframe 0\nhand middle", 3, "right, left or unknown")
        expectError("flipspaces-hand-replay v1\nframe 0\nwave", 3, "unknown line")
        val three = (0 until 3).joinToString("\n") { "hand right\nimage ${points()}" }
        expectError("flipspaces-hand-replay v1\nframe 0\n$three", 7, "at most 2 hands")
    }

    @Test
    fun syntheticHandsAimWhereAskedWithTheAskedRatio() {
        for (box in listOf(AirBox(), AirBox(mirror = true), AirBox(0.1f, 0.2f, 0.8f, 0.9f))) {
            val s = SyntheticHands(1920, 1080, box)
            val h = s.hand(Point(1234f, 567f), 0.36f, 0)
            val at = box.toDisplay(h.steadyPoint, 1920, 1080)
            assertEquals(1234f, at.x, 0.5f)
            assertEquals(567f, at.y, 0.5f)
            assertEquals(0.36f, h.pinchRatio, 1e-4f)
            assertEquals(HandPose.POINT, HandPoseClassifier.classify(h, pinching = false))
        }
    }

    @Test
    fun everyBuiltInScenarioBuildsAndUnknownOnesAreRefused() {
        for (name in ReplayScenarios.names) {
            val frames = ReplayScenarios.build(name, emptyMap(), hands)
            assertTrue(name, frames.size > 20)
            assertTrue(name, frames.zipWithNext().all { (a, b) -> b.tMs > a.tMs })
            assertTrue(name, ReplayScenarios.help.containsKey(name))
        }
        try {
            ReplayScenarios.build("moonwalk", emptyMap(), hands)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tap"))
        }
    }
}
