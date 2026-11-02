package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.3: how a panel's frames get from its virtual display to the Space without SurfaceFlinger
 * ever waiting on the Space.
 *
 * Mike's 0.5.1 two hand resize and his 0.5.2 plug-in both ended in "SurfaceFlinger RESET by
 * watchdog" with the same main thread stack: `Display::finishFrame` > `RenderSurface::dequeueBuffer`
 * > `BpGraphicBufferProducer::dequeueBuffer`, a binder wait for a free buffer in a panel display's
 * output. In 0.5.1 that output was a SurfaceTexture
 * drained on the Space's render thread; in 0.5.2 one drained on a frame thread whose EGL context
 * shared the render thread's display. Both drains ran through GL (updateTexImage, glFinish), so
 * whenever the render thread waited in its buffer swap for SurfaceFlinger, or MediaPipe's GPU
 * graph was being rebuilt, the drain stalled with it, the display's queue filled, and
 * SurfaceFlinger blocked for good.
 *
 * So each panel display now renders into an ImageReader. Its frames are taken on a thread that
 * does nothing but acquire and close images (no GL, no EGL, nothing that can wait for
 * SurfaceFlinger), and the [PanelImageMailbox] here caps how many images the Space holds, so
 * the reader always has a free image to acquire and the display a free buffer to dequeue.
 */
class PanelImageMailbox<T : Any>(
    /** The ImageReader's maxImages. */
    val maxImages: Int = DEFAULT_MAX_IMAGES,
    /** Images handed to the GPU that may still be read; past this no new one is leased. */
    val maxRetiring: Int = DEFAULT_MAX_RETIRING,
    private val close: (T) -> Unit,
    /**
     * 0.5.26: how many images may wait to be copied, oldest first. 1 is 0.5.3 to 0.5.25 (only the newest). 2 lets a
     * window keep the next picture while the one due now is shown ([PanelLatch]): each picture is shown on the refresh
     * its own timestamp says, not on whichever refresh its arrival happened to beat.
     */
    val maxWaiting: Int = 1,
) {
    init {
        // The waiting images, the one being copied, the retiring ones, and the one the reader
        // acquires before it lets go of the previous must all fit.
        require(maxWaiting >= 1) { "maxWaiting must be at least 1" }
        require(maxImages >= maxRetiring + maxWaiting + 2) { "maxImages $maxImages is too few for $maxRetiring retiring and $maxWaiting waiting images" }
    }

    private class Retiring<T>(val image: T, val token: Any?)

    private val waitingImages = ArrayDeque<T>()
    private var leased: T? = null
    private val retiring = ArrayDeque<Retiring<T>>()
    private var shedding = false

    /** Images taken off the display, and those let go of unread (a newer one came first). */
    var offered = 0L
        private set
    var dropped = 0L
        private set

    /** Images the Space holds now; the reader's next acquire needs this below [maxImages]. */
    @get:Synchronized
    val held: Int get() = waitingImages.size + (if (leased != null) 1 else 0) + retiring.size

    /** True while an image the Space has not leased yet is waiting. */
    @get:Synchronized
    val hasNew: Boolean get() = waitingImages.isNotEmpty()

    /**
     * Drain thread: room for one more acquire. Closes the oldest waiting image if the Space holds
     * so many that the reader could not acquire (never the leased or retiring ones, whose buffers
     * the GPU may be reading). Returns whether an acquire may go ahead.
     */
    fun makeRoom(): Boolean {
        val drop: T?
        synchronized(this) {
            drop = if (held >= maxImages - 1) waitingImages.removeFirstOrNull() else null
            if (drop != null) dropped++
        }
        drop?.let(close)
        synchronized(this) { return held < maxImages }
    }

    /**
     * Drain thread: [image] was just acquired. It becomes the newest waiting image; past
     * [maxWaiting] the oldest waiting one is closed at once. While shedding (a stall) only the
     * newest is kept, never leased, so the panel shows the app as it is now once frames run again.
     */
    fun offer(image: T) {
        val drops = ArrayList<T>(1)
        synchronized(this) {
            offered++
            waitingImages.addLast(image)
            val keep = if (shedding) 1 else maxWaiting
            while (waitingImages.size > keep) drops += waitingImages.removeFirst()
            dropped += drops.size
        }
        drops.forEach(close)
    }

    /**
     * Render thread: the newest image to copy, or null. It stays leased until [retire]. Refused
     * while [maxRetiring] images are still on the GPU (the waiting one keeps being replaced).
     */
    fun lease(): T? = lease { it.size - 1 }

    /**
     * 0.5.26: the waiting image [choose] picks (an index into the waiting images, oldest first; null for none now), or
     * null. The ones older than it are closed (never shown); the newer ones keep waiting.
     */
    fun lease(choose: (List<T>) -> Int?): T? {
        val drops = ArrayList<T>()
        val next: T
        synchronized(this) {
            if (leased != null || shedding || retiring.size >= maxRetiring || waitingImages.isEmpty()) return null
            val i = choose(waitingImages.toList())?.takeIf { it in waitingImages.indices } ?: return null
            repeat(i) { drops += waitingImages.removeFirst() }
            next = waitingImages.removeFirst()
            leased = next
            dropped += drops.size
        }
        drops.forEach(close)
        return next
    }

    /** Render thread: the copy of [image] was submitted; [token] (a GPU fence) tells when it is read. */
    fun retire(image: T, token: Any?) {
        val now: T?
        synchronized(this) {
            if (leased === image) leased = null
            now = if (shedding) image else null
            if (now == null) retiring.addLast(Retiring(image, token))
        }
        now?.let(close)
    }

    /**
     * Render thread: closes every retiring image whose GPU read is done ([isDone] on its token;
     * never waits). Returns the tokens let go of, for the caller to delete.
     */
    fun reap(isDone: (Any?) -> Boolean): List<Any?> {
        val done = ArrayList<Retiring<T>>()
        synchronized(this) {
            while (retiring.isNotEmpty() && isDone(retiring.first().token)) done += retiring.removeFirst()
        }
        done.forEach { close(it.image) }
        return done.map { it.token }
    }

    /**
     * Any thread (the stall watchdog): hold as little as possible. The retiring images are closed
     * now, even though the GPU might still read them (a torn panel frame is better than
     * SurfaceFlinger waiting), nothing is leased until [resume], and an image the render thread
     * gives back is closed at once. Only the newest waiting image stays (replaced as new ones
     * come), so at most two are held, the leased one included. Returns the tokens of the retiring
     * images, for the render thread to delete later.
     */
    fun shed(): List<Any?> {
        val drop = ArrayList<T>()
        val tokens = ArrayList<Any?>()
        synchronized(this) {
            shedding = true
            while (retiring.isNotEmpty()) retiring.removeFirst().let { drop += it.image; tokens += it.token }
            // 0.5.26: only the newest waiting image stays.
            while (waitingImages.size > 1) drop += waitingImages.removeFirst()
        }
        drop.forEach(close)
        return tokens
    }

    @Synchronized
    fun resume() {
        shedding = false
    }

    @get:Synchronized
    val isShedding: Boolean get() = shedding

    /** Closes everything, the waiting and leased images too (the panel is going away). */
    fun clear(): List<Any?> {
        val tokens = shed()
        val last = ArrayList<T>()
        synchronized(this) {
            last += waitingImages
            waitingImages.clear()
            leased?.let { last += it }
            leased = null
        }
        last.forEach(close)
        return tokens
    }

    companion object {
        const val DEFAULT_MAX_IMAGES = 6
        const val DEFAULT_MAX_RETIRING = 2
    }
}

/**
 * 0.5.3: when the render thread copies a panel's newest image into the texture it draws: in view,
 * with a new image, and no more often than the panel's thermal cap. Images that are not copied
 * are replaced by newer ones in the [PanelImageMailbox], so nothing here can hold up the display;
 * a panel coming back into view finds its newest image waiting.
 */
object PanelCopyPolicy {
    /**
     * 0.5.26 (`91 held to the cap` and `137 held to the cap` a 5 s line in 2D with no heat at all): with no
     * cap ([ThermalGuard.FULL_FPS] or more) a new image is copied on the first refresh after it arrives, with no
     * interval. 0.5.25 also held an image that came under 16 ms after the last copy, so on a 120 Hz Space a 60 Hz app
     * landed 8 ms late every few frames (17 / 25 ms steps, 45 of its 53 frames a second shown). A cap below it keeps
     * its interval with [SLACK_MS] of slack, so a frame a little early on the cap's beat is not held a whole refresh.
     */
    fun plan(hasNew: Boolean, visible: Boolean, capFps: Int, nowMs: Long, lastCopyMs: Long): CopyPlan {
        if (!visible || !hasNew) return CopyPlan.SKIP
        if (capFps >= ThermalGuard.FULL_FPS) return CopyPlan.COPY
        val interval = if (capFps <= 0) Long.MAX_VALUE else 1000L / capFps - SLACK_MS
        val due = lastCopyMs <= 0L || nowMs - lastCopyMs >= interval
        return if (due) CopyPlan.COPY else CopyPlan.LATER
    }

    /** 0.5.26: as [PanelFrameGate.SLACK_MS]. */
    const val SLACK_MS = PanelFrameGate.SLACK_MS
}

enum class CopyPlan { SKIP, LATER, COPY }

/**
 * 0.5.17 (the prepare pass, panel copies, mipmaps and resizes, took about 13
 * of the 16.7 ms frame on the GPU in side by side): what the prepare pass does for one panel this
 * frame, decided here so it can be tested, and counted for the perf line ([PanelPrepareCounts]).
 *
 * - A panel is copied only when it is in view, has an image it has not copied yet, and its thermal
 *   cap allows ([PanelCopyPolicy]); every other frame it draws the copy it has.
 * - A mip chain is built only after a new copy, only for a panel shown smaller than its pixels
 *   (over [PanelMips.MIN_SHRINK] texels a glasses pixel), and only down to the levels that shrink
 *   samples ([mipLevels]), not the whole chain. A panel drawn near 1:1 (Mike's are about 1.41
 *   texels a glasses pixel) is drawn from one level with bilinear filtering and never gets a chain.
 * - The black check (once a second, on a copy) reads a level 16 pixels across, so it builds the
 *   whole chain; it is counted apart.
 */
object PanelPrepare {
    /** Why a panel was or was not copied this frame. */
    enum class Copy { COPIED, NO_NEW_IMAGE, HELD_TO_CAP, OUT_OF_VIEW }

    fun copy(hasNew: Boolean, visible: Boolean, capFps: Int, nowMs: Long, lastCopyMs: Long): Copy = when {
        !visible -> Copy.OUT_OF_VIEW
        !hasNew -> Copy.NO_NEW_IMAGE
        PanelCopyPolicy.plan(true, true, capFps, nowMs, lastCopyMs) == CopyPlan.COPY -> Copy.COPIED
        else -> Copy.HELD_TO_CAP
    }

    /**
     * The mip levels (level 0 included) a texture of [fullLevels] needs to be sampled at
     * [texelsPerPixel]: 1 up to [PanelMips.MIN_SHRINK] (bilinear from level 0); past it, the levels
     * a trilinear lookup at log2 of the shrink reads, plus one for a panel seen at an angle.
     */
    fun mipLevels(texelsPerPixel: Float, fullLevels: Int): Int {
        if (!(texelsPerPixel > PanelMips.MIN_SHRINK)) return 1
        val lod = kotlin.math.ln(texelsPerPixel.toDouble()) / kotlin.math.ln(2.0)
        return (kotlin.math.ceil(lod).toInt() + 2).coerceIn(2, fullLevels.coerceAtLeast(2))
    }

    /** Whether a chain must be built now: more levels are needed than the copy has valid. */
    fun buildChain(levelsNeeded: Int, levelsBuilt: Int): Boolean = levelsNeeded > 1 && levelsBuilt < levelsNeeded
}

/**
 * 0.5.17: the prepare pass over one perf window: panels copied against those skipped and why, and
 * the mip chains built, so the next test reads them off the perf line.
 */
class PanelPrepareCounts {
    private val copies = LongArray(PanelPrepare.Copy.entries.size)
    var chains = 0L
        private set
    var chainLevels = 0L
        private set
    var blackCheckChains = 0L
        private set

    fun copy(c: PanelPrepare.Copy) {
        copies[c.ordinal]++
    }

    fun chain(levels: Int, forBlackCheck: Boolean) {
        if (forBlackCheck) blackCheckChains++ else {
            chains++
            chainLevels += levels
        }
    }

    fun count(c: PanelPrepare.Copy): Long = copies[c.ordinal]

    /** The window's line (the counts start over). */
    fun line(): String {
        val copied = count(PanelPrepare.Copy.COPIED)
        val skipped = count(PanelPrepare.Copy.NO_NEW_IMAGE) + count(PanelPrepare.Copy.HELD_TO_CAP) + count(PanelPrepare.Copy.OUT_OF_VIEW)
        val s = String.format(
            java.util.Locale.ROOT,
            "prepare: %d panel copies, %d skipped (%d no new image, %d held to the cap, %d out of view); %d mip chains built (%s), %d for the black check",
            copied, skipped, count(PanelPrepare.Copy.NO_NEW_IMAGE), count(PanelPrepare.Copy.HELD_TO_CAP), count(PanelPrepare.Copy.OUT_OF_VIEW),
            chains, if (chains > 0) "%.1f levels each".format(java.util.Locale.ROOT, chainLevels.toDouble() / chains) else "none",
            blackCheckChains,
        )
        copies.fill(0)
        chains = 0
        chainLevels = 0
        blackCheckChains = 0
        return s
    }
}

/**
 * 0.5.2: a panel's virtual display is resized at most once every [minGapMs] and only to a size
 * that differs from the one it has, the last asked size winning. 0.5.1 resized on every grab
 * release, so quick pinches during a two hand stretch resized the display (and set off a window
 * manager transition with a screenshot of it) several times a second.
 *
 * 0.5.8, a live resize: Mike's 0.5.6 log resized the display only at release ("display 58 resized
 * to 1238x868" 19 ms after both pinches went up), so the window took its size when he let go.
 * The Space now asks for the size on every frame of a stretch and the gate sends it at most every
 * [LIVE_GAP_MS] (the renderer's setting), plus once at release. A resize also waits until the app
 * has drawn a frame at the size sent before (`ready`, the new ImageReader has an image), so no
 * resize is sent faster than the app lays out; after [settleMs] without that frame it goes anyway
 * (an app that draws nothing new).
 */
class PanelResizeGate(
    private val minGapMs: Long = 400L,
    private val settleMs: Long = 600L,
    /**
     * 0.5.45 (Mike, vc65: "whenever i resize windows, they do a flickering thing for just a bit since its loading the new
     * fit size"): false takes no [request] made while the window is held (live): the app is laid out once, at the
     * release, and its picture scales with the window meanwhile. True is 0.5.8 to 0.5.44's live resizes.
     */
    private val takeLive: Boolean = true,
) {
    private data class Entry(
        var appliedW: Int, var appliedH: Int, var wantW: Int, var wantH: Int, var lastMs: Long,
        var appliedDpi: Int = PanelDisplays.DENSITY_DPI, var wantDpi: Int = PanelDisplays.DENSITY_DPI,
    ) {
        val differs: Boolean get() = wantW != appliedW || wantH != appliedH || wantDpi != appliedDpi
    }

    private val entries = HashMap<Int, Entry>()

    /** The display behind [panelId] has [w] by [h] at [dpi] (opened at that size). */
    fun opened(panelId: Int, w: Int, h: Int, dpi: Int = PanelDisplays.DENSITY_DPI) {
        entries[panelId] = Entry(w, h, w, h, Long.MIN_VALUE / 2, dpi, dpi)
    }

    /**
     * The panel should become [w] by [h] at [dpi] (0.5.21: the density is part of the size, [PanelDisplaySize]).
     *
     * 0.5.28 (a held window's live resize laid Twitch out again 40 times in 16 s):
     * a [live] request (the window still held) is only taken when a side differs by over [LIVE_CHANGE] from the size
     * the display has (or the density changed); a smaller one leaves the display as it is until the release, whose
     * request is always taken.
     */
    fun request(panelId: Int, w: Int, h: Int, dpi: Int = PanelDisplays.DENSITY_DPI, live: Boolean = false) {
        val e = entries[panelId] ?: return
        if (live && (!takeLive || (dpi == e.appliedDpi && near(w, e.appliedW) && near(h, e.appliedH)))) {
            e.wantW = e.appliedW
            e.wantH = e.appliedH
            e.wantDpi = e.appliedDpi
            return
        }
        e.wantW = w
        e.wantH = h
        e.wantDpi = dpi
    }

    private fun near(a: Int, b: Int): Boolean = b > 0 && kotlin.math.abs(a - b) <= b * LIVE_CHANGE

    /** The density last sent for [panelId] (with the size [due] gave). */
    fun appliedDpi(panelId: Int): Int = entries[panelId]?.appliedDpi ?: PanelDisplays.DENSITY_DPI

    /**
     * The resizes to send now, as panel id to size; each panel at most once per [minGapMs], and
     * (0.5.8) only once [ready] says the app drew at the last size sent, or [settleMs] passed.
     */
    fun due(nowMs: Long, ready: (Int) -> Boolean = { true }): List<Pair<Int, Pair<Int, Int>>> {
        val out = mutableListOf<Pair<Int, Pair<Int, Int>>>()
        for ((id, e) in entries) {
            if (!e.differs) continue
            if (nowMs - e.lastMs < minGapMs) continue
            if (nowMs - e.lastMs < settleMs && !ready(id)) continue
            e.appliedW = e.wantW
            e.appliedH = e.wantH
            e.appliedDpi = e.wantDpi
            e.lastMs = nowMs
            out += id to (e.wantW to e.wantH)
        }
        return out
    }

    /** The panel closed or lost its display: nothing more is sent for it. */
    fun forget(panelId: Int) {
        entries.remove(panelId)
    }

    fun pending(panelId: Int): Boolean = entries[panelId]?.differs == true

    companion object {
        /** 0.5.8: the Space's gap between live resizes while a stretch or corner drag goes on. 0.5.28: 250 ms (was 150). */
        const val LIVE_GAP_MS = 250L

        /** 0.5.28: a live resize is sent only past this share of a side (5 %). */
        const val LIVE_CHANGE = 0.05f
    }
}
