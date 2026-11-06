package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.MouseReport
import com.hayeco.flipspaces.core.pointer.PhysicalMouse
import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_PRIMARY
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mike, 1.1.17-mouse-test: "after clicking in a window and typing, [the cursor] moves to another window if i look toward
 * another window even though i have head cursor setting off". Replayed: a mouse click in window A, keys typed with the
 * hands on the keyboard in the Aura's camera (a hand cursor, and a stray pinch), the head turned toward window B.
 */
class MouseHeadTurnTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val a = SpacePanel(1, "Notes", "com.example.notes", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val b = SpacePanel(2, "Browser", "com.example.browser", "Main", yawDeg = 50f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(a, b))

    private class Rig(val pointer: SpatialPointer, val mouse: PhysicalMouse, val layout: SpaceLayout) {
        var t = 0L
        var head = Quat.IDENTITY
        val actions = mutableListOf<SpaceAction>()

        fun send(buttons: Int = 0, dx: Float = 0f, dy: Float = 0f): List<SpaceAction> {
            t += 20
            val out = mouse.report(MouseReport(buttons, dx, dy, timeMs = t)).flatMap { handle(it) } + pointer.frame(head, layout, t)
            actions += out
            return out
        }

        fun handle(s: PointerSignal): List<SpaceAction> = pointer.handle(s, head, layout, { 1280 to 720 }).also { actions += it }

        fun frame(): List<SpaceAction> {
            t += 16
            return pointer.frame(head, layout, t).also { actions += it }
        }
    }

    private fun clickInA(follows: Boolean, mouse: Boolean = true): Rig {
        val p = SpatialPointer(frustum)
        p.cursorFollowsHead = follows
        val rig = Rig(p, PhysicalMouse(), layout)
        rig.frame()
        // A little right of A's centre, so the pointer is not simply the head's forward.
        rig.send(dx = 4f / rig.mouse.degPerCount)
        // The Space sees the mouse ([SpatialPointer.mouseLeads]) from its first event.
        p.mouseLeads = mouse
        assertEquals(1, p.hover?.panel?.id)
        val click = rig.send(BUTTON_PRIMARY) + rig.send(0)
        assertTrue(click.any { it is SpaceAction.Focus && it.panelId == 1 })
        rig.actions.clear()
        return rig
    }

    /** The left hand on the keyboard, seen by the glasses' camera: a ray a little below the head's forward. */
    private fun hand(rig: Rig, s: PointerSignal) {
        rig.handle(PointerSignal.ForHand(HandSide.LEFT, s, Vec3(0f, -0.1f, 0f), Vec3(0f, -0.05f, -1f).normalized()))
    }

    /** Typing: the keys go to the app, nothing of them reaches the pointer; the hands move over the keyboard. */
    private fun type(rig: Rig) {
        repeat(30) {
            hand(rig, PointerSignal.MoveTo(960f, 600f))
            rig.frame()
        }
        // The Space hides the idle dot (its auto hide).
        rig.pointer.hoverSuspended = true
        repeat(30) { rig.frame() }
    }

    /** The head turned 50 degrees right, onto B, over half a second, the hands still on the keyboard; then a stray pinch. */
    private fun lookAtB(rig: Rig) {
        for (i in 1..30) {
            rig.head = Quat.fromYawPitch(50f * i / 30f, 0f)
            hand(rig, PointerSignal.MoveTo(960f, 600f))
            rig.frame()
        }
        hand(rig, PointerSignal.Down(pinch = true))
        rig.frame()
        hand(rig, PointerSignal.Up())
        rig.frame()
    }

    private fun pointedPanel(rig: Rig): Int? =
        PanelHitTester.nearest(rig.pointer.ray(rig.head), rig.layout.panels)?.panel?.id

    private fun touchedB(rig: Rig): Boolean =
        rig.actions.any { (it is SpaceAction.Focus && it.panelId == 2) || (it is SpaceAction.Touch && it.panelId == 2) }

    @Test
    fun withCursorFollowsHeadOffThePointerStaysOnTheClickedWindow() {
        val rig = clickInA(follows = false)
        assertTrue(rig.pointer.handsSetAside)
        type(rig)
        lookAtB(rig)
        assertTrue("no hand cursor takes the mouse's place", rig.pointer.activeHands.isEmpty())
        assertEquals("the ray still on A", 1, pointedPanel(rig))
        assertFalse("the stray pinch pressed nothing, so the keys stay with A", touchedB(rig))
        assertTrue(rig.actions.none { it is SpaceAction.Touch || it is SpaceAction.Focus })
        // The dot shows again when the mouse is touched: on A, and a nudge moves it from there, not from the gaze.
        rig.pointer.hoverSuspended = false
        rig.frame()
        assertEquals(1, rig.pointer.hover?.panel?.id)
        rig.send(dx = 1f)
        assertEquals(1, rig.pointer.hover?.panel?.id)
        assertEquals(1, pointedPanel(rig))
    }

    @Test
    fun withCursorFollowsHeadOnThePointerFollowsTheHead() {
        val rig = clickInA(follows = true)
        assertFalse(rig.pointer.handsSetAside)
        type(rig)
        lookAtB(rig)
        // Today's behaviour: the pointer turns with the head onto B, and the hand is a cursor of its own.
        assertEquals(2, pointedPanel(rig))
        assertEquals(listOf(HandSide.LEFT), rig.pointer.activeHands)
    }

    @Test
    fun withoutAMouseTheHandsWorkAsBefore() {
        val rig = clickInA(follows = false, mouse = false)
        assertFalse(rig.pointer.handsSetAside)
        type(rig)
        assertEquals(listOf(HandSide.LEFT), rig.pointer.activeHands)
        // The cursor still stays where it was in the room as the head turns.
        lookAtB(rig)
        assertEquals(1, pointedPanel(rig))
    }

    @Test
    fun turningCursorFollowsHeadOnBringsTheHandsBack() {
        val rig = clickInA(follows = false)
        type(rig)
        assertTrue(rig.pointer.activeHands.isEmpty())
        rig.pointer.cursorFollowsHead = true
        hand(rig, PointerSignal.MoveTo(960f, 600f))
        assertEquals(listOf(HandSide.LEFT), rig.pointer.activeHands)
    }

    @Test
    fun aHandAlreadyAimingIsLetGoWhenTheMouseTakesOver() {
        val p = SpatialPointer(frustum)
        p.cursorFollowsHead = false
        val rig = Rig(p, PhysicalMouse(), layout)
        hand(rig, PointerSignal.MoveTo(960f, 600f))
        rig.frame()
        assertEquals(listOf(HandSide.LEFT), p.activeHands)
        rig.send(dx = 10f)
        p.mouseLeads = true
        val out = rig.frame()
        assertTrue(p.activeHands.isEmpty())
        assertTrue(out.any { it is SpaceAction.Note && it.line.contains("set aside") })
    }
}
