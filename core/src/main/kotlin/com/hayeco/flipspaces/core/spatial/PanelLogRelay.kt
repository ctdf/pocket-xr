package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.25 (the panel service's own lines, the layer checks among them, were only in
 * logcat-all; the app's log recorder keeps only the app's uid, and the service runs as the shell, uid 2000): the
 * service keeps its recent lines here and passes them to the app in batches over its existing callback binder,
 * and the app logs each one under [TAG], starting [PREFIX], as it does the glasses' own log.
 *
 * Cheap by design: at most [capacity] lines are kept (the oldest dropped first, and counted), and each batch
 * ([take]) carries at most [perBatch] lines, one batch every [EVERY_MS]; a busy moment waits for the next batch.
 */
class PanelLogRelay(val capacity: Int = CAPACITY, val perBatch: Int = PER_BATCH) {
    private val lines = ArrayDeque<String>()
    private var dropped = 0

    init {
        require(capacity >= 1 && perBatch >= 1) { "The relay must keep and pass at least one line" }
    }

    /** One line of the service's log; the oldest kept line goes when [capacity] are kept. */
    @Synchronized
    fun add(line: String) {
        lines.addLast(line.replace('\n', ' ').replace('\r', ' ').let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + " (cut)" else it })
        while (lines.size > capacity) {
            lines.removeFirst()
            dropped++
        }
    }

    /** The next batch, oldest first, at most [perBatch] lines (one of them the drop count when lines were dropped); empty when none. */
    @Synchronized
    fun take(): List<String> {
        if (lines.isEmpty() && dropped == 0) return emptyList()
        val out = ArrayList<String>(perBatch)
        if (dropped > 0) {
            out += "$dropped earlier line${if (dropped == 1) " was" else "s were"} dropped before the app took them (the service keeps $capacity)"
            dropped = 0
        }
        while (out.size < perBatch && lines.isNotEmpty()) out += lines.removeFirst()
        return out
    }

    @get:Synchronized
    val pending: Int get() = lines.size

    companion object {
        /** The app's tag for the relayed lines. */
        const val TAG = "FlipSpaces/Panels"

        /** Each relayed line starts with this, so it is never taken for the app's own. */
        const val PREFIX = "panel service: "

        const val CAPACITY = 200
        const val PER_BATCH = 5
        const val EVERY_MS = 1_000L

        /** A line longer than this is cut (the hold's reply lines are the longest, about 900 characters). */
        const val MAX_CHARS = 2_000

        /** The app's line for one relayed line. */
        fun appLine(line: String): String = PREFIX + line
    }
}
