package com.hayeco.flipspaces.core.uvc

import com.hayeco.flipspaces.core.hands.GlassesUsb
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.ProviderChooser
import com.hayeco.flipspaces.core.hands.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvAndProvidersTest {
    private fun rgb(c: Int) = Triple(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)

    @Test
    fun yuvColoursAreRight() {
        assertEquals(Triple(255, 255, 255), rgb(YuvConverter.argb(235, 128, 128)))
        assertEquals(Triple(0, 0, 0), rgb(YuvConverter.argb(16, 128, 128)))
        val (r, g, b) = rgb(YuvConverter.argb(81, 90, 240))
        assertTrue("red was $r $g $b", r > 240 && g < 10 && b < 10)
    }

    @Test
    fun yuy2PairsShareChroma() {
        // Two pixels: white then black, no colour.
        val src = byteArrayOf(235.toByte(), 128.toByte(), 16, 128.toByte())
        val out = YuvConverter.yuy2ToArgb(src, 2, 1)
        assertEquals(0xFFFFFFFF.toInt(), out[0])
        assertEquals(0xFF000000.toInt(), out[1])
    }

    @Test
    fun yuy2StepHalvesTheImage() {
        val src = ByteArray(4 * 2 * 2) { if (it % 2 == 0) 235.toByte() else 128.toByte() }
        assertEquals(2, YuvConverter.yuy2ToArgb(src, 4, 2, step = 2).size)
    }

    @Test
    fun nv12UsesTheInterleavedPlane() {
        // 2x2 grey picture with a red chroma sample.
        val src = byteArrayOf(81, 81, 81, 81, 90, 240.toByte())
        val out = YuvConverter.nv12ToArgb(src, 2, 2)
        assertEquals(4, out.size)
        out.forEach { assertTrue(rgb(it).first > 240) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortFrameIsRejected() {
        YuvConverter.yuy2ToArgb(ByteArray(3), 2, 1)
    }

    @Test
    fun chooserPrefersHandsThenFallsBackToTrackpad() {
        val none = ProviderStatus.Unavailable("no")
        assertEquals(
            HandProviderKind.TRACKPAD,
            ProviderChooser.choose(mapOf(HandProviderKind.XREAL_SDK to none, HandProviderKind.GLASSES_CAMERA to ProviderStatus.NeedsPermission("USB"))),
        )
        assertEquals(
            HandProviderKind.GLASSES_CAMERA,
            ProviderChooser.choose(mapOf(HandProviderKind.XREAL_SDK to none, HandProviderKind.GLASSES_CAMERA to ProviderStatus.Running("MJPG"))),
        )
        assertEquals(
            HandProviderKind.TRACKPAD,
            ProviderChooser.choose(
                mapOf(HandProviderKind.GLASSES_CAMERA to ProviderStatus.Ready, HandProviderKind.TRACKPAD to ProviderStatus.Ready),
                preferred = HandProviderKind.TRACKPAD,
            ),
        )
        assertEquals(
            HandProviderKind.GLASSES_CAMERA,
            ProviderChooser.choose(mapOf(HandProviderKind.GLASSES_CAMERA to ProviderStatus.Ready), preferred = HandProviderKind.XREAL_SDK),
        )
    }

    @Test
    fun glassesIds() {
        assertTrue(GlassesUsb.isAura(0x3318, 0x043A))
        assertFalse(GlassesUsb.isAura(0x3318, 0x0436))
        assertTrue(GlassesUsb.isXreal(0x3318))
    }
}
