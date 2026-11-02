package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.69: the glass capsules behind a window's round buttons. */
class WindowCapsules0569Test {
    private val app = SpacePanel(id = 1, label = "Chrome", packageName = "com.android.chrome", activityName = "", yawDeg = 0f, pitchDeg = 0f, widthDeg = 40f, heightDeg = 25f, distanceM = 1.5f)

    @Test
    fun anAppWindowHasThreeCapsulesWrappingTheirButtons() {
        val l = WindowControls.layout(app)
        val caps = WindowControls.capsules(app, l)
        assertEquals(3, caps.size)
        for (c in caps) for (control in c.controls) {
            val p = l.of(control)
            assertTrue(c.u0 < p.u0 && c.u1 > p.u1 && c.v0 < p.v0 && c.v1 > p.v1)
        }
        // 0.5.85: Switch app joins the right capsule on an app's window.
        assertEquals(listOf(WindowControls.Control.SWITCH_APP, WindowControls.Control.REFRESH, WindowControls.Control.RESET, WindowControls.Control.CLOSE), caps[1].controls)
    }

    @Test
    fun theMovePillIsInNoCapsuleAndNoCapsuleReachesIt() {
        val l = WindowControls.layout(app)
        val move = l.of(WindowControls.Control.MOVE)
        for (c in WindowControls.capsules(app, l)) {
            assertTrue(WindowControls.Control.MOVE !in c.controls)
            if (c.v1 < 0f) assertTrue(c.u1 < move.u0 || c.u0 > move.u1)
        }
    }

    @Test
    fun aFlipSpacesWindowHasNoBottomCapsule() {
        val own = app.copy(packageName = "")
        assertEquals(2, WindowControls.capsules(own).size)
    }

    @Test
    fun capsulesAreRoundAtTheirEnds() {
        val l = WindowControls.layout(app)
        val bodyH = app.heightM
        for (c in WindowControls.capsules(app, l)) assertEquals((c.v1 - c.v0) * bodyH / 2f, c.radiusM, 1e-5f)
    }
}
