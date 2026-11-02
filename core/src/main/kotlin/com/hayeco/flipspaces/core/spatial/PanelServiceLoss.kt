package com.hayeco.flipspaces.core.spatial

/**
 * How the panel service connection was lost (0.4.4).
 *
 * [SERVICE_DIED]: the panel process died (onServiceDisconnected, or its binder no longer
 * answers). [NO_BINDER]: Shizuku connected but handed back no live binder, which is how a
 * service that dies while starting shows up. [BIND_TIMED_OUT]: a bind try got no connection in
 * its [BindRetry] wait. [SHIZUKU_DIED]: the Shizuku server stopped; the panel process, started
 * by it as the shell user, can outlive it with its virtual displays.
 */
enum class PanelLoss { SERVICE_DIED, NO_BINDER, BIND_TIMED_OUT, SHIZUKU_DIED }

/**
 * What to do about a lost connection. [releaseDisplays] go through the old service before it
 * is dropped; [stopOldService] asks it to exit (IPanelHost.destroy); [removeRecord] unbinds
 * with remove = true, so Shizuku drops its record of the service instead of handing the next
 * bind to a dead one.
 */
data class LossPlan(
    val releaseDisplays: List<Int>,
    val stopOldService: Boolean,
    val removeRecord: Boolean,
)

/**
 * 0.4.4 fixes from the 0.4.3 retest:
 *
 * A panel service that died while starting left Shizuku with a record of it. Each bind try
 * after that attached to the dead record, and the retries unbound with remove = false, which
 * kept it, so the reconnect only came after the 1, 2, 4, 8 and 30 s waits (45 s). Now every
 * loss of the service itself removes the record at once, and a timed out try from the second
 * on ([REMOVE_FROM_TRY], after 3 s in all) removes it before the next bind, so that bind starts
 * a fresh service. The first try's 1 s is not enough to call a service dead: a cold start can
 * take longer, and removing the record stops a service that is still starting.
 *
 * When only the Shizuku server died, the panel process lived on with its virtual displays
 * until it was killed; 0.4.3 just forgot them. Now they are released through the old service,
 * which is then told to exit. The record went with the server, so there is none to remove
 * (and no server to ask).
 */
object PanelServiceLoss {
    const val REMOVE_FROM_TRY = 2

    /** [timedOutTry] is the try that got no connection, for [PanelLoss.BIND_TIMED_OUT] (1 based). */
    fun plan(loss: PanelLoss, displays: Collection<Int>, oldServiceAlive: Boolean, timedOutTry: Int = 0): LossPlan = when (loss) {
        PanelLoss.SHIZUKU_DIED -> LossPlan(
            releaseDisplays = if (oldServiceAlive) displays.toList() else emptyList(),
            stopOldService = oldServiceAlive,
            removeRecord = false,
        )
        // The process and its displays are gone; only Shizuku's record of it is left.
        PanelLoss.SERVICE_DIED, PanelLoss.NO_BINDER -> LossPlan(
            releaseDisplays = emptyList(),
            stopOldService = false,
            removeRecord = true,
        )
        PanelLoss.BIND_TIMED_OUT -> LossPlan(
            releaseDisplays = emptyList(),
            stopOldService = false,
            removeRecord = timedOutTry >= REMOVE_FROM_TRY,
        )
    }
}
