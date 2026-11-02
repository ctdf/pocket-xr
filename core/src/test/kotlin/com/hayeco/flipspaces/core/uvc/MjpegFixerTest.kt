package com.hayeco.flipspaces.core.uvc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MjpegFixerTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    /** SOI, an APP0 stub, a DQT stub, SOS header, two scan bytes, EOI. */
    private val noDht = b(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x04, 0x41, 0x56, 0xFF, 0xDB, 0x00, 0x03, 0x00, 0xFF, 0xDA, 0x00, 0x02, 0x12, 0x34, 0xFF, 0xD9)

    @Test
    fun insertsStandardTablesBeforeScan() {
        assertFalse(MjpegFixer.hasHuffmanTables(noDht))
        val fixed = MjpegFixer.ensureHuffmanTables(noDht)
        assertTrue(MjpegFixer.hasHuffmanTables(fixed))
        assertEquals(noDht.size + MjpegFixer.standardDht.size, fixed.size)
        assertArrayEquals(noDht.copyOfRange(0, 13), fixed.copyOfRange(0, 13))
        assertEquals(0xC4, fixed[14].toInt() and 0xFF)
        assertArrayEquals(noDht.copyOfRange(13, noDht.size), fixed.copyOfRange(13 + MjpegFixer.standardDht.size, fixed.size))
    }

    @Test
    fun leavesFramesWithTablesAlone() {
        val fixed = MjpegFixer.ensureHuffmanTables(noDht)
        assertSame(fixed, MjpegFixer.ensureHuffmanTables(fixed))
    }

    @Test
    fun notAJpegIsReturnedUnchanged() {
        val junk = b(1, 2, 3, 4, 5)
        assertSame(junk, MjpegFixer.ensureHuffmanTables(junk))
    }

    @Test
    fun standardSegmentHasTheRightShape() {
        val dht = MjpegFixer.standardDht
        // 4 tables: 4 ids + 4 x 16 counts + 12 + 162 + 12 + 162 values, plus the 2 length bytes.
        val expected = 2 + 4 + 64 + 12 + 162 + 12 + 162
        assertEquals(expected, ((dht[2].toInt() and 0xFF) shl 8) or (dht[3].toInt() and 0xFF))
        assertEquals(expected + 2, dht.size)
    }

    /**
     * End to end: encode a picture with the JDK's JPEG writer (which uses the standard tables),
     * strip its DHT segments as a UVC camera would, put ours back and decode it again.
     */
    @Test
    fun strippedJpegDecodesAgainWithStandardTables() {
        val image = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 0x40)
        val encoded = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
        val stripped = stripDht(encoded)
        assertFalse(MjpegFixer.hasHuffmanTables(stripped))
        val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(MjpegFixer.ensureHuffmanTables(stripped)))
        val reference = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(encoded))
        for (y in 0 until 48 step 7) for (x in 0 until 64 step 9) {
            assertEquals("pixel $x,$y", reference.getRGB(x, y), decoded.getRGB(x, y))
        }
    }

    private fun stripDht(jpeg: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        var i = 2
        while (true) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                out.write(jpeg, i, jpeg.size - i)
                return out.toByteArray()
            }
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker != 0xC4) out.write(jpeg, i, len + 2)
            i += len + 2
        }
    }

    @Test
    fun acTablesHoldEveryRunSizeSymbolOnce() {
        val symbols = (0..15).flatMap { run -> (1..10).map { size -> run shl 4 or size } }.toSet() + setOf(0x00, 0xF0)
        assertEquals(162, MjpegFixer.AC_LUMA_VALUES.size)
        assertEquals(symbols, MjpegFixer.AC_LUMA_VALUES.toSet())
        assertEquals(162, MjpegFixer.AC_CHROMA_VALUES.size)
        assertEquals(symbols, MjpegFixer.AC_CHROMA_VALUES.toSet())
    }
}
