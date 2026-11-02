package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.5.37 (Mike: save preset app windows, reopened exactly as they were). */
class WindowPresets0537Test {
    private val layout = SpaceLayout(
        listOf(
            SpacePanel(1, "YouTube", "com.google.android.youtube", "com.google.android.youtube.HomeActivity", yawDeg = -20f, pitchDeg = 3f, distanceM = 2.4f, widthDeg = 42f, heightDeg = 23.6f, curve = 0.8f),
            SpacePanel(2, "Messages", "com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ConversationListActivity", yawDeg = 25f, pitchDeg = -4f, distanceM = 1.3f, widthDeg = 22f, heightDeg = 34f),
        ),
    )

    @Test
    fun aSavedPresetComesBackWithEveryWindowExactly() {
        val saved = WindowPresets.save(emptyList(), layout, 1_000L)!!
        val back = WindowPresets.decode(WindowPresets.encode(saved))
        assertEquals(1, back.size)
        assertEquals("Workspace 1", back[0].name)
        assertEquals("YouTube · Messages", back[0].apps)
        for ((a, b) in layout.panels.zip(back[0].layout.panels)) {
            assertEquals(a.packageName, b.packageName)
            assertEquals(a.activityName, b.activityName)
            assertEquals(a.yawDeg, b.yawDeg, 1e-3f)
            assertEquals(a.pitchDeg, b.pitchDeg, 1e-3f)
            assertEquals(a.distanceM, b.distanceM, 1e-3f)
            assertEquals(a.widthDeg, b.widthDeg, 1e-3f)
            assertEquals(a.heightDeg, b.heightDeg, 1e-3f)
            assertEquals(a.curve, b.curve, 1e-3f)
        }
    }

    @Test
    fun namesCountUpNewestFirstAndAtMostEight() {
        var list = emptyList<WindowPreset>()
        repeat(10) { list = WindowPresets.save(list, layout, it.toLong())!! }
        assertEquals(WindowPresets.MAX, list.size)
        assertEquals("Workspace 10", list.first().name)
        assertNull("no windows, nothing to save", WindowPresets.save(list, SpaceLayout(emptyList()), 0L))
    }
}
