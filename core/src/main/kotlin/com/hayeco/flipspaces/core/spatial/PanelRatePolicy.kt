package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.56 (in session A the Space waited 6 to 7 ms p99 behind other GPU work while another app's window
 * drew at 60 fps at a larger size, which pinned the late draw's margin at its cap; misses went from 0.3 to about 2 % and pose
 * to photons from 20 to 25.6 ms): windows that are not in use are asked for and copied at a lower rate.
 *
 * In use: under any cursor (the head's or a hand's), under the gaze, or held. Such a window, and one playing video, is at
 * [activeFps] at once. One left alone stays at [activeFps] for [graceMs] (a cursor passing over an edge does not flip it),
 * then [restFps], and [idleFps] once it has not been used for [idleAfterMs]. A window seen for the first time counts as used
 * now, so a new window opens at the full rate.
 *
 * GL thread only.
 */
class PanelRatePolicy(
    val activeFps: Int = ThermalGuard.FULL_FPS,
    val restFps: Int = 30,
    val idleFps: Int = 15,
    val graceMs: Long = 1_000L,
    val idleAfterMs: Long = 30_000L,
) {
    enum class Why(val words: String) {
        IN_USE("under the cursor or gaze"),
        VIDEO("playing video"),
        JUST_LEFT("just left"),
        REST("not in use"),
        IDLE("idle"),
    }

    data class Rate(val fps: Int, val why: Why)

    private val lastUsedMs = HashMap<Int, Long>()

    /** The rate for window [panelId] now. */
    fun rate(panelId: Int, inUse: Boolean, video: Boolean, nowMs: Long): Rate {
        if (inUse) lastUsedMs[panelId] = nowMs
        val used = lastUsedMs.getOrPut(panelId) { nowMs }
        return when {
            inUse -> Rate(activeFps, Why.IN_USE)
            video -> Rate(activeFps, Why.VIDEO)
            nowMs - used < graceMs -> Rate(activeFps, Why.JUST_LEFT)
            nowMs - used < idleAfterMs -> Rate(restFps, Why.REST)
            else -> Rate(idleFps, Why.IDLE)
        }
    }

    /** A closed window is forgotten. */
    fun forget(panelId: Int) {
        lastUsedMs.remove(panelId)
    }

    /** Only the windows still open are kept. */
    fun retain(open: Collection<Int>) {
        lastUsedMs.keys.retainAll(open.toSet())
    }
}
