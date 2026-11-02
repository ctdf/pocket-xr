package com.hayeco.flipspaces.core.hands

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One way of showing a camera frame to MediaPipe: rotate it upright by [rotation] degrees
 * clockwise, then cut a square of the upright frame [zoom] times smaller than its long side,
 * centred near ([centerX], [centerY]) (normalised). Zoom 1 is the whole frame letterboxed
 * square, as [SquareLetterbox] does.
 *
 * Why zoom: MediaPipe's palm detector sees its input at 192 x 192. A 1920 x 1080 frame from
 * the glasses' wide camera shrinks ten times on the way, so a hand held at arm's length is a
 * palm of 15 to 20 px, below what the detector finds. The landmark model, which follows a hand
 * once found, crops from the full input and is not affected; that is why the device saw
 * confidence 0.91 the one time a palm was found but 0% hands otherwise.
 */
data class DetectionView(
    val label: String,
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    init {
        require(zoom >= 1f) { "Zoom must be 1 or more" }
    }
}

/**
 * The square cut [view] makes of a [width] x [height] frame turned [rotation] degrees
 * clockwise. [sourceToSquare] places the frame in the square bitmap (for android.graphics.Matrix)
 * and [toUpright] maps MediaPipe's landmarks back to the upright full frame, so the cursor
 * stays continuous whichever view found the hand.
 *
 * The bitmap is at most [maxOutputSide] pixels square: the palm detector sees 192 px and the
 * landmark model a 224 px crop anyway, and every pixel handed over is copied and uploaded to
 * the GPU, which is what made a 960 x 960 whole frame view cost more than the models.
 */
class DetectionWindow(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val view: DetectionView,
    val maxOutputSide: Int = Int.MAX_VALUE,
) {
    init {
        require(width > 0 && height > 0) { "Image needs a positive size" }
        require(rotation in ROTATIONS) { "Rotation must be 0, 90, 180 or 270, not $rotation" }
    }

    /** The frame's size once rotated upright. */
    val uprightWidth: Int = if (rotation % 180 == 0) width else height
    val uprightHeight: Int = if (rotation % 180 == 0) height else width

    /** Side of the square, in upright frame pixels (and in the square bitmap's pixels). */
    val side: Int = max(1, (max(uprightWidth, uprightHeight) / view.zoom).roundToInt())

    /** Side of the bitmap MediaPipe gets: [side], shrunk to [maxOutputSide]. Never enlarged. */
    val outputSide: Int = minOf(side, maxOf(1, maxOutputSide))

    /** Bitmap pixels per upright frame pixel. */
    val scale: Float = outputSide.toFloat() / side

    /** Top left of the square in upright frame pixels; negative where it letterboxes. */
    val left: Float = place(view.centerX * uprightWidth, uprightWidth)
    val top: Float = place(view.centerY * uprightHeight, uprightHeight)

    /** Keeps the square inside the frame where it fits, centred (with bars) where it does not. */
    private fun place(center: Float, extent: Int): Float =
        if (side >= extent) (extent - side) / 2f else (center - side / 2f).coerceIn(0f, (extent - side).toFloat())

    /**
     * The affine transform from source frame pixels to square bitmap pixels, as the six values
     * a, b, c, d, e, f of x' = a*x + b*y + c, y' = d*x + e*y + f.
     */
    val sourceToSquare: FloatArray = when (rotation) {
        0 -> floatArrayOf(1f, 0f, -left, 0f, 1f, -top)
        // Clockwise quarter turn: upright (h - y, x).
        90 -> floatArrayOf(0f, -1f, height - left, 1f, 0f, -top)
        180 -> floatArrayOf(-1f, 0f, width - left, 0f, -1f, height - top)
        // Anticlockwise quarter turn: upright (y, w - x).
        else -> floatArrayOf(0f, 1f, -left, -1f, 0f, width - top)
    }.also { m -> for (i in m.indices) m[i] *= scale }

    /** Where source pixel ([x], [y]) lands in the square bitmap. */
    fun toSquare(x: Float, y: Float): Pair<Float, Float> {
        val m = sourceToSquare
        return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
    }

    /** A landmark normalised to the square, normalised to the upright full frame instead. */
    fun toUpright(p: Landmark): Landmark = Landmark(
        x = (left + p.x * side) / uprightWidth,
        y = (top + p.y * side) / uprightHeight,
        // MediaPipe's z is on roughly the same scale as x.
        z = p.z * side / uprightWidth,
    )

    fun toUpright(hand: HandFrame): HandFrame = hand.copy(landmarks = hand.landmarks.map(::toUpright))

    /** For the debug line. */
    fun describe(): String =
        "input ${width}x$height, rotation $rotation, view ${view.label} (zoom ${"%.1f".format(view.zoom)}), square ${side}x$side at ${left.roundToInt()},${top.roundToInt()}" +
            if (outputSide != side) " sent at ${outputSide}x$outputSide" else ""

    companion object {
        val ROTATIONS = setOf(0, 90, 180, 270)

        /** Normalises any multiple of 90 (including negative) to 0, 90, 180 or 270. */
        fun normalise(degrees: Int): Int {
            require(degrees % 90 == 0) { "Rotation must be a multiple of 90, not $degrees" }
            return ((degrees % 360) + 360) % 360
        }
    }
}

/**
 * Which [DetectionView] the next frame uses, and whether to send it at all.
 *
 * Searching (no hand): each search view gets [framesPerView] frames, counted only for results
 * of frames sent with that view, then the next one is tried, so a small palm is found by a
 * zoomed view and a big one by the whole frame. A hand counts as found after [confirmFrames]
 * hits in a row; a single hit (a false palm) neither stops the cycle nor restarts it. After
 * [idleAfterMs] without a hand, frames go at most every [idleIntervalMs], which leaves the GPU
 * to the Space instead of running the palm detector 30 times a second on an empty room.
 *
 * Tracking: the view no longer cycles, which lets MediaPipe follow the hand from frame to frame
 * on its own and skip the palm detector. With [roi] the view becomes a crop around the hand at
 * the camera's full resolution: fewer pixels to hand over and more detail for the landmarks. It
 * moves only when the hand nears the crop's edge or changes size a lot, since every move costs
 * MediaPipe its track for a frame; the first result after a view change may miss without it
 * counting. The hand is lost after [lostAfterMisses] misses in a row: a miss in the crop falls
 * back to the view that found the hand, and on loss the cycle restarts from the whole frame.
 *
 * 0.3.1 dropped the hand on its first miss and jumped back to the whole frame, so a false palm
 * in the centre view flipped full and centre every few frames and MediaPipe never kept a track.
 *
 * 0.5.4, [roi] null (the glasses camera's setting, [steady]): the view never moves while a hand
 * is tracked, so MediaPipe's own track (the hand's box carried from frame to frame, in the
 * picture's coordinates) stays valid and its palm detector stays off. Every crop move gave
 * MediaPipe a box that no longer matched the picture: Mike's 0.5.3 log went "full -> hand (zoom
 * 3.3)" and back to full as a hand appeared, with "hand size jumped 44% in one frame" frames.
 * Without the crop, a hand found in a zoomed view that comes within [widenAt] of that view's
 * edge moves once to the widest view, before it is cut off.
 *
 * 0.5.7, a lost hand is looked for where it is most likely to be: Mike's 0.5.6 log lost a moving
 * hand after 3 to 4 s, then cycled full, centre (zoom 1.8) and close (zoom 2.5) every 0.2 to 0.4
 * s, so a hand that had moved out of the zoomed squares was looked for in them for two thirds of
 * the time and took seconds to come back. Now, for [recentMs] after a tracked hand was last seen,
 * the search stays on the widest view at the full frame rate (no cycling: the hand is still near
 * where it was, and the whole frame holds that); then for [framesPerView] frames a "near" view
 * [nearZoom] times zoomed and centred where the hand was last seen, for a hand that went small;
 * only then the usual cycle. A false palm's lone hit does not count as a hand seen.
 */
class DetectionCycle(
    val views: List<DetectionView> = DEFAULT_VIEWS,
    private val framesPerView: Int = 6,
    private val confirmFrames: Int = 2,
    private val lostAfterMisses: Int = 3,
    val roi: HandRoi? = HandRoi(),
    /**
     * 0.5.19 (the battery fell about 40 % an hour, the hand model on the GPU for
     * every camera frame): with no hand for [idleAfterMs] 2 s the search runs at 15 a second (every
     * other frame at 30 fps; 0.5.18: 20 after 1.5 s). A hand found is tracked at the full rate at once.
     */
    private val idleAfterMs: Long = 2000,
    private val idleIntervalMs: Long = 60,
    private val widenAt: Float = 0.1f,
    private val recentMs: Long = 1000,
    private val nearZoom: Float = 1.8f,
) {
    init {
        require(views.isNotEmpty()) { "Needs at least one view" }
        require(framesPerView >= 1 && confirmFrames >= 1 && lostAfterMisses >= 1) { "Frame counts must be at least 1" }
    }

    var index = 0
        private set
    private var framesOnView = 0
    private var hits = 0
    private var misses = 0
    private var tracking = false
    private var foundIn: DetectionView? = null
    private var crop: DetectionView? = null
    private var settle = false
    private var lastHandMs = Long.MIN_VALUE
    private var lastSentMs = Long.MIN_VALUE

    /** 0.5.7: how a lost hand is looked for; the tracked hand's last time and centre (upright, normalised). */
    private enum class Search { CYCLE, RECENT, NEAR }

    private var search = Search.CYCLE
    private var near: DetectionView? = null
    private var trackedSeenMs = Long.MIN_VALUE
    private var lastCentre: Pair<Float, Float>? = null

    /** The view searching uses: the widest just after a loss, then near the hand, then the cycle. */
    private val searchView: DetectionView
        get() = when (search) {
            Search.RECENT -> widest
            Search.NEAR -> near ?: views[index]
            Search.CYCLE -> views[index]
        }

    /** The view for the next frame. */
    val current: DetectionView get() = if (tracking) crop ?: foundIn ?: searchView else searchView

    /** 0.5.7: for the log, how the search runs now. */
    val searchLabel: String
        get() = when {
            tracking -> "tracking"
            search == Search.RECENT -> "lost hand, whole view at full rate"
            search == Search.NEAR -> "lost hand, near where it was last seen"
            else -> "searching"
        }

    /** True while a hand is tracked (confirmed and not yet lost). */
    val hasHand: Boolean get() = tracking

    /**
     * Whether to send a frame captured at [nowMs]. Always while a hand is tracked or was seen
     * recently; at most every idleIntervalMs after a long time without one. Call [sent] for each
     * frame sent.
     */
    fun shouldSend(nowMs: Long): Boolean {
        // 0.5.20: the glasses off a head: no hand press can count
        // (PhoneHandling.notWorn), so the hand model does no work at all and the GPU is the Space's.
        if (notWorn != null) {
            notWornSkipped++
            return false
        }
        if (tracking || hits > 0) return true
        if (lastHandMs == Long.MIN_VALUE) lastHandMs = nowMs
        // 0.5.18: hot and no hand for a while: the search slows to [hotIntervalMs].
        if (hot && nowMs - lastHandMs >= hotIdleAfterMs && lastSentMs != Long.MIN_VALUE && nowMs - lastSentMs < hotIntervalMs) {
            hotSkipped++
            return false
        }
        if (nowMs - lastHandMs < idleAfterMs) return true
        return lastSentMs == Long.MIN_VALUE || nowMs - lastSentMs >= idleIntervalMs
    }

    /**
     * 0.5.18 (the phone reached thermal status 3, 45 deg C, while MediaPipe
     * took 47 to 54 ms a frame on the GPU the Space draws with, and a hand was in view in only 119 of
     * 444 seconds): from severe heat ([hot]), a picture with no hand for [hotIdleAfterMs] is searched
     * at most every [hotIntervalMs] (every third camera frame at 30 fps: 10 a second instead of 30),
     * which leaves the GPU to the Space. A
     * hand found is tracked at the camera's full rate again at once; a hand coming into view waits at
     * most [hotIntervalMs] to be found.
     */
    @Volatile var hot = false
    var hotIdleAfterMs: Long = 1_000
    var hotIntervalMs: Long = 90

    /** 0.5.20: why the glasses are not on a head ([com.hayeco.flipspaces.core.hands.PhoneHandling.notWorn]), or null; no frame is sent while set. */
    @Volatile var notWorn: String? = null

    /** 0.5.20: camera frames not sent because the glasses were off a head. */
    var notWornSkipped = 0L
        private set

    /** 0.5.18: frames left unsent because the phone was hot and no hand was in view. */
    var hotSkipped = 0L
        private set

    fun sent(nowMs: Long) {
        lastSentMs = nowMs
    }

    /**
     * 0.5.24: no hand for [idleAfterMs] at [nowMs]: only the palm search runs, at the idle rate. False before the first
     * frame and for a cycle with no idle ([everyFrame]).
     */
    fun idle(nowMs: Long): Boolean = lastHandMs != Long.MIN_VALUE && nowMs - lastHandMs >= idleAfterMs

    object IdleSearch {
        /**
         * 0.5.24 (the Space's long draws were "waiting for a GPU busy with other work", p99 9 to 18 ms
         * of it, with MediaPipe's idle search on the GPU at 5 a second taking 30 to 45 ms each; for five seconds
         * with the camera stopped, the draw's p99 was 4.3 ms): the idle palm search goes to a landmarker on the CPU,
         * built once, while the hand model runs on the GPU and the Space draws. A hand found goes back to the GPU one.
         */
        fun searchOnCpu(runnerOnGpu: Boolean, idle: Boolean, spaceDraws: Boolean, cpuSearcher: Boolean): Boolean =
            runnerOnGpu && idle && spaceDraws && cpuSearcher
    }

    /** Records a result without the hand's landmarks or a frame time (tests, simple callers). */
    fun record(handFound: Boolean): Boolean = record(current, if (handFound) PLACEHOLDER else null, 1, 1, 0L)

    /**
     * Records the result of a frame sent with [sentWith]: the hand in upright normalised
     * coordinates or null, the upright frame's size and the result's time. Returns true when
     * the next frame uses a different view.
     */
    fun record(sentWith: DetectionView, hand: HandFrame?, uprightWidth: Int, uprightHeight: Int, nowMs: Long): Boolean {
        val before = current
        val firstAfterChange = settle && sentWith == before
        if (sentWith == before) settle = false
        if (hand != null) {
            lastHandMs = nowMs
            misses = 0
            if (!tracking) {
                hits++
                // The frame counts against the view's budget; the view moves on at the next miss.
                if (sentWith == searchView) framesOnView++
                if (hits >= confirmFrames) {
                    tracking = true
                    foundIn = if (sentWith in views || sentWith == near) sentWith else searchView
                    crop = null
                    search = Search.CYCLE
                }
            }
            if (tracking) {
                trackedSeenMs = nowMs
                if (hand !== PLACEHOLDER) lastCentre = hand.bounds().let { b -> (b[0] + b[2]) / 2f to (b[1] + b[3]) / 2f }
            }
            if (tracking && roi != null && hand !== PLACEHOLDER) {
                val next = roi.plan(crop, hand, uprightWidth, uprightHeight)
                if (next != null) crop = next
            }
            val found = foundIn
            if (tracking && roi == null && hand !== PLACEHOLDER && found != null && found != widest &&
                nearEdge(found, hand, uprightWidth, uprightHeight)
            ) {
                foundIn = widest
            }
        } else if (tracking) {
            if (!firstAfterChange && sentWith == before) {
                misses++
                if (misses >= lostAfterMisses) {
                    lose()
                } else if (crop != null) {
                    // The hand left the crop: go back to the wider view that found it.
                    crop = null
                }
            }
        } else if (sentWith == searchView) {
            // A lone hit followed by a miss was not a hand; the view keeps its frame budget.
            hits = 0
            when (search) {
                Search.RECENT -> if (nowMs - trackedSeenMs >= recentMs) {
                    framesOnView = 0
                    near = nearView()
                    if (near != null) {
                        search = Search.NEAR
                    } else {
                        afterWidest()
                    }
                }
                Search.NEAR -> {
                    framesOnView++
                    if (framesOnView >= framesPerView) afterWidest()
                }
                Search.CYCLE -> {
                    framesOnView++
                    if (framesOnView >= framesPerView) {
                        framesOnView = 0
                        index = (index + 1) % views.size
                    }
                }
            }
        }
        val changed = current != before
        if (changed) settle = true
        return changed
    }

    /** The view showing the most of the frame. */
    private val widest: DetectionView = views.minBy { it.zoom }

    /** 0.5.7: a zoomed square centred where the tracked hand was last seen, or null without one. */
    private fun nearView(): DetectionView? {
        val (x, y) = lastCentre ?: return null
        if (nearZoom <= widest.zoom) return null
        return DetectionView(NEAR_LABEL, zoom = nearZoom, centerX = x.coerceIn(0f, 1f), centerY = y.coerceIn(0f, 1f))
    }

    /** The widest view has had its time: the usual cycle goes on from the view after it. */
    private fun afterWidest() {
        search = Search.CYCLE
        near = null
        framesOnView = 0
        index = (views.indexOf(widest) + 1) % views.size
    }

    /** True when [hand] (upright normalised) is within [widenAt] of [view]'s square's edge. */
    private fun nearEdge(view: DetectionView, hand: HandFrame, width: Int, height: Int): Boolean {
        val w = DetectionWindow(width, height, 0, view)
        val b = hand.bounds()
        val margin = widenAt * w.side
        return b[0] * width < w.left + margin || b[2] * width > w.left + w.side - margin ||
            b[1] * height < w.top + margin || b[3] * height > w.top + w.side - margin
    }

    private fun lose() {
        tracking = false
        hits = 0
        misses = 0
        foundIn = null
        crop = null
        index = views.indexOf(widest)
        framesOnView = 0
        // 0.5.7: the whole view first, at the full rate, while the hand is still near where it was.
        search = if (trackedSeenMs != Long.MIN_VALUE) Search.RECENT else Search.CYCLE
        near = null
    }

    fun reset() {
        trackedSeenMs = Long.MIN_VALUE
        lose()
        index = 0
        lastCentre = null
        settle = false
        lastHandMs = Long.MIN_VALUE
        lastSentMs = Long.MIN_VALUE
    }

    companion object {
        /**
         * The whole frame, then a centred square cut of a landscape frame's height (1.8 times
         * bigger), then a tighter cut a little below centre, where a hand held out in front of
         * a head worn camera usually is.
         */
        val DEFAULT_VIEWS = listOf(
            DetectionView("full"),
            DetectionView("centre", zoom = 1.8f),
            DetectionView("close", zoom = 2.5f, centerY = 0.6f),
        )

        const val NEAR_LABEL = "near"

        private val PLACEHOLDER = HandFrame(List(HandFrame.COUNT) { Landmark(0.5f, 0.5f) }, 0)

        /** 0.5.4: the glasses camera's cycle: the search views, and no moving crop while tracking. */
        fun steady(): DetectionCycle = DetectionCycle(roi = null)

        /**
         * 0.5.14, the glasses camera's: every camera frame is sent, always
         * the whole view. 0.5.13 searched by cycling full, centre and close (842 view changes in 6
         * minutes, one every 0.4 s, so a hand raised into the wrong square waited for the cycle) and
         * after 1.5 s without a hand sent a frame only every 50 ms (15 fps searching, 30 dropped a
         * second).
         */
        fun everyFrame(): DetectionCycle = DetectionCycle(
            views = listOf(DetectionView("full")),
            roi = null,
            idleAfterMs = Long.MAX_VALUE / 4,
            nearZoom = 1f,
        )

        /**
         * 0.5.20 (30 frames a second were sent all session, `hand 0%` for
         * minutes, because the glasses camera used [everyFrame], whose idle never comes, so 0.5.19's
         * idle reached only the default cycle): the glasses camera's cycle. Every frame, the whole
         * view, while a hand is tracked or was seen in the last 2 s; then 15 a second (every other
         * frame at 30 fps), so the hand model's GPU work, which holds the Space's late draw back
         * (measured on the phone), halves while no hand is up. A hand found is tracked at 30 at once.
         */
        fun glassesCamera(): DetectionCycle = DetectionCycle(
            views = listOf(DetectionView("full")),
            roi = null,
            idleAfterMs = 2000,
            idleIntervalMs = IDLE_INTERVAL_MS,
            nearZoom = 1f,
        )

        /**
         * 0.5.21 (at 15 fps with no hand up the GPU hand model still kept
         * the late draw's budget at its 15.7 ms cap, 32 ms pose to photons, against 19 to 25 ms with it
         * paused): 5 frames a second while no hand is up (was 15), only the palm search running then. A
         * hand raised is found within 0.2 s and tracked at 30 at once.
         */
        const val IDLE_INTERVAL_MS = 200L
    }
}

/**
 * The crop that follows a tracked hand: a square [sizeFactor] times the hand's bounding box
 * (at least [minFraction] of the frame's long side, so zoom stays at most 1 / minFraction),
 * rounded up to a sixteenth of the long side, centred on it. A planned crop is kept while the hand's centre stays within [recentreAt] of
 * the crop's side from its centre and the hand's size stays within [resizeRatio] of the size
 * it was planned for.
 */
data class HandRoi(
    val sizeFactor: Float = 3.5f,
    val minFraction: Float = 0.3f,
    val recentreAt: Float = 0.22f,
    val resizeRatio: Float = 1.6f,
) {
    init {
        require(sizeFactor >= 1f && minFraction in 0.05f..1f && recentreAt > 0f && resizeRatio > 1f) { "Bad ROI settings" }
    }

    /**
     * The crop for [hand] (upright normalised) in a [width] x [height] upright frame, or null
     * to keep [current].
     */
    fun plan(current: DetectionView?, hand: HandFrame, width: Int, height: Int): DetectionView? {
        val long = maxOf(width, height).toFloat()
        val b = hand.bounds()
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val handPx = maxOf((b[2] - b[0]) * width, (b[3] - b[1]) * height)
        // In steps of a sixteenth of the frame, so the app reuses a few bitmap sizes.
        val step = long / 16f
        val sidePx = (kotlin.math.ceil(handPx * sizeFactor / step) * step).coerceIn(long * minFraction, long)
        val zoom = long / sidePx
        if (current != null && current.label == LABEL) {
            val curSide = long / current.zoom
            val dx = abs(cx - current.centerX) * width / curSide
            val dy = abs(cy - current.centerY) * height / curSide
            val sizeChange = maxOf(sidePx / curSide, curSide / sidePx)
            if (dx <= recentreAt && dy <= recentreAt && sizeChange <= resizeRatio) return null
        }
        return DetectionView(LABEL, zoom = zoom.coerceAtLeast(1f), centerX = cx.coerceIn(0f, 1f), centerY = cy.coerceIn(0f, 1f))
    }

    companion object {
        const val LABEL = "hand"
    }
}

/**
 * MediaPipe Hand Landmarker thresholds. [detection] is the palm detector's score threshold:
 * MediaPipe's default 0.5 drops the low scores a small or blurred palm gets, so it is lower here;
 * [presence], checked by the landmark model on every found hand, still filters out what the
 * lower detector threshold lets through. Low [presence] and [tracking] keep following a hand
 * that moves or is partly out of view instead of falling back to the palm detector.
 */
data class HandThresholds(
    val detection: Float = 0.3f,
    /**
     * 0.5.7: 0.3 (was 0.4). MediaPipe drops a hand it follows as soon as the landmark model's
     * presence for it falls below this, then runs the palm detector, which finds a moving, blurred
     * palm badly: Mike's 0.5.6 log lost the hand after a fast move ("hand 43%") and took seconds
     * to find it. A lower bar keeps the track through those frames; what is not a hand still
     * scores near 0, and [PinchReadiness] still gates single bad frames.
     */
    val presence: Float = 0.3f,
    /**
     * 0.5.7: 0.25 (was 0.4): how much the hand's box must overlap last frame's to count as the
     * same hand. A hand sweeping at 2000 px/s moves about 70 px a frame at 30 fps, which takes
     * a 150 px box under 0.4.
     */
    val tracking: Float = 0.25f,
) {
    init {
        listOf(detection, presence, tracking).forEach { require(it in 0f..1f) { "Thresholds are between 0 and 1" } }
    }

    fun describe(): String = "detection ${fmt(detection)}, presence ${fmt(presence)}, tracking ${fmt(tracking)}"

    private fun fmt(v: Float) = "%.2f".format(v)

    companion object {
        /** MediaPipe's own default for all three. */
        const val MEDIAPIPE_DEFAULT = 0.5f
    }
}

/**
 * The once a second camera line in the log: which camera is open, how it is mounted and what
 * MediaPipe is shown. A UVC camera reports no sensor orientation, so [sensorOrientation] is null
 * for the glasses and the [DetectionWindow.rotation] is the one set in the app.
 */
object CameraDebugLine {
    const val EVERY_MS = 1000L

    fun line(cameraId: String, sensorOrientation: Int?, window: DetectionWindow, delegate: String): String =
        "Camera: id $cameraId, sensor orientation ${sensorOrientation?.toString() ?: "none (UVC)"}, " +
            "rotation applied ${window.rotation}, ${window.describe()}, MediaPipe on $delegate"

    /** True when a line is due at [nowMs], given the last one went out at [lastMs] (0 for never). */
    fun due(nowMs: Long, lastMs: Long): Boolean = lastMs == 0L || nowMs - lastMs >= EVERY_MS
}
