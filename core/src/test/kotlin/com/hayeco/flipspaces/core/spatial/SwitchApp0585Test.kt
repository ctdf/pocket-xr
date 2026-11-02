package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.85 (Mike: a Switch app button on each window, its pinned apps and All apps). */
class SwitchApp0585Test {
    private val apps = listOf(AppEntry("Chrome", "c", "C"), AppEntry("Discord", "d", "D"), AppEntry("Maps", "m", "M"))

    @Test
    fun eachPinnedAppIsASquareTileThatAPressFinds() {
        val aspect = 1.6f
        val card = PanelCard.SwitchApp("Messages", apps, aspect)
        val tiles = card.buttons.filter { it.kind == CardAction.SWITCH_TO }
        assertEquals(3, tiles.size)
        for ((i, t) in tiles.withIndex()) {
            // Square as the eyes see it: as wide in metres as tall.
            assertEquals(t.v1 - t.v0, (t.u1 - t.u0) * aspect, 1e-4f)
            assertEquals(apps[i], card.buttonAt((t.u0 + t.u1) / 2f, (t.v0 + t.v1) / 2f)?.app)
        }
        // Inside the card, left to right, not overlapping.
        for (i in 1 until tiles.size) assertTrue(tiles[i].u0 > tiles[i - 1].u1)
        assertTrue(tiles.all { it.u0 >= card.box[0] && it.u1 <= card.box[2] && it.v1 <= card.box[3] })
    }

    @Test
    fun allAppsAndCancelAreThere() {
        val card = PanelCard.SwitchApp("Messages", emptyList(), 1.6f)
        val all = card.buttons.single { it.kind == CardAction.ALL_APPS }
        assertEquals(CardAction.ALL_APPS, card.hit((all.u0 + all.u1) / 2f, (all.v0 + all.v1) / 2f))
        assertTrue(card.buttons.any { it.kind == CardAction.KEEP })
    }

    @Test
    fun anAppWindowHasTheSwitchButtonLeftOfRefresh() {
        val w = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f, widthDeg = 40f, heightDeg = 25f)
        val l = WindowControls.layout(w)
        assertTrue(l.of(WindowControls.Control.SWITCH_APP).u1 < l.of(WindowControls.Control.REFRESH).u0)
    }
}
