package com.hayeco.flipspaces.core.uvc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcStreamingTest {
    private fun header(info: Int, pts: Long? = null, scr: Boolean = false): ByteArray {
        val extra = (if (pts != null) 4 else 0) + (if (scr) 6 else 0)
        val h = ByteArray(2 + extra)
        h[0] = h.size.toByte()
        h[1] = (info or UvcPayloadHeader.EOH or (if (pts != null) UvcPayloadHeader.PTS else 0) or (if (scr) UvcPayloadHeader.SCR else 0)).toByte()
        pts?.let { for (k in 0 until 4) h[2 + k] = (it shr (8 * k)).toByte() }
        if (scr) {
            val at = 2 + (if (pts != null) 4 else 0)
            h[at] = 0x78; h[at + 1] = 0x56; h[at + 2] = 0x34; h[at + 3] = 0x12
            h[at + 4] = 0xFF.toByte(); h[at + 5] = 0x0F
        }
        return h
    }

    private fun payload(info: Int, vararg body: Int, pts: Long? = null) = header(info, pts) + body.map { it.toByte() }.toByteArray()

    @Test
    fun parsesHeaderBits() {
        val h = UvcPayloadHeader.parse(header(UvcPayloadHeader.FID or UvcPayloadHeader.EOF, pts = 0x01020304, scr = true) + byteArrayOf(9, 9))!!
        assertEquals(12, h.length)
        assertEquals(1, h.frameId)
        assertTrue(h.endOfFrame)
        assertEquals(0x01020304L, h.pts)
        assertEquals(0x12345678L, h.scrStc)
        assertEquals(0x7FF, h.scrSof)
        assertTrue(!h.error && !h.stillImage)
    }

    @Test
    fun errorAndStillBits() {
        val h = UvcPayloadHeader.parse(header(UvcPayloadHeader.ERR or UvcPayloadHeader.STI))!!
        assertTrue(h.error)
        assertTrue(h.stillImage)
        assertNull(h.pts)
    }

    @Test
    fun rejectsBadHeaders() {
        assertNull(UvcPayloadHeader.parse(byteArrayOf(2)))
        assertNull(UvcPayloadHeader.parse(byteArrayOf(1, 0x80.toByte())))
        assertNull(UvcPayloadHeader.parse(byteArrayOf(12, 0x80.toByte(), 0, 0)))
        // EOH clear: not a header.
        assertNull(UvcPayloadHeader.parse(byteArrayOf(2, 0x02, 5)))
        // Announces PTS but is only 2 bytes long.
        assertNull(UvcPayloadHeader.parse(byteArrayOf(2, (0x80 or 0x04).toByte(), 5, 5, 5, 5)))
    }

    @Test
    fun parsesAtAnOffset() {
        val buf = byteArrayOf(99, 99) + header(UvcPayloadHeader.EOF) + byteArrayOf(1)
        val h = UvcPayloadHeader.parse(buf, 2, 3)!!
        assertTrue(h.endOfFrame)
    }

    @Test
    fun assemblesFrameUntilEof() {
        val a = UvcFrameAssembler()
        assertTrue(a.onPayload(payload(0, 1, 2)).isEmpty())
        assertTrue(a.onPayload(payload(0, 3)).isEmpty())
        val frames = a.onPayload(payload(UvcPayloadHeader.EOF, 4, pts = 77))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frames.single().data)
        assertEquals(77L, frames.single().pts)
        assertEquals(1L, a.framesDone)
    }

    @Test
    fun fidToggleEndsAFrameWithoutEof() {
        val a = UvcFrameAssembler()
        a.onPayload(payload(0, 1, 2))
        val frames = a.onPayload(payload(UvcPayloadHeader.FID, 3))
        assertArrayEquals(byteArrayOf(1, 2), frames.single().data)
        val next = a.onPayload(payload(UvcPayloadHeader.FID or UvcPayloadHeader.EOF, 4))
        assertArrayEquals(byteArrayOf(3, 4), next.single().data)
    }

    @Test
    fun errorPayloadDropsTheFrame() {
        val a = UvcFrameAssembler()
        a.onPayload(payload(UvcPayloadHeader.ERR, 1))
        assertTrue(a.onPayload(payload(UvcPayloadHeader.EOF, 2)).isEmpty())
        assertEquals(1L, a.framesDropped)
        assertEquals(1, a.onPayload(payload(UvcPayloadHeader.FID or UvcPayloadHeader.EOF, 5)).size)
    }

    @Test
    fun wrongSizeUncompressedFrameIsDropped() {
        val a = UvcFrameAssembler(expectedBytes = 4)
        assertTrue(a.onPayload(payload(UvcPayloadHeader.EOF, 1, 2, 3)).isEmpty())
        assertEquals(1, a.onPayload(payload(UvcPayloadHeader.FID or UvcPayloadHeader.EOF, 1, 2, 3, 4)).size)
    }

    @Test
    fun headerOnlyPayloadsAndGarbageAreHarmless() {
        val a = UvcFrameAssembler()
        assertTrue(a.onPayload(header(0)).isEmpty())
        assertTrue(a.onPayload(byteArrayOf(7, 7, 7)).isEmpty())
        assertEquals(1L, a.badHeaders)
        assertTrue(a.onPayload(ByteArray(0)).isEmpty())
    }

    @Test
    fun largeFramesGrowTheBuffer() {
        val a = UvcFrameAssembler()
        val chunk = ByteArray(200_000) { 1 }
        a.onPayload(header(0) + chunk)
        val frame = a.onPayload(header(UvcPayloadHeader.EOF) + chunk).single()
        assertEquals(400_000, frame.data.size)
    }

    @Test
    fun bulkSplitterJoinsReadsUntilShortPacket() {
        val s = UvcBulkPayloadSplitter(maxPayloadBytes = 4096, packetSize = 512)
        val got = mutableListOf<Int>()
        s.onRead(ByteArray(1024), 1024, 1024) { _, n -> got += n }
        assertTrue(got.isEmpty())
        s.onRead(ByteArray(1024), 300, 1024) { _, n -> got += n }
        assertEquals(listOf(1324), got)
        // A full payload ends without a short packet.
        s.onRead(ByteArray(4096), 4096, 4096) { _, n -> got += n }
        assertEquals(listOf(1324, 4096), got)
        // A zero length packet ends a payload that was a multiple of the packet size.
        s.onRead(ByteArray(1024), 1024, 1024) { _, n -> got += n }
        s.onRead(ByteArray(1024), 0, 1024) { _, n -> got += n }
        assertEquals(listOf(1324, 4096, 1024), got)
    }

    @Test
    fun probeControlRoundTrips() {
        val c = UvcStreamControl(formatIndex = 1, frameIndex = 2, frameIntervalUnits = 333_333, maxVideoFrameSize = 614_400, maxPayloadTransferSize = 3072, clockFrequency = 48_000_000)
        val bytes = c.encode(34)
        assertEquals(34, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(1, bytes[2].toInt())
        assertEquals(2, bytes[3].toInt())
        assertEquals(c, UvcStreamControl.decode(bytes))
        val short = c.encode(26)
        assertEquals(26, short.size)
        assertEquals(0L, UvcStreamControl.decode(short).clockFrequency)
        assertEquals(30.0, c.fps, 0.01)
    }

    @Test
    fun probeLengthFollowsVersion() {
        assertEquals(26, UvcStreamControl.lengthFor(0x0100))
        assertEquals(34, UvcStreamControl.lengthFor(0x0110))
        assertEquals(48, UvcStreamControl.lengthFor(0x0150))
    }

    @Test(expected = IllegalArgumentException::class)
    fun probeDecodeRejectsShortReply() {
        UvcStreamControl.decode(ByteArray(10))
    }

    @Test
    fun chooserPrefersMjpegNear640() {
        val formats = UvcDescriptorParser.parse(Desc.glassesLike())!!.streaming.single().formats
        val choice = UvcFormatChooser.choose(formats)!!
        assertEquals("MJPG", choice.format.fourcc)
        assertEquals(640, choice.frame.width)
        // Continuous 60 to 10 fps, capped at 60.
        assertEquals(166_666L, choice.intervalUnits)
        assertEquals(333_332L, UvcFormatChooser.choose(formats, maxFps = 30.0)!!.intervalUnits)
    }

    @Test
    fun chooserFallsBackToFrameBased() {
        val formats = UvcDescriptorParser.parse(Desc.glassesLike(frameBased = true))!!.streaming.single().formats
        val choice = UvcFormatChooser.choose(formats)
        assertNotNull(choice)
        assertEquals("H264", choice!!.format.fourcc)
        assertEquals(1920, choice.frame.width)
    }

    @Test
    fun chooserSkipsFormatsItCannotDecode() {
        val odd = UvcFormat(1, UvcFormatKind.UNCOMPRESSED, "Y800", 8, listOf(UvcFrame(1, 640, 480, 333_333, listOf(333_333))))
        assertNull(UvcFormatChooser.choose(listOf(odd)))
    }

    @Test
    fun discreteIntervalPicksFastestAllowed() {
        val f = UvcFrame(1, 640, 480, 333_333, listOf(83_333, 166_666, 333_333))
        assertEquals(166_666L, UvcFormatChooser.interval(f, 60.0))
        assertEquals(83_333L, UvcFormatChooser.interval(f, 120.0))
        assertEquals(333_333L, UvcFormatChooser.interval(f, 5.0))
    }
}
