package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedContentTest {
    @Test
    fun blackWhileTheAppKeepsDrawingIsProtected() {
        val d = BlackFrameDetector(blackForMs = 2500, minFrames = 10)
        assertFalse(d.sample(0f, 30, 0))
        assertFalse(d.sample(0f, 30, 1000))
        assertFalse(d.sample(0.01f, 30, 2000))
        assertTrue(d.sample(0f, 30, 2600))
        assertTrue(d.protected)
    }

    @Test
    fun anyBrightPixelStartsOver() {
        val d = BlackFrameDetector(blackForMs = 2500, minFrames = 10)
        d.sample(0f, 30, 0)
        d.sample(0.5f, 30, 2000)
        assertFalse(d.sample(0f, 30, 3000))
        assertTrue(d.sample(0f, 30, 5600))
        // The app shows something again: no longer protected.
        assertFalse(d.sample(0.4f, 30, 6000))
        assertFalse(d.protected)
        assertFalse(d.blackLongEnough)
    }

    @Test
    fun anIdleBlackPanelIsNotCounted() {
        val d = BlackFrameDetector(blackForMs = 2500, minFrames = 10)
        d.sample(0f, 0, 0)
        d.sample(0f, 2, 2000)
        assertFalse(d.blackLongEnough)
        assertFalse(d.sample(0f, 2, 4000))
        // Long enough to ask the window manager, but not protected by itself.
        assertTrue(d.blackLongEnough)
        assertTrue(d.sample(0f, 10, 5000))
        d.reset()
        assertFalse(d.protected)
    }

    private val dump = """
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{1a2b3c u0 com.netflix.mediaclient/com.netflix.mediaclient.ui.player.PlayerActivity}:
    mDisplayId=14 rootTaskId=31 mSession=Session{77 1234:u0a321} mClient=android.os.BinderProxy@1
    mOwnerUid=10321 showForAllUsers=false package=com.netflix.mediaclient appop=NONE
    mAttrs={(0,0)(fillxfill) sim={adjust=pan} ty=BASE_APPLICATION fmt=TRANSPARENT
      fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SECURE SPLIT_TOUCH HARDWARE_ACCELERATED
  Window #1 Window{4d5e6f u0 com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity}:
    mDisplayId=15 rootTaskId=32
    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION
      fl=LAYOUT_IN_SCREEN SPLIT_TOUCH HARDWARE_ACCELERATED
  Window #2 Window{7a8b9c u0 com.bank.app/com.bank.app.Main}:
    mDisplayId=15 rootTaskId=33
      fl=SECURE
    """.trimIndent()

    @Test
    fun secureWindowsAreFoundPerDisplay() {
        assertEquals(setOf("com.netflix.mediaclient"), SecureWindows.securePackages(dump, 14))
        assertEquals(setOf("com.bank.app"), SecureWindows.securePackages(dump, 15))
        assertTrue(SecureWindows.securePackages(dump, 8).isEmpty())
        assertTrue(SecureWindows.securePackages("", 14).isEmpty())
    }

    @Test
    fun theOfferNamesTheReason() {
        assertTrue(ProtectedOffer.message("Netflix", secure = true).contains("FLAG_SECURE"))
        assertTrue(ProtectedOffer.message("Netflix", secure = false).contains("DRM"))
        assertTrue(ProtectedOffer.message("Netflix", secure = false).contains("DeX window"))
    }
}
