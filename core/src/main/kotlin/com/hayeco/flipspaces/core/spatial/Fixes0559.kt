package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.59 (Mike: the left eye's picture ends in a fixed vertical line; findings left eye clip, fix 2): the picture adjust
 * the renderer applies. Its Menu rows have been gone since 0.5.36, so a value saved in the 0.5.27 to 0.5.35 tests was
 * still drawn with no way to see or change it. With no Menu for it ([MENU_HAS_ADJUST] false) nothing saved is applied,
 * and the Space clears the saved value when it starts.
 */
object ActiveAdjust {
    /** True while the Menu shows the picture adjust; it has not since 0.5.36. */
    const val MENU_HAS_ADJUST = false

    fun of(saved: PictureAdjust, menuHasAdjust: Boolean = MENU_HAS_ADJUST): PictureAdjust =
        if (menuHasAdjust) saved else PictureAdjust.NONE

    /** The log line for what was saved and what is applied. */
    fun line(glasses: String, saved: PictureAdjust, menuHasAdjust: Boolean = MENU_HAS_ADJUST): String = when {
        saved.neutral -> "picture adjust for $glasses: none"
        menuHasAdjust -> "picture adjust for $glasses: ${saved.line()} (applied)"
        else -> "picture adjust for $glasses: saved ${saved.line()}, not applied and cleared (no Menu since 0.5.36)"
    }
}
