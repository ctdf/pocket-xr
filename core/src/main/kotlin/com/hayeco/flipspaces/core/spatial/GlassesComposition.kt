package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.16: what keeps the Space off a hardware overlay on the glasses.
 *
 * SurfaceFlinger's HWC table for the XREAL display had the Space's 3840x1080 SurfaceView as
 * CLIENT (composed again on the GPU every frame), with DeX's privacy dot overlay (CLIENT) and the
 * full screen "Pocket XR cursor" window (DEVICE) above it. The cursor window took a hardware
 * plane for nothing while the Space was up: the Space draws its own cursor and the window was
 * empty but for the odd banner. Now it is hidden while the Space is up and nothing is on it
 * ([CursorWindow]), and the table is read once per side by side session and logged ([parse],
 * [line]) so the next test shows DEVICE or CLIENT directly.
 */
object CursorWindow {
    /**
     * Whether the cursor overlay window is shown at all: always without the Space; with it, only
     * while it has something the Space does not draw (a banner, the dock, a move or resize preview).
     */
    fun shown(spaceUp: Boolean, banner: Boolean, dock: Boolean, preview: Boolean): Boolean = !spaceUp || banner || dock || preview
}

object HwcComposition {
    /** One row of a display's HWC layer table: its name, composition type and display frame. */
    data class Layer(val name: String, val comp: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /**
         * 0.5.17: the Space's SurfaceView. The
         * table's name is cut short in the middle (`com.hayeco.flips[...]patial`) and may follow a
         * hash (`d63b35b SurfaceView[...]`), so it is a SurfaceView with "hayeco" in its name
         * ([name] is already without the hash).
         */
        val isSpace: Boolean get() = name.startsWith("SurfaceView[") && "hayeco" in name
    }

    data class Display(val id: String, val layers: List<Layer>)

    /** 0.5.17: `HWC layers:` (0.5.16's test table) or `HWC layers (top to bottom):` (the build on the Flip). */
    private val header = Regex("""^Display (\S+).*HWC layers(\s*\(([^)]*)\))?:""")
    private val comps = setOf("DEVICE", "CLIENT", "SOLID_COLOR", "CURSOR", "SIDEBAND", "DISPLAY_DECORATION", "REFRESH_RATE_INDICATOR", "INVALID")

    /** 0.5.17: a leading layer hash on the name line (`d63b35b SurfaceView[...]`). */
    private val hashPrefix = Regex("""^[0-9a-fA-F]{6,16}\s+""")

    /**
     * Every display's HWC layer table in a `dumpsys SurfaceFlinger` output, bottom layer first.
     * 0.5.17: a table headed `(top to bottom)` is listed top first, so it is reversed here.
     */
    fun parse(dump: String): List<Display> {
        val out = ArrayList<Display>()
        var id: String? = null
        var topFirst = false
        var layers = ArrayList<Layer>()
        var lastName: String? = null
        fun close() {
            id?.let { out += Display(it, if (topFirst) layers.reversed() else layers) }
            id = null
            layers = ArrayList()
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            val h = header.find(line.trim())
            if (h != null) {
                close()
                id = h.groupValues[1]
                topFirst = h.groupValues[3].trim().lowercase(Locale.ROOT).startsWith("top")
                lastName = null
                continue
            }
            if (id == null) continue
            if (line.isBlank()) continue
            val cells = line.split('|').map { it.trim() }
            val comp = cells.getOrNull(2)?.uppercase(Locale.ROOT)
            if (cells.size >= 5 && comp in comps && (cells[0].startsWith("rel") || cells[0].firstOrNull()?.isDigit() == true)) {
                val frame = cells[4].split(Regex("\\s+")).mapNotNull { it.toFloatOrNull()?.toInt() }
                val name = lastName ?: "?"
                if (frame.size >= 4) layers += Layer(name, comp!!, frame[0], frame[1], frame[2], frame[3]) else layers += Layer(name, comp!!, 0, 0, 0, 0)
                lastName = null
                continue
            }
            if (line.startsWith("---") || line.trim().startsWith("Layer name") || line.trim().startsWith("Z |")) continue
            // A table row's name sits on the line before it.
            if (!line.contains('|')) lastName = line.trim().replaceFirst(hashPrefix, "")
        }
        close()
        return out
    }

    /** The display holding the Space's SurfaceView, or null. */
    fun spaceDisplay(displays: List<Display>): Display? = displays.firstOrNull { d -> d.layers.any { it.isSpace } }

    /** One line: how the Space is composed and what sits above it. */
    fun line(displays: List<Display>): String {
        val d = spaceDisplay(displays) ?: return "glasses composition: the Space's SurfaceView is not in SurfaceFlinger's HWC tables (${displays.size} displays read)"
        val i = d.layers.indexOfFirst { it.isSpace }
        val space = d.layers[i]
        val above = d.layers.drop(i + 1)
        val client = d.layers.filter { it.comp == "CLIENT" }
        val verdict = when {
            space.comp == "DEVICE" -> "on a hardware overlay"
            space.comp == "CLIENT" -> "composed on the GPU (CLIENT)"
            else -> space.comp
        }
        fun desc(l: Layer) = "%s %s %dx%d".format(Locale.ROOT, l.name.take(60), l.comp, l.width, l.height)
        return "glasses composition (display %s): the Space %dx%d is %s; above it: %s; %d CLIENT layers on the display%s".format(
            Locale.ROOT, d.id, space.width, space.height, verdict,
            if (above.isEmpty()) "nothing" else above.joinToString("; ") { desc(it) },
            client.size,
            if (space.comp == "CLIENT") ", so every Space frame is copied again on the GPU" else "",
        )
    }
}
