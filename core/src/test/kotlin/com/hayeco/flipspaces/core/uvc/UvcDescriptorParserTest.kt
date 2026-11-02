package com.hayeco.flipspaces.core.uvc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Builds descriptor bytes the way a UVC camera reports them (little endian, USB 2.0 chapter 9). */
internal class Desc {
    private val out = ByteArrayOutputStream()

    fun raw(vararg fields: Any): Desc {
        val body = ByteArrayOutputStream()
        fields.forEach { f ->
            when (f) {
                is U8 -> body.write(f.v)
                is U16 -> { body.write(f.v and 0xFF); body.write(f.v shr 8 and 0xFF) }
                is U32 -> for (k in 0 until 4) body.write((f.v shr (8 * k) and 0xFF).toInt())
                is ByteArray -> body.write(f)
                is Int -> body.write(f)
                else -> error("bad field $f")
            }
        }
        val bytes = body.toByteArray()
        out.write(bytes.size + 1)
        out.write(bytes)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()

    data class U8(val v: Int)
    data class U16(val v: Int)
    data class U32(val v: Long)

    companion object {
        fun guid(code: String): ByteArray =
            code.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71)

        /** A composite device like the glasses: HID first, then a bulk UVC camera, then HID again. */
        fun glassesLike(frameBased: Boolean = false): ByteArray {
            val d = Desc()
            d.raw(0x01, U16(0x0200), 0xEF, 0x02, 0x01, 64, U16(0x3318), U16(0x043A), U16(0x0100), 1, 2, 3, 1) // device
            d.raw(0x02, U16(0), 11, 1, 0, 0x80, 250) // configuration (total length unused by the parser)
            d.raw(0x04, 0, 0, 1, 0x03, 0x00, 0x00, 0) // If0 HID
            d.raw(0x05, 0x81, 0x03, U16(1024), 1)
            d.raw(0x0B, 1, 2, 0x0E, 0x03, 0x00, 0) // IAD for the video function
            d.raw(0x04, 1, 0, 1, 0x0E, 0x01, 0x00, 0) // If1 VideoControl
            d.raw(0x24, 0x01, U16(0x0110), U16(0x4D), U32(48_000_000), 1, 2) // VC header, UVC 1.1
            d.raw(0x24, 0x02, 1, U16(0x0201), 0, 0, U16(0), U16(0), U16(0), 3, 0, 0, 0) // camera terminal
            d.raw(0x05, 0x83, 0x03, U16(16), 8) // VC interrupt endpoint
            d.raw(0x04, 2, 0, 1, 0x0E, 0x02, 0x00, 0) // If2 VideoStreaming, alt 0 has the bulk endpoint
            d.raw(0x24, 0x01, 2, U16(0), 0x82, 0, 3, 0, 0, 0, 1, 0, 0) // input header
            if (frameBased) {
                d.raw(0x24, 0x10, 1, 1, guid("H264"), 16, 1, 0, 0, 0, 0, 1) // frame based format
                d.raw(0x24, 0x11, 1, 0, U16(1920), U16(1080), U32(1), U32(2), U32(333_333), 1, U32(0), U32(333_333))
            } else {
                d.raw(0x24, 0x06, 1, 2, 1, 1, 0, 0, 0, 0) // MJPEG format, 2 frames
                d.raw(0x24, 0x07, 1, 0, U16(1280), U16(720), U32(1), U32(2), U32(1_843_200), U32(333_333), 2, U32(333_333), U32(666_666))
                d.raw(0x24, 0x07, 2, 0, U16(640), U16(480), U32(1), U32(2), U32(614_400), U32(166_666), 0, U32(166_666), U32(1_000_000), U32(166_666))
                d.raw(0x24, 0x04, 2, 1, guid("YUY2"), 16, 1, 0, 0, 0, 0) // uncompressed format
                d.raw(0x24, 0x05, 1, 0, U16(640), U16(480), U32(1), U32(2), U32(614_400), U32(333_333), 1, U32(333_333))
            }
            d.raw(0x24, 0x0D, 1, 1, 4) // colour matching
            d.raw(0x05, 0x82, 0x02, U16(512), 0) // bulk IN
            d.raw(0x04, 8, 0, 1, 0x03, 0x01, 0x01, 0) // If8 HID keyboard
            d.raw(0x05, 0x89, 0x03, U16(3), 10)
            return d.bytes()
        }
    }
}

class UvcDescriptorParserTest {
    @Test
    fun findsInterfacesEndpointAndVersion() {
        val cam = UvcDescriptorParser.parse(Desc.glassesLike())!!
        assertEquals(0x0110, cam.uvcVersion)
        assertEquals("1.1", cam.versionLabel)
        assertEquals(1, cam.controlInterface)
        val vs = cam.streaming.single()
        assertEquals(2, vs.interfaceNumber)
        assertEquals(0x82, vs.endpointAddress)
        val ep = vs.bulkEndpoint!!
        assertTrue(ep.bulk && ep.isIn)
        assertEquals(512, ep.maxPacketSize)
    }

    @Test
    fun readsFormatsAndFrames() {
        val vs = UvcDescriptorParser.parse(Desc.glassesLike())!!.streaming.single()
        assertEquals(listOf("MJPG", "YUY2"), vs.formats.map { it.fourcc })
        val mjpeg = vs.formats[0]
        assertEquals(UvcFormatKind.MJPEG, mjpeg.kind)
        assertEquals(listOf(1280 to 720, 640 to 480), mjpeg.frames.map { it.width to it.height })
        assertEquals(listOf(333_333L, 666_666L), mjpeg.frames[0].intervalUnits)
        assertEquals(30.0, mjpeg.frames[0].maxFps, 0.01)
        assertTrue(mjpeg.frames[1].continuous)
        assertEquals(60.0, mjpeg.frames[1].maxFps, 0.01)
        assertEquals(1_843_200L, mjpeg.frames[0].maxFrameBytes)
        val yuy2 = vs.formats[1]
        assertEquals(UvcFormatKind.UNCOMPRESSED, yuy2.kind)
        assertEquals(16, yuy2.bitsPerPixel)
        assertEquals(1, yuy2.frames.size)
    }

    @Test
    fun readsFrameBasedFormats() {
        val vs = UvcDescriptorParser.parse(Desc.glassesLike(frameBased = true))!!.streaming.single()
        val f = vs.formats.single()
        assertEquals(UvcFormatKind.FRAME_BASED, f.kind)
        assertEquals("H264", f.fourcc)
        assertEquals(1920, f.frames.single().width)
        assertEquals(listOf(333_333L), f.frames.single().intervalUnits)
    }

    @Test
    fun describeListsEverything() {
        val text = UvcDescriptorParser.parse(Desc.glassesLike())!!.describe()
        assertTrue(text, text.contains("UVC 1.1"))
        assertTrue(text, text.contains("endpoint 0x82 (bulk, 512 B packets)"))
        assertTrue(text, text.contains("MJPG (MJPEG)"))
        assertTrue(text, text.contains("1280x720 up to 30 fps"))
        assertTrue(text, text.contains("640x480 up to 60 fps"))
    }

    @Test
    fun noVideoInterfaceGivesNull() {
        val d = Desc()
        d.raw(0x01, Desc.U16(0x0200), 0, 0, 0, 64, Desc.U16(1), Desc.U16(2), Desc.U16(1), 1, 2, 3, 1)
        d.raw(0x04, 0, 0, 1, 0x03, 0x00, 0x00, 0)
        assertNull(UvcDescriptorParser.parse(d.bytes()))
    }

    @Test
    fun truncatedDescriptorsDoNotThrow() {
        val full = Desc.glassesLike()
        for (cut in listOf(5, 40, 100, full.size - 3)) {
            UvcDescriptorParser.parse(full.copyOf(cut))
        }
        // A zero length descriptor stops the walk instead of looping.
        UvcDescriptorParser.parse(byteArrayOf(0, 4, 0, 0))
    }

    @Test
    fun nonAsciiGuidFallsBackToHex() {
        val g = byteArrayOf(0x7D, 0xEB.toByte(), 0x36, 0xE4.toByte()) + ByteArray(12)
        assertEquals("7DEB36E4", UvcDescriptorParser.fourccFromGuid(g, 0))
    }
}
