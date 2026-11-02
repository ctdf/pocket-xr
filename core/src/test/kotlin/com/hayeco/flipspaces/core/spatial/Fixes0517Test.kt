package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.17, from the vc37 retest: the prepare pass (panel copies,
 * mipmaps, resizes) took about 13 ms of GPU a frame in side by side, and the composition probe read 0
 * tables. The yaw fixes are in [Fixes0517YawTest].
 */
class Fixes0517Test {
    @Test
    fun aPanelIsCopiedOnlyWithANewImage() {
        // Alexa at 0 to 1 fps: in view every frame, a new image about once a second.
        val counts = PanelPrepareCounts()
        var lastCopy = 0L
        var copies = 0
        for (frame in 0 until 300) {
            val now = 1000L + frame * 16L
            val hasNew = frame % 60 == 0
            val c = PanelPrepare.copy(hasNew, visible = true, capFps = 60, nowMs = now, lastCopyMs = lastCopy)
            if (c == PanelPrepare.Copy.COPIED) {
                lastCopy = now
                copies++
            }
            counts.copy(c)
        }
        assertEquals(5, copies)
        assertEquals(PanelPrepare.Copy.OUT_OF_VIEW, PanelPrepare.copy(true, false, 60, 5000, 0))
        // A new image inside the thermal cap's interval waits for the cap.
        assertEquals(PanelPrepare.Copy.HELD_TO_CAP, PanelPrepare.copy(true, true, 30, 1010, 1000))
        assertEquals(PanelPrepare.Copy.COPIED, PanelPrepare.copy(true, true, 30, 1040, 1000))
        counts.chain(3, forBlackCheck = false)
        counts.chain(12, forBlackCheck = true)
        val line = counts.line()
        println(line)
        assertTrue(line, line.startsWith("prepare: 5 panel copies, 295 skipped (295 no new image, 0 held to the cap, 0 out of view); 1 mip chains built (3.0 levels each), 1 for the black check"))
        // The counts start over with each perf line.
        assertTrue(counts.line().startsWith("prepare: 0 panel copies, 0 skipped"))
    }

    @Test
    fun aPanelNearOneToOneGetsNoMipChain() {
        val full = PanelMips.levels(2688, 1512)
        assertEquals(12, full)
        // Mike's panels: about 1.41 texels a glasses pixel. One level, bilinear.
        assertEquals(1, PanelPrepare.mipLevels(1.41f, full))
        assertEquals(1, PanelPrepare.mipLevels(1.0f, full))
        assertEquals(1, PanelPrepare.mipLevels(PanelMips.MIN_SHRINK, full))
        assertEquals(1, PanelPrepare.mipLevels(Float.NaN, full))
        // Shown smaller than half its pixels: only the levels a trilinear lookup there reads (and one more for an angle).
        assertEquals(3, PanelPrepare.mipLevels(2.0f, full))
        assertEquals(4, PanelPrepare.mipLevels(3.0f, full))
        assertEquals(5, PanelPrepare.mipLevels(8.0f, full))
        assertEquals(full, PanelPrepare.mipLevels(10_000f, full))
        // A chain only when more levels are needed than the copy has valid.
        assertFalse(PanelPrepare.buildChain(1, 1))
        assertTrue(PanelPrepare.buildChain(3, 1))
        assertFalse(PanelPrepare.buildChain(3, 3))
        assertFalse(PanelPrepare.buildChain(3, full))
        assertTrue(PanelPrepare.buildChain(4, 3))
    }

    @Test
    fun aPanelOverSixtyFramesBuildsChainsOnlyAfterItsCopies() {
        // A model of the prepare pass per frame, as SpaceRenderer.preparePanel runs it: one panel near
        // 1:1 with a new image every frame, one far panel (3 texels a pixel) at 1 fps.
        data class Tex(var built: Int = 1)
        val near = Tex()
        val far = Tex()
        var nearChains = 0
        var farChains = 0
        for (frame in 0 until 60) {
            near.built = 1 // a new copy every frame
            if (PanelPrepare.buildChain(PanelPrepare.mipLevels(1.41f, 12), near.built)) nearChains++
            if (frame % 60 == 0) far.built = 1
            val need = PanelPrepare.mipLevels(3f, 12)
            if (PanelPrepare.buildChain(need, far.built)) {
                far.built = need
                farChains++
            }
        }
        assertEquals(0, nearChains)
        assertEquals(1, farChains)
    }

    @Test
    fun aCpuTooSlowForTheSpaceIsNotTriedAgainThisSession() {
        // Mike's 0.5.16: GPU 24 ms, CPU 63 ms a frame while the Space drew: the GPU kept.
        val c = com.hayeco.flipspaces.core.hands.DelegateChooser(samples = 2, spaceFirst = true)
        c.record(true, 24, spaceDraws = true)
        assertEquals(false, c.record(true, 24, spaceDraws = true))
        c.record(false, 63, spaceDraws = true)
        assertEquals(true, c.record(false, 63, spaceDraws = true))
        assertEquals(63f, c.cpuTooSlowMs!!, 0.01f)
        // The next camera session: measured on the GPU, then kept without another CPU trial.
        c.reset()
        c.record(true, 26, spaceDraws = true)
        assertEquals(null, c.record(true, 26, spaceDraws = true))
        assertEquals(com.hayeco.flipspaces.core.hands.DelegateChooser.Phase.DONE, c.phase)
        assertTrue(c.keepGpu)
        assertTrue(c.line!!, c.line!!.contains("kept, the CPU took 63 ms a frame earlier this session"))
        // A GPU that became slower than that CPU would need to be is tried again.
        c.reset()
        c.record(true, 60, spaceDraws = true)
        assertEquals(false, c.record(true, 60, spaceDraws = true))
    }

    /**
     * The table as Mike's 0.5.16 `dumpsys SurfaceFlinger` had it on the XREAL display: listed top
     * to bottom, the DeX privacy dot (3840x56, DEVICE) above the Space's SurfaceView (3840x1080,
     * DEVICE), names after a layer hash and cut short in the middle; the cursor window not in it.
     */
    private val flipDump = """
Display 4626964206039778820 (active) HWC layers (top to bottom):
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 Layer name
           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) |     Frame Rate (Explicit) (Seamlessness) [Focused]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 a41c9e2 DexPrivacyDotOverlay#2471
  rel      0 |         2024 |     DEVICE |          0 |    0    0 3840   56 |    0.0    0.0 3840.0   56.0 |                                              [ ]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 d63b35b SurfaceView[com.hayeco.flips[...]patial.SpaceActivity]#2468(BLAST Consumer)2468
  rel      0 |            0 |     DEVICE |          0 |    0    0 3840 1080 |    0.0    0.0 3840.0 1080.0 |                                              [ ]
---------------------------------------------------------------------------------------------------------------------------------------------------------------

Display 4619827259835644672 (active) HWC layers (top to bottom):
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 Layer name
           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) |     Frame Rate (Explicit) (Seamlessness) [Focused]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 5f0e1aa StatusBar#77
  rel      0 |         2000 |     DEVICE |          0 |    0    0 1080   96 |    0.0    0.0 1080.0   96.0 |                                              [ ]
---------------------------------------------------------------------------------------------------------------------------------------------------------------
 0b7d2c4 Wallpaper#1
  rel      0 |         2013 |     DEVICE |          0 |    0    0 1080 2640 |    0.0    0.0 1080.0 2640.0 |                                              [ ]
""".trimIndent()

    @Test
    fun theCompositionProbeReadsTheFlipsTable() {
        val displays = HwcComposition.parse(flipDump)
        assertEquals(2, displays.size)
        val glasses = displays[0]
        assertEquals("4626964206039778820", glasses.id)
        // Bottom first after the parse: the Space, then the dot above it.
        assertEquals(listOf(true, false), glasses.layers.map { it.isSpace })
        assertEquals("DexPrivacyDotOverlay#2471", glasses.layers[1].name)
        assertEquals("Wallpaper#1", displays[1].layers[0].name)
        val line = HwcComposition.line(displays)
        println(line)
        assertTrue(line, line.startsWith("glasses composition (display 4626964206039778820): the Space 3840x1080 is on a hardware overlay; above it: DexPrivacyDotOverlay#2471 DEVICE 3840x56; 0 CLIENT layers"))
        // The same table with the Space composed on the GPU says so.
        val client = HwcComposition.line(HwcComposition.parse(flipDump.replace("0 |     DEVICE |          0 |    0    0 3840 1080", "0 |     CLIENT |          0 |    0    0 3840 1080")))
        assertTrue(client, client.contains("is composed on the GPU (CLIENT)") && client.contains("1 CLIENT layers"))
    }
}
