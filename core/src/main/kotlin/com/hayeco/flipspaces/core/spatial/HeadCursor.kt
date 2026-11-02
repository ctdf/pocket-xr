package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.32 (Mike: "I like the head tracking cursor dot but it's always showing even when I'm not using it; there should
 * be a way to disable or enable it in settings"): how the head cursor (the trackpad's dot, with no hand up) shows.
 */
enum class HeadCursorMode(val key: String, val label: String) {
    AUTO("auto", "AUTO-HIDE"),
    ALWAYS("always", "ALWAYS"),
    OFF("off", "OFF");

    /** The Menu row's next choice: auto hide, always, off, then auto hide again. */
    fun next(): HeadCursorMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?): HeadCursorMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * The head cursor's opacity. [HeadCursorMode.AUTO]: hidden until the trackpad or pointer is used, full for [SHOW_MS]
 * after the last input (or the last frame something was pressed or held, [alpha]'s active), then a [FADE_MS] fade.
 * [HeadCursorMode.ALWAYS] is 0.5.31's dot; [HeadCursorMode.OFF] never draws it.
 */
class HeadCursorVisibility(var mode: HeadCursorMode = HeadCursorMode.AUTO) {
    private var lastInputMs = Long.MIN_VALUE

    /** The trackpad or the pointer was used at [nowMs]. */
    fun input(nowMs: Long) {
        lastInputMs = nowMs
    }

    /** The dot's opacity at [nowMs]; [active] while a press or a held window is under way. */
    fun alpha(nowMs: Long, active: Boolean): Float {
        when (mode) {
            HeadCursorMode.ALWAYS -> return 1f
            HeadCursorMode.OFF -> return 0f
            HeadCursorMode.AUTO -> Unit
        }
        if (active) lastInputMs = nowMs
        if (lastInputMs == Long.MIN_VALUE) return 0f
        val quiet = nowMs - lastInputMs - SHOW_MS
        return when {
            quiet <= 0L -> 1f
            quiet >= FADE_MS -> 0f
            else -> 1f - quiet.toFloat() / FADE_MS
        }
    }

    companion object {
        const val SHOW_MS = 2_500L
        const val FADE_MS = 300L
    }
}
