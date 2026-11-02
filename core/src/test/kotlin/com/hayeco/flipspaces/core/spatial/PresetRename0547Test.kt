package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.5.47 (Mike: "users should be able to rename their window presets"). */
class PresetRename0547Test {
    private val layout = SpaceLayout(listOf(SpacePanel(1, "YouTube", "com.google.android.youtube", "Main", yawDeg = 0f, pitchDeg = 0f)))
    private val presets = listOf(WindowPreset(1, "Workspace 1", layout), WindowPreset(2, "Workspace 2", layout))

    @Test
    fun aRenameKeepsToOneCleanLineAndSurvivesSaving() {
        val renamed = WindowPresets.rename(presets, 2, "  Movie\tnight\n ")
        assertEquals(listOf("Workspace 1", "Movie night"), renamed.map { it.name })
        assertEquals(renamed.map { it.name }, WindowPresets.decode(WindowPresets.encode(renamed)).map { it.name })
        assertEquals("a blank name changes nothing", presets, WindowPresets.rename(presets, 1, "   "))
        assertEquals(WindowPresets.MAX_NAME, WindowPresets.rename(presets, 1, "x".repeat(80)).first().name.length)
    }
}
