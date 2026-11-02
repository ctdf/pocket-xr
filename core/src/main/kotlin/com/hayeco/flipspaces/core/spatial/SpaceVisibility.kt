package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.10: one window of `dumpsys window windows`, as One UI 8 prints it (the 0.4.8 dump on
 * the phone): `Window #5 Window{8cd212 u0 pkg/pkg.spatial.SpaceActivity}:`, then
 * `mDisplayId=12 ...`, `mViewVisibility=0x0 ... mObscured=false`, `Frames: ... frame=[0,0][1920,1080]`,
 * `Surface: shown=true mDrawState=HAS_DRAWN ...`, `isOnScreen=true` and `isVisible=true`.
 */
data class WmWindow(
    val title: String,
    val displayId: Int?,
    val onScreen: Boolean?,
    val visible: Boolean?,
    val surfaceShown: Boolean?,
    val obscured: Boolean?,
    /** left, top, right, bottom of `frame=`; null when the dump has none. */
    val frame: IntArray? = null,
) {
    /** The window manager shows it: on screen, visible, its surface shown, not covered. */
    val shown: Boolean get() = onScreen == true && visible == true && surfaceShown != false && obscured != true

    /** The window manager says it is not shown (positive evidence, not a missing field). */
    val hidden: Boolean get() = onScreen == false || visible == false || surfaceShown == false || obscured == true

    override fun equals(other: Any?): Boolean = other is WmWindow && title == other.title && displayId == other.displayId &&
        onScreen == other.onScreen && visible == other.visible && surfaceShown == other.surfaceShown && obscured == other.obscured &&
        (frame?.toList() == other.frame?.toList())

    override fun hashCode(): Int = title.hashCode() * 31 + (displayId ?: -1)

    fun line(): String = "$title (" + listOfNotNull(
        onScreen?.let { "on screen $it" },
        visible?.let { "visible $it" },
        surfaceShown?.let { "surface shown $it" },
        obscured?.takeIf { it }?.let { "covered" },
        frame?.let { "frame ${it[0]},${it[1]} to ${it[2]},${it[3]}" },
    ).joinToString(", ") + ")"
}

object WindowDump {
    private val HEADER = Regex("""^\s*Window #\d+ Window\{[0-9a-f]+ u\d+ (.*)\}:\s*$""")
    private val DISPLAY = Regex("""\bmDisplayId=(\d+)""")
    private val ON_SCREEN = Regex("""^\s*isOnScreen=(true|false)""")
    private val VISIBLE = Regex("""^\s*isVisible=(true|false)""")
    private val SHOWN = Regex("""\bSurface: shown=(true|false)""")
    private val OBSCURED = Regex("""\bmObscured=(true|false)""")
    private val FRAME = Regex("""\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    /** Every window of a `dumpsys window windows` text, in the dump's order (top first). */
    fun parse(dump: String?): List<WmWindow> {
        if (dump.isNullOrBlank()) return emptyList()
        val out = mutableListOf<WmWindow>()
        var cur: WmWindow? = null
        for (line in dump.lineSequence()) {
            val h = HEADER.find(line)
            if (h != null) {
                cur?.let { out += it }
                cur = WmWindow(h.groupValues[1].trim(), null, null, null, null, null)
                continue
            }
            val w = cur ?: continue
            var next = w
            if (w.displayId == null) DISPLAY.find(line)?.let { next = next.copy(displayId = it.groupValues[1].toInt()) }
            ON_SCREEN.find(line)?.let { next = next.copy(onScreen = it.groupValues[1] == "true") }
            VISIBLE.find(line)?.let { next = next.copy(visible = it.groupValues[1] == "true") }
            SHOWN.find(line)?.let { next = next.copy(surfaceShown = it.groupValues[1] == "true") }
            OBSCURED.find(line)?.let { next = next.copy(obscured = it.groupValues[1] == "true") }
            if (w.frame == null) FRAME.find(line)?.let { m -> next = next.copy(frame = IntArray(4) { m.groupValues[it + 1].toInt() }) }
            cur = next
        }
        cur?.let { out += it }
        return out
    }
}

/**
 * 0.5.10: whether the glasses show the Space, from every source there is, and never "not seen"
 * without positive evidence.
 *
 * 1. SurfaceFlinger's composed layers ([SurfaceFlingerLayers.check]): composed or not composed on
 *    the glasses' physical display. The best answer, when the dump has a table.
 * 2. Without one (Mike's One UI 8 / Android 16 dump: "no HWC layer table"), the window manager:
 *    the Space's window on that display, on screen, visible, surface shown and not covered counts
 *    as seen; the window there and hidden, or no Space window on that display at all while the
 *    dump lists that display's windows, is positive evidence it is not seen.
 * 3. Neither readable: unknown, which the check never treats as "not seen" ([VisibleCheck]).
 *
 * The window manager can be wrong in one way (0.5.6: a task the desktop shell hid is still
 * "visible" to it), so a window manager "seen" keeps what is there rather than proving it; it can
 * no longer make the Space reload because a dump format changed.
 */
object SpaceVisibility {
    const val WM_PREFIX = "window manager: "

    fun judge(sf: OnScreen, windows: List<WmWindow>, displayId: Int, packageName: String, activity: String): OnScreen {
        if (sf !is OnScreen.Unknown) return sf
        val onDisplay = windows.filter { it.displayId == displayId }
        if (onDisplay.isEmpty()) return OnScreen.Unknown("${sf.why}; the window manager lists no window on display $displayId")
        val space = onDisplay.filter { SurfaceFlingerLayers.isSpace(it.title, packageName, activity) }
        space.firstOrNull { it.shown }?.let { return OnScreen.Composed("$WM_PREFIX${it.line()}", displayId.toString()) }
        if (space.isNotEmpty() && space.all { it.hidden }) {
            return OnScreen.NotComposed(displayId.toString(), listOf("$WM_PREFIX${space.first().line()}") + topShown(onDisplay))
        }
        if (space.isEmpty()) {
            return OnScreen.NotComposed(displayId.toString(), listOf("${WM_PREFIX}no window of the Space on display $displayId") + topShown(onDisplay))
        }
        return OnScreen.Unknown("${sf.why}; the window manager is not sure (${space.first().line()})")
    }

    private fun topShown(windows: List<WmWindow>): List<String> = windows.filter { it.shown }.take(4).map { it.title }

    /**
     * DeX's chrome from the window manager when SurfaceFlinger has no table: the taskbar is a
     * window (`DexTaskbarWindow`); the caption is drawn by the desktop shell inside the task and is
     * no window, so it is read from the Space's own frame: content that starts below the display's
     * top edge sits under a title bar. Null without a Space window on [displayId].
     */
    fun chrome(windows: List<WmWindow>, displayId: Int, packageName: String, activity: String): DexChrome? {
        val onDisplay = windows.filter { it.displayId == displayId }
        val space = onDisplay.firstOrNull { SurfaceFlingerLayers.isSpace(it.title, packageName, activity) && it.shown } ?: return null
        val taskbar = onDisplay.any { w -> w.shown && (w.title.contains("DexTaskbar", true) || w.title.contains("Taskbar", true)) }
        val caption = (space.frame?.get(1) ?: 0) > 0
        return DexChrome(taskbar, caption, outline = false)
    }
}
