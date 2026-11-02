package com.hayeco.flipspaces.core

/**
 * 0.5.6: one "FlipSpaces Space" task on the glasses. Mike's 0.5.5 dump of display 45 had two:
 * Task #8241 holding the Space, and an empty #8233 left under the DeX Desk by an earlier Space
 * whose process ended without onDestroy (an install over a running Space), which the new panel
 * service process did not know about. WindowManager logged ChangeInfo for both about twice a
 * second while they sat there.
 */
object SpaceTasks {
    /** The name the panel service gives its holding task (TaskCreationParams.setName). */
    const val NAME = "FlipSpaces Space"

    /** What the panel service knows of one root task on the glasses display. */
    data class RootTask(
        val taskId: Int,
        val childTasks: Int,
        /** numActivities; null when this Android does not say. */
        val activities: Int?,
        /** Its top or base activity, null when it has none. */
        val topActivity: String?,
        /** Named [NAME] in Android's dump, or a holder this service created. */
        val ours: Boolean,
    ) {
        val empty: Boolean get() = childTasks == 0 && (activities ?: 0) == 0 && topActivity == null
    }

    /**
     * The tasks to delete: ours, empty, and not [keepTaskId] (the holder in use). Nothing else is
     * ever deleted, not DeX's Desk, not a task with an activity, not one of another app.
     */
    fun leftovers(tasks: List<RootTask>, keepTaskId: Int?): List<Int> =
        tasks.filter { it.ours && it.empty && it.taskId != keepTaskId }.map { it.taskId }

    /**
     * Ids of tasks named [NAME] in `dumpsys activity activities` (or containers). Android prints a
     * task as `Task{... #8233 type=standard ...}` with the name on that line (`name=FlipSpaces
     * Space`, or quoted); a line without both is skipped.
     */
    fun namedIn(dump: String): Set<Int> {
        val out = HashSet<Int>()
        for (line in dump.lineSequence()) {
            if (!line.contains(NAME)) continue
            (HASH_ID.find(line) ?: TASK_ID.find(line))?.groupValues?.get(1)?.toIntOrNull()?.let { out += it }
        }
        return out
    }

    /**
     * 0.5.24 (the 5 s check said `1 ... layer still in SurfaceFlinger` for three displays whose layers
     * were gone at the final dump, and for display 90 it never ran, the service having exited 4.8 s after the release):
     * the layer list is read at each of these times after a release, and the service is held bound until the last.
     */
    val LAYER_CHECKS_MS = listOf(5_000L, 15_000L)

    /** 0.5.24: how long the panel service is held after a release, so its last layer check runs. */
    val LAYER_HOLD_MS: Long get() = LAYER_CHECKS_MS.last() + 2_000L

    /** 0.5.24: the check's line at [atMs] after the release of [displayId]; [last] when no check follows it. */
    fun layerCheckLine(displayId: Int, atMs: Long, left: List<String>, taskIds: Set<Int>, last: Boolean): String = when {
        left.isEmpty() -> "display $displayId released: no FlipSpaces Space layer left in SurfaceFlinger ${atMs / 1000} s later (tasks ${taskIds.joinToString()})"
        last -> "display $displayId released: ${left.size} FlipSpaces Space layer${if (left.size == 1) "" else "s"} still in SurfaceFlinger ${atMs / 1000} s later, the last check: ${left.joinToString()}"
        else -> "display $displayId released: ${left.size} FlipSpaces Space layer${if (left.size == 1) "" else "s"} still in SurfaceFlinger ${atMs / 1000} s later (SurfaceFlinger may not have let go yet; read again at ${LAYER_CHECKS_MS.last() / 1000} s): ${left.joinToString()}"
    }

    /**
     * 0.5.23: the `Task=N(FlipSpaces Space)#L` layers in a `dumpsys SurfaceFlinger --list`
     * for the tasks [taskIds], as they are listed; empty when none is left.
     */
    fun leftoverLayers(list: String, taskIds: Set<Int>): List<String> =
        list.lineSequence().map { it.trim() }.filter { line ->
            line.contains(NAME) && LAYER_TASK.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { it in taskIds } == true
        }.toList()

    private val LAYER_TASK = Regex("""Task=(\d+)""")

    /** 0.5.25: the task id a `Task=N(...)` layer line names, or null. */
    fun layerTaskId(line: String): Int? = LAYER_TASK.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 0.5.25: a layer line of `dumpsys SurfaceFlinger --list` with no parent
     * (`RequestedLayerState{Task=9475(FlipSpaces Space)#26532 z=1}`, no `parentId=`) is offscreen: it is composed
     * on no display. Every stale Space layer in vc45 was one, next to DeX's own Desk layers left the same way.
     */
    fun offscreen(line: String): Boolean = !line.contains("parentId=")

    /**
     * 0.5.25: the offscreen task layers in [list] that are not a Space's (DeX's Desk and MinimizedDesk, its task
     * layers), for the last check's line: they show the leftover is the system's, not only Pocket XR's.
     */
    fun otherOffscreenTaskLayers(list: String): Int =
        list.lineSequence().map { it.trim() }.count { it.startsWith("RequestedLayerState{Task=") && !it.contains(NAME) && offscreen(it) }

    /**
     * 0.5.25: what the last layer check does with the Space layers still left ([left]).
     * [remove]: the tasks of those layers that the window manager still has (named [NAME] in its dump,
     * [inWindowManager]) and that are this service's own ([taskIds]), and not a holder in use now ([inUse]): the
     * service removes them. [gone]: the tasks the window manager no longer has, whose layer another process holds.
     * Nothing outside [taskIds] is ever in [remove].
     */
    data class LeftoverPlan(val remove: List<Int>, val gone: List<Int>)

    fun leftoverPlan(left: List<String>, taskIds: Set<Int>, inWindowManager: Set<Int>, inUse: Set<Int> = emptySet()): LeftoverPlan {
        val ids = left.mapNotNull { layerTaskId(it) }.filter { it in taskIds }.distinct()
        val remove = ids.filter { it in inWindowManager && it !in inUse }
        return LeftoverPlan(remove, ids.filter { it !in inWindowManager })
    }

    /**
     * 0.5.25: the last check's line on what was done: each task removed (with Android's answer, [removed]) and each
     * one gone from the window manager whose layer is left, offscreen or not ([left]); [others] is
     * [otherOffscreenTaskLayers].
     */
    fun leftoverLine(displayId: Int, plan: LeftoverPlan, removed: Map<Int, String>, left: List<String>, others: Int): String {
        val parts = ArrayList<String>()
        for (id in plan.remove) parts += "Space task $id was still in the window manager, so the panel service removed it: ${removed[id] ?: "not tried"}"
        for (id in plan.gone) {
            val line = left.firstOrNull { layerTaskId(it) == id }
            val where = if (line != null && offscreen(line)) {
                "its layer is offscreen (no parent, so no display composes it) and another process holds it, which the panel service cannot remove"
            } else {
                "its layer still has a parent, so it may still be composed"
            }
            parts += "Space task $id is gone from the window manager; $where"
        }
        if (parts.isEmpty()) parts += "no Space task of this service's to remove"
        return "display $displayId released: ${parts.joinToString("; ")}; $others other task layer${if (others == 1) " is" else "s are"} left offscreen the same way (DeX's Desk among them)"
    }

    /** 0.5.25: the line of the check run [atMs] after a removal: whether the removed tasks' layers went. */
    fun afterRemovalLine(displayId: Int, removedIds: List<Int>, stillLeft: List<String>, atMs: Long): String =
        if (stillLeft.isEmpty()) "display $displayId released: the layers of removed Space task${if (removedIds.size == 1) "" else "s"} ${removedIds.joinToString()} are gone from SurfaceFlinger ${atMs / 1000.0} s after the removal"
        else "display $displayId released: ${stillLeft.size} layer${if (stillLeft.size == 1) "" else "s"} of removed Space task${if (removedIds.size == 1) "" else "s"} ${removedIds.joinToString()} still in SurfaceFlinger ${atMs / 1000.0} s after the removal: ${stillLeft.joinToString()}"

    /** 0.5.25: how long after a removal the layer list is read again (inside [LAYER_HOLD_MS]). */
    const val AFTER_REMOVAL_MS = 1_500L

    /**
     * Whether "bring the Space forward" needs a task move: only when the holder is not already
     * the top root task on the display. 0.5.5 reordered on every plug-in check and phone button,
     * a window transaction each time even with the Space already in front.
     */
    fun needsReorder(topRootTaskId: Int?, holderTaskId: Int?): Boolean =
        holderTaskId == null || topRootTaskId != holderTaskId

    private val HASH_ID = Regex("""#(\d+)""")
    private val TASK_ID = Regex("""taskId=(\d+)""")
}
