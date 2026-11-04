package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadDirection
import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.TrackpadSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1.1.9 (issue 33, a Retroid Nova with an XREAL One Pro: "The up and down mouse is tied to lifting and lowering the left
 * side of the device, but it should be tilting the front of the device (in landscape)"): the aim is the screen's top edge
 * at the display's rotation, and Spatial Mouse on a handheld locks no orientation.
 */
class AimRotation0119Test {
    private val c = cos(Math.toRadians(30.0)).toFloat()
    private val s = sin(Math.toRadians(30.0)).toFloat()

    /** A device to world matrix (row major, x east, y north, z up) from the world directions of the device's x, y and z. */
    private fun matrix(x: FloatArray, y: FloatArray, z: FloatArray) = floatArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])

    private operator fun FloatArray.unaryMinus() = FloatArray(size) { -this[it] }

    /**
     * The device held at [rotation] quarter turns (Surface.ROTATION_*) with the screen's right edge to [right], its top
     * edge to [up] and its face to [face], as the user sees it: the device's own axes by the turn.
     */
    private fun held(rotation: Int, right: FloatArray, up: FloatArray, face: FloatArray): FloatArray = when (rotation) {
        1 -> matrix(up, -right, face)
        2 -> matrix(-right, -up, face)
        3 -> matrix(-up, right, face)
        else -> matrix(right, up, face)
    }

    private val east = floatArrayOf(1f, 0f, 0f)
    private val north = floatArrayOf(0f, 1f, 0f)
    private val sky = floatArrayOf(0f, 0f, 1f)
    private val west = floatArrayOf(-1f, 0f, 0f)

    /** The screen's top edge tipped 30 degrees up from the north, its face 30 degrees back from the sky. */
    private val northUp30 = floatArrayOf(0f, c, s)
    private val faceBack30 = floatArrayOf(0f, -s, c)

    @Test fun theTopEdgeAimsAtEveryRotation() {
        for (rotation in 0..3) {
            val flat = held(rotation, east, north, sky)
            val (yaw, pitch) = PhoneAim.angles(flat, rotation)!!
            assertEquals("yaw at $rotation", 0f, yaw, 0.01f)
            assertEquals("pitch at $rotation", 0f, pitch, 0.01f)
            val tipped = held(rotation, east, northUp30, faceBack30)
            assertEquals("tipped at $rotation", 30f, PhoneAim.angles(tipped, rotation)!!.second, 0.01f)
            assertEquals("tipped yaw at $rotation", 0f, PhoneAim.angles(tipped, rotation)!!.first, 0.01f)
            // The top edge turned to the east aims right.
            val turned = held(rotation, floatArrayOf(0f, -1f, 0f), east, sky)
            assertEquals("turned at $rotation", 90f, PhoneAim.angles(turned, rotation)!!.first, 0.01f)
        }
    }

    /** [PhoneAim.toScreen] is `SensorManager.remapCoordinateSystem` for the rotation: the screen's own axes, whatever the turn. */
    @Test fun toScreenGivesTheScreensAxes() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val tippedScreen = matrix(east, northUp30, faceBack30)
        for (rotation in 0..3) {
            assertArrayEquals("flat at $rotation", identity, PhoneAim.toScreen(held(rotation, east, north, sky), rotation), 1e-6f)
            assertArrayEquals("tipped at $rotation", tippedScreen, PhoneAim.toScreen(held(rotation, east, northUp30, faceBack30), rotation), 1e-6f)
            // The aim from the screen's axes is the aim at the rotation.
            val r = held(rotation, east, northUp30, faceBack30)
            assertEquals(PhoneAim.angles(r, rotation)!!.second, PhoneAim.angles(PhoneAim.toScreen(r, rotation))!!.second, 1e-4f)
        }
        // Android's own mappings: ROTATION_90 is (AXIS_Y, AXIS_MINUS_X): the new x is the old -y, the new y the old x.
        val r = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        assertArrayEquals(floatArrayOf(-2f, 1f, 3f, -5f, 4f, 6f, -8f, 7f, 9f), PhoneAim.toScreen(r, 1), 0f)
        assertArrayEquals(floatArrayOf(-1f, -2f, 3f, -4f, -5f, 6f, -7f, -8f, 9f), PhoneAim.toScreen(r, 2), 0f)
        assertArrayEquals(floatArrayOf(2f, -1f, 3f, 5f, -4f, 6f, 8f, -7f, 9f), PhoneAim.toScreen(r, 3), 0f)
        assertArrayEquals(r, PhoneAim.toScreen(r, 0), 0f)
    }

    /**
     * The Nova: its screen portrait by nature, held landscape at ROTATION_90 (the system's way up). Tilting its front (the
     * top edge as held) aims up; lifting its left side, which is the portrait top, the sensor's +y, does not. 1.1.8 read
     * the sensor's +y alone, so it was the other way round.
     */
    @Test fun theNovaHeldLandscapeAimsWithItsFront() {
        val front = held(1, east, northUp30, faceBack30)
        assertEquals(30f, PhoneAim.angles(front, 1)!!.second, 0.01f)
        assertEquals(0f, PhoneAim.angles(front)!!.second, 0.01f) // 1.1.8: the front tilt did nothing

        // The left side (the portrait top, to the west) lifted 30 degrees, the top edge still to the north.
        val leftUp = floatArrayOf(-c, 0f, s)
        val leftLifted = matrix(north, leftUp, floatArrayOf(s, 0f, c))
        assertEquals(0f, PhoneAim.angles(leftLifted, 1)!!.second, 0.01f)
        assertEquals(30f, PhoneAim.angles(leftLifted)!!.second, 0.01f) // 1.1.8: what issue 33 reported
        assertEquals(-90f, PhoneAim.angles(held(1, east, north, sky))!!.first, 0.01f) // and it aimed to the west

        // Reverse landscape (ROTATION_270): the same front tilt aims up.
        assertEquals(30f, PhoneAim.angles(held(3, east, northUp30, faceBack30), 3)!!.second, 0.01f)
        // The portrait top to the west at rotation 1 is the device's +y.
        assertArrayEquals(west, floatArrayOf(held(1, east, north, sky)[1], held(1, east, north, sky)[4], held(1, east, north, sky)[7]), 0f)
    }

    /** Only the low two bits of the rotation count, as Surface.ROTATION_* are 0..3. */
    @Test fun aRotationIsAQuarterTurnCount() {
        val r = held(1, east, northUp30, faceBack30)
        assertEquals(PhoneAim.angles(r, 1), PhoneAim.angles(r, 5))
        assertNotEquals(PhoneAim.angles(r, 1), PhoneAim.angles(r, 0))
    }

    /** The orientation choice: the Nova (portrait by nature, built in controls) is a handheld, a phone is not. */
    @Test fun theNovaIsAHandheld() {
        // Natural size 1080 x 1920 read at ROTATION_90 as 1920 x 1080, or after a forced portrait at 0 as 1080 x 1920.
        val novaNatural = TrackpadSettings.landscapeByNature(1920, 1080, 1)
        assertFalse(novaNatural)
        assertFalse(TrackpadSettings.landscapeByNature(1080, 1920, 0))
        assertTrue(TrackpadSettings.landscapeHandheld(novaNatural, builtInGamepad = true))
        // A screen landscape by nature (the 1.1.8 emulator, or another handheld), with or without its controls.
        assertTrue(TrackpadSettings.landscapeHandheld(TrackpadSettings.landscapeByNature(1920, 1080, 0), builtInGamepad = false))
        assertTrue(TrackpadSettings.landscapeHandheld(TrackpadSettings.landscapeByNature(1080, 1920, 3), builtInGamepad = true))
        // A phone, with or without a Bluetooth controller (external, so not built in).
        assertFalse(TrackpadSettings.landscapeHandheld(TrackpadSettings.landscapeByNature(1080, 2340, 0), builtInGamepad = false))
    }

    /** The Nova asks for Spatial Mouse: no portrait lock, so the system's landscape (or the user's forced one) stays. */
    @Test fun spatialMouseOnTheNovaLocksNothing() {
        val nova = TrackpadSettings.landscapeHandheld(landscapeByNature = false, builtInGamepad = true)
        for (direction in PadDirection.entries) {
            assertEquals(-1, TrackpadSettings.screenOrientation(PadMode.SPATIAL_MOUSE, direction, nova))
        }
        // Held landscape (OS forced or the system's own): the landscape page. Turned to portrait by the user: the portrait page.
        assertTrue(TrackpadSettings.landscapePage(PadMode.SPATIAL_MOUSE, nova, landscapeNow = true))
        assertFalse(TrackpadSettings.landscapePage(PadMode.SPATIAL_MOUSE, nova, landscapeNow = false))
        // Its other pages lock as before.
        assertEquals(1, TrackpadSettings.screenOrientation(PadMode.VERTICAL, PadDirection.DEFAULT, nova))
        assertEquals(0, TrackpadSettings.screenOrientation(PadMode.HORIZONTAL, PadDirection.DEFAULT, nova))
        assertEquals(8, TrackpadSettings.screenOrientation(PadMode.HORIZONTAL, PadDirection.INVERTED, nova))
        assertTrue(TrackpadSettings.landscapePage(PadMode.HORIZONTAL, nova, landscapeNow = false))
        // The Nova's default page is the Horizontal Trackpad (1.1.2), which 1.1.8 never gave it.
        assertEquals(PadMode.HORIZONTAL, TrackpadSettings.modeOf(-1, nova))
    }

    /** A phone: Spatial Mouse is portrait as always, its page portrait even if the screen is landscape for a moment. */
    @Test fun spatialMouseOnAPhoneIsPortrait() {
        val phone = TrackpadSettings.landscapeHandheld(landscapeByNature = false, builtInGamepad = false)
        assertEquals(1, TrackpadSettings.screenOrientation(PadMode.SPATIAL_MOUSE, PadDirection.DEFAULT, phone))
        assertFalse(TrackpadSettings.landscapePage(PadMode.SPATIAL_MOUSE, phone, landscapeNow = true))
        assertFalse(TrackpadSettings.landscapePage(PadMode.SPATIAL_MOUSE, phone))
    }
}
