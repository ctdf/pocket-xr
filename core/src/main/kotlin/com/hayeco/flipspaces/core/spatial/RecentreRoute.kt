package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.16: one recentre per press. 0.5.15's phone Recentre button sent the
 * Space a Recentre command (which the renderer turns into a tracker recentre) and also called the
 * tracker itself, so every press recentred twice, 12 ms apart, and the second applied the roll and
 * pitch relevel again. Now the press goes one way ([paths]), and the tracker takes at most one
 * request per [Debounce.windowMs] whatever sends it (the glasses' key, the launcher tile, the phone).
 */
object RecentreRoute {
    enum class Path { SPACE_COMMAND, TRACKER }

    /** How the phone's Recentre button reaches the tracker: through the Space when it runs (it also centres its pointer), else directly. */
    fun paths(spaceRunning: Boolean): List<Path> = listOf(if (spaceRunning) Path.SPACE_COMMAND else Path.TRACKER)

    /** Requests closer than [windowMs] to the last accepted one are one press. Not thread safe. */
    class Debounce(val windowMs: Long = 300) {
        private var lastMs = Long.MIN_VALUE

        var dropped = 0
            private set

        fun accept(nowMs: Long): Boolean {
            if (lastMs != Long.MIN_VALUE && nowMs - lastMs < windowMs) {
                dropped++
                return false
            }
            lastMs = nowMs
            return true
        }
    }
}
