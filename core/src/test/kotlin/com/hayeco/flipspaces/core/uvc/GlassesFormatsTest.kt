package com.hayeco.flipspaces.core.uvc

import com.hayeco.flipspaces.core.hands.ByteArrayPool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format choice, buffer reuse and bulk reads for the Aura camera as Mike's Flip 7 log listed it. */
class GlassesFormatsTest {
    private val fps30 = 333_333L

    private fun frame(i: Int, w: Int, h: Int, interval: Long = fps30) = UvcFrame(i, w, h, interval, listOf(interval))

    /** MJPG 1080x1920 listed before 1920x1080, as a camera may; HEVC frame based sizes after. */
    private val aura = listOf(
        UvcFormat(1, UvcFormatKind.MJPEG, "MJPG", 0, listOf(frame(1, 1080, 1920), frame(2, 1920, 1080))),
        UvcFormat(
            2, UvcFormatKind.FRAME_BASED, "HEVC", 16,
            listOf(
                frame(1, 2016, 1512), frame(2, 1920, 1080), frame(3, 1080, 1920), frame(4, 1920, 1920),
                frame(5, 2880, 1600), frame(6, 4032, 3024, 666_666),
            ),
        ),
    )

    @Test
    fun auraGetsLandscapeMjpeg1080p() {
        val choice = UvcFormatChooser.choose(aura)!!
        assertEquals("MJPG", choice.format.fourcc)
        assertEquals(1920, choice.frame.width)
        assertEquals(1080, choice.frame.height)
        assertEquals(fps30, choice.intervalUnits)
        assertTrue(choice.landscape)
        assertEquals("MJPG 1920x1080 landscape at 30 fps", choice.label)
    }

    @Test
    fun landscapeInAnotherFormatBeatsPortraitMjpeg() {
        val portraitMjpeg = UvcFormat(1, UvcFormatKind.MJPEG, "MJPG", 0, listOf(frame(1, 1080, 1920)))
        val hevc = aura[1]
        val choice = UvcFormatChooser.choose(listOf(portraitMjpeg, hevc))!!
        assertEquals("HEVC", choice.format.fourcc)
        assertTrue(choice.landscape)
        assertEquals(1920, choice.frame.width)
    }

    @Test
    fun portraitOnlyCameraStillWorks() {
        val portraitMjpeg = UvcFormat(1, UvcFormatKind.MJPEG, "MJPG", 0, listOf(frame(1, 1080, 1920), frame(2, 720, 1280)))
        val choice = UvcFormatChooser.choose(listOf(portraitMjpeg))!!
        assertFalse(choice.landscape)
        assertEquals(720, choice.frame.width)
        assertTrue(choice.label.contains("portrait"))
    }

    @Test
    fun squareCountsAsLandscape() {
        val square = UvcFormat(1, UvcFormatKind.MJPEG, "MJPG", 0, listOf(frame(1, 1080, 1920), frame(2, 1920, 1920)))
        assertEquals(1920, UvcFormatChooser.choose(listOf(square))!!.frame.height)
    }

    @Test
    fun assemblerCopiesIntoPooledBuffers() {
        val pool = ByteArrayPool()
        val a = UvcFrameAssembler(allocate = pool::obtain)
        fun eof(fid: Int, vararg body: Int) = byteArrayOf(2, (0x80 or 0x02 or fid).toByte()) + body.map { it.toByte() }.toByteArray()
        val first = a.onPayload(eof(0, 1, 2, 3)).single()
        assertEquals(3, first.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.data.copyOf(first.size))
        pool.recycle(first.data)
        val second = a.onPayload(eof(1, 4, 5)).single()
        assertSame(first.data, second.data)
        assertEquals(2, second.size)
        assertEquals(4, second.data[0].toInt())
        assertEquals(1L, pool.allocations)
    }

    @Test
    fun splitterSplitsAReadThatCrossesAFullPayload() {
        // A 1000 byte payload that ends exactly on a packet boundary, then the next one, in one read.
        val s = UvcBulkPayloadSplitter(maxPayloadBytes = 1024, packetSize = 512)
        val got = mutableListOf<Int>()
        val data = ByteArray(1536) { (it / 1024).toByte() }
        s.onRead(data, 1536, 2048) { p, n ->
            got += n
            assertEquals(if (got.size == 1) 0 else 1, p[0].toInt())
        }
        assertEquals(listOf(1024, 512), got)
    }

    @Test
    fun readPlanQueuesWholePacketRequests() {
        val plan = UvcBulkReadPlan.of(maxPayload = 3_000_000, packetSize = 512, frameBytes = 400_000)
        assertEquals(UvcBulkReadPlan.MAX_REQUEST, plan.requestBytes)
        assertEquals(0, plan.requestBytes % 512)
        assertTrue(plan.requests in UvcBulkReadPlan.MIN_REQUESTS..UvcBulkReadPlan.MAX_REQUESTS)
        // A small payload size caps the request, rounded up to whole packets.
        assertEquals(32_768, UvcBulkReadPlan.of(maxPayload = 32_768, packetSize = 1024).requestBytes)
        assertEquals(1536, UvcBulkReadPlan.of(maxPayload = 1100, packetSize = 512).requestBytes)
        assertEquals(UvcBulkReadPlan.MIN_REQUESTS, UvcBulkReadPlan.of(0, 512).requests)
        assertEquals(UvcBulkReadPlan.MAX_REQUESTS, UvcBulkReadPlan.of(0, 512, frameBytes = 16_000_000).requests)
    }

    @Test
    fun tableInserterReusesItsBuffer() {
        fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()
        val noDht = b(0xFF, 0xD8, 0xFF, 0xDB, 0x00, 0x03, 0x00, 0xFF, 0xDA, 0x00, 0x02, 0x12, 0x34, 0xFF, 0xD9)
        val padded = noDht + ByteArray(50)
        val inserter = MjpegTableInserter()
        val one = inserter.fix(padded, noDht.size)
        assertEquals(noDht.size + MjpegFixer.standardDht.size, one.length)
        assertTrue(MjpegFixer.hasHuffmanTables(one.data, one.length))
        assertArrayEquals(MjpegFixer.ensureHuffmanTables(noDht), one.data.copyOf(one.length))
        val two = inserter.fix(padded, noDht.size)
        assertSame(one.data, two.data)
        assertEquals(2L, inserter.inserted)
        // Frames with tables, and non JPEG data, pass through untouched.
        val withTables = one.data.copyOf(one.length)
        assertSame(withTables, inserter.fix(withTables).data)
        val junk = ByteArray(10)
        assertSame(junk, inserter.fix(junk).data)
    }
}
