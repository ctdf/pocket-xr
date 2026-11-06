package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.15 (tester John Davis: "clicking compose email in either outlook or Gmail ... does not allow creating of the emails
 * on the screen. [It] transfers over to the phone to create"): an app in a glasses window that starts a new screen in a
 * task of its own (Gmail's and Outlook's Compose do) can see it land on the phone. Android places a new task on the
 * display of the activity that started it only when the start comes from that activity; a start from the app's service,
 * from a PendingIntent, or through a short "trampoline" activity that finishes at once has no such activity, and then
 * Android uses the display that last had the phone's own focus, which is the phone's screen (the window displays keep a
 * focus of their own). Pocket XR cannot change how the app starts it, so it watches for it instead: a new task of a
 * window's app that turns up on the phone's screen within [WINDOW_MS] of a press in that window is moved back onto the
 * window's display, through the shell (Shizuku), as `am display move-stack` does.
 */
object LaunchDisplay {
    /** How long after a press in a window a new task of its app on the phone is taken to be that press's. */
    const val WINDOW_MS = 6_000L

    /** How often the phone's tasks are read while that is so. */
    const val CHECK_MS = 400L

    /** The phone's own screen. */
    const val PHONE_DISPLAY = 0

    /** A root task on the phone's screen: its id and the package of its top (else base) activity. */
    data class PhoneTask(val taskId: Int, val packageName: String)

    /** A window showing [packageName] on [displayId], last pressed at [pressedMs] (uptime), or never. */
    data class WindowApp(val displayId: Int, val packageName: String, val pressedMs: Long?)

    /** Move [taskId] ([packageName]) from the phone's screen to [displayId]. */
    data class Move(val taskId: Int, val packageName: String, val displayId: Int)

    /** The shell's line for one phone task: "taskId package" ([parse]'s input). */
    fun line(taskId: Int, packageName: String): String = "$taskId $packageName"

    /** The shell's lines back into tasks; a line that is not "id package" is left out. */
    fun parse(lines: Array<String>?): List<PhoneTask> = lines.orEmpty().mapNotNull { l ->
        val parts = l.trim().split(' ')
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val pkg = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        PhoneTask(id, pkg)
    }

    /** True while a window was pressed within [WINDOW_MS], the only time the phone's tasks need reading. */
    fun watching(windows: List<WindowApp>, nowMs: Long): Boolean = windows.any { w -> w.pressedMs != null && nowMs - w.pressedMs in 0..WINDOW_MS }

    /**
     * Which tasks on the phone go back to a window. [known] are the task ids already seen on the phone (they are the
     * user's own phone apps, never moved); [moved] the ones moved before (never moved twice, so a task Android sends back
     * is left where Android wants it). A new task goes to the window of its app pressed most recently within [WINDOW_MS];
     * a new task of another app, or of an app whose window was not pressed lately, is the user's and becomes known.
     */
    fun plan(phone: List<PhoneTask>, windows: List<WindowApp>, known: Set<Int>, moved: Set<Int>, nowMs: Long, ownPackage: String? = null): List<Move> =
        phone.mapNotNull { t ->
            if (t.taskId in known || t.taskId in moved || t.packageName == ownPackage) return@mapNotNull null
            val w = windows
                .filter { it.packageName == t.packageName && it.pressedMs != null && nowMs - it.pressedMs in 0..WINDOW_MS }
                .maxByOrNull { it.pressedMs!! } ?: return@mapNotNull null
            Move(t.taskId, t.packageName, w.displayId)
        }

    /** The log line for a move and its result ([error] null when Android took it). */
    fun moveLine(m: Move, error: String?): String =
        "${m.packageName} opened task ${m.taskId} on the phone's screen after a press in its window; " +
            if (error == null) "moved to the window's display ${m.displayId}" else "could not move it to display ${m.displayId}: $error"
}

/**
 * 1.1.15: [LaunchDisplay]'s memory between reads: the phone's tasks already seen and the ones moved. The first read
 * only learns what is on the phone, so nothing open before a window was pressed is ever taken.
 */
class StrayTaskWatch(private val ownPackage: String? = null) {
    private var known: MutableSet<Int>? = null
    private val moved = HashSet<Int>()

    /** The moves for this read of the phone's tasks. */
    fun check(phone: List<LaunchDisplay.PhoneTask>, windows: List<LaunchDisplay.WindowApp>, nowMs: Long): List<LaunchDisplay.Move> {
        val k = known ?: return emptyList<LaunchDisplay.Move>().also { known = phone.map { it.taskId }.toHashSet() }
        val moves = LaunchDisplay.plan(phone, windows, k, moved, nowMs, ownPackage)
        moved += moves.map { it.taskId }
        k += phone.map { it.taskId }.filter { id -> moves.none { it.taskId == id } }
        return moves
    }

    /** Called when the phone's tasks were seen while no window was pressed lately: they are all the user's. */
    fun learn(phone: List<LaunchDisplay.PhoneTask>) {
        val k = known ?: HashSet<Int>().also { known = it }
        k += phone.map { it.taskId }
    }
}
