package com.hayeco.flipspaces.game

/**
 * 4F build: what the Space tells the game screen (same process): where the cursor last was on a window, for touch
 * points, and how the last gamepad or touch send went, shown in the game screen's top bar.
 */
object GameStatus {
    data class Cursor(val window: String, val u: Float, val v: Float)

    @Volatile var cursor: Cursor? = null

    /** Called on the main thread with a line and whether it is a problem. */
    @Volatile var listener: ((String, Boolean) -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun report(line: String, problem: Boolean) {
        main.post { listener?.invoke(line, problem) }
    }
}
