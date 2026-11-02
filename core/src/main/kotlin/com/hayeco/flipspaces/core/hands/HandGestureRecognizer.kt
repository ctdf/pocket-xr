package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.ViewFrustum

/**
 * The part of the camera image that maps onto the whole glasses display, in normalised image
 * coordinates. Hands outside it pin the cursor to the display edge. [mirror] flips x, for a
 * camera that faces the user instead of the world.
 */
data class AirBox(
    val left: Float = 0.15f,
    val top: Float = 0.15f,
    val right: Float = 0.85f,
    val bottom: Float = 0.85f,
    val mirror: Boolean = false,
) {
    init {
        require(left < right && top < bottom) { "Air box must have a positive size" }
    }

    fun toDisplay(point: Landmark, displayWidth: Int, displayHeight: Int): Point {
        val nx = ((point.x - left) / (right - left)).coerceIn(0f, 1f)
        val ny = ((point.y - top) / (bottom - top)).coerceIn(0f, 1f)
        val x = if (mirror) 1f - nx else nx
        return Point(x * (displayWidth - 1), ny * (displayHeight - 1))
    }
}

/** What the overlay shows about the hand, separate from the pointer signals. */
data class HandStatus(
    val present: Boolean,
    val pose: HandPose? = null,
    val pinched: Boolean = false,
    /** Pinch strength: 0 when the fingers are wide apart, 1 at the pinch threshold. */
    val pinchProgress: Float = 0f,
    val cursor: Point? = null,
    /** The thumb to index ratio behind [pinchProgress] (see [HandFrame.pinchRatio]). */
    val pinchRatio: Float? = null,
    /** The cursor is held still for a pinch (closing, pressed, not dragging). */
    val locked: Boolean = false,
    /** 0.5.0: which hand, when two are tracked apart. */
    val side: HandSide? = null,
    /** 0.5.0: the frame passed the confidence gate ([PinchReadiness]). */
    val ready: Boolean = true,
    /** 0.5.0: the hand ray in the head frame (ray mode only). */
    val ray: HandRayResult? = null,
    /** 0.5.46: the palm faces the glasses: a pinch now opens the launcher (the cursor shows it). */
    val palmMenu: Boolean = false,
    /** 0.5.50: how far a held palm pinch is toward the recentre, 0 to 1 (0 with none held), for the dot's fill. */
    val palmHold: Float = 0f,
    /** 0.5.8: the hand is near the bottom of the camera picture ([HandFrame.nearBottom]), for the Space's hint. */
    val nearBottom: Boolean = false,
)

/**
 * [traces] are the pinch steps of this frame, for the log. [hands] (0.5.0) has each hand's own
 * status when two are tracked apart; [status] is then the right hand's, or the left's alone.
 */
data class HandUpdate(
    val signals: List<PointerSignal>,
    val status: HandStatus,
    val traces: List<PinchTrace> = emptyList(),
    val hands: Map<HandSide, HandStatus> = emptyMap(),
)

/** Where the cursor aims from on the hand. */
enum class AimPoint(val label: String) {
    /** Wrist and middle, ring and pinky knuckles ([HandFrame.steadyPoint]): untouched by the pinch fingers. */
    STEADY("wrist and knuckles"),

    /** Weighted knuckles and wrist including the index knuckle ([HandFrame.palmPoint]), as in 0.3.2 to 0.3.4. */
    PALM("palm"),

    /** The index knuckle alone, as up to 0.3.1. */
    INDEX_KNUCKLE("index knuckle"),

    /**
     * 0.5.1, the hand ray's default: between the thumb and index tips ([HandFrame.pinchPoint]),
     * where the pinch happens. Both tips close on it, so it moves little as a pinch closes, and
     * the pinch lock freezes the cursor for what is left.
     */
    PINCH("pinch point"),

    /**
     * 0.5.2, the hand ray's default: Meta's stable pinch position ([HandFrame.stablePinchPoint]),
     * a fixed offset from the knuckles to about where a pinch closes. Close to [PINCH] where the
     * cursor sits, but the thumb and index closing do not move it.
     */
    STABLE_PINCH("stable pinch point on the knuckles"),

    /**
     * 0.5.9, the hand ray's default: the same spot as [STABLE_PINCH], built from the mean of the
     * four knuckles and a smoothed offset ([SteadyAnchor]), about 0.4 of its jitter. Needs the
     * ray builder's state; without it (image mode) it is [STABLE_PINCH].
     */
    STEADY_PINCH("steady pinch point (knuckle mean and smoothed offset)"),
}

/** The point on this hand that [aim] names. */
fun HandFrame.point(aim: AimPoint): Landmark = when (aim) {
    AimPoint.STEADY -> steadyPoint
    AimPoint.PALM -> palmPoint
    AimPoint.INDEX_KNUCKLE -> this[HandFrame.INDEX_MCP]
    AimPoint.PINCH -> pinchPoint
    AimPoint.STABLE_PINCH, AimPoint.STEADY_PINCH -> stablePinchPoint
}

/**
 * One remembered frame: when it was captured, where the cursor was shown, the pinch ratio, the
 * time the shown point stands for ([pointMs], later than [timeMs] by the prediction lead) and
 * where the smoothed hand itself was ([hand], without lock or prediction).
 */
data class AimSample(
    val timeMs: Long,
    val point: Point,
    val ratio: Float,
    val pointMs: Long = timeMs,
    val hand: Point = point,
    val raw: Point = hand,
    /** 0.5.17: the hand's size jumped on this frame (a broken frame); its ratio proves no closing ([PinchLockSettings.proofSkipsBrokenFrames]). */
    val broken: Boolean = false,
)

/**
 * Finds where a pinch really started (or ended) in the recent past. A pinch is recognised a
 * frame or two after the fingers start closing, and by then the closing motion has already
 * pulled the cursor off its target, so the press goes where the cursor was when the ratio
 * started to fall: walking back from the newest sample while the ratio keeps rising going back
 * by more than [step] a frame (for a release, falling), within [maxMs] and at least [minMs]
 * back. It stops where the fingers were steady, so a hand that was still moving onto its target
 * just before a quick pinch is not rewound further than the pinch itself.
 */
class PinchRewind(val minMs: Long = 50, val maxMs: Long = 150, val step: Float = 0.01f) {
    init {
        require(minMs in 0..maxMs) { "Need 0 <= minMs <= maxMs" }
    }

    /** [samples] oldest first, the newest being the frame that changed the pinch, at [nowMs]. */
    fun onset(samples: List<AimSample>, nowMs: Long, closing: Boolean): AimSample = samples[onsetIndex(samples, nowMs, closing)]

    /** The index in [samples] of [onset]'s answer. */
    fun onsetIndex(samples: List<AimSample>, nowMs: Long, closing: Boolean): Int {
        require(samples.isNotEmpty()) { "Needs at least one sample" }
        val oldest = nowMs - maxMs
        var i = samples.lastIndex
        while (i > 0) {
            val earlier = samples[i - 1]
            if (earlier.timeMs < oldest) break
            val later = samples[i]
            val stillMoving = if (closing) earlier.ratio - later.ratio > step else later.ratio - earlier.ratio > step
            if (!stillMoving) break
            i--
        }
        // At least minMs back, as long as that stays within maxMs.
        while (i > 0 && samples[i].timeMs > nowMs - minMs && samples[i - 1].timeMs >= oldest) i--
        return i
    }

    /**
     * Cuts a rewind to [from] short (0.3.6): walking back from the newest sample, stop before a
     * sample shown more than [capPx] from [hand] (where the hand is at the onset) or before a
     * step where the hand moved faster than [fastPxPerS]. Mike's 0.3.5 log had locks rewound 291
     * and 660 px across fast moves, so the press landed far from where he was aiming. Returns
     * the index to lock to and whether the cap cut the rewind.
     */
    fun capped(samples: List<AimSample>, from: Int, hand: Point, capPx: Float, fastPxPerS: Float): Pair<Int, Boolean> {
        var j = samples.lastIndex
        while (j > from) {
            val earlier = samples[j - 1]
            val later = samples[j]
            val dt = (later.timeMs - earlier.timeMs).coerceAtLeast(1L)
            val speed = later.hand.distanceTo(earlier.hand) * 1000f / dt
            if (earlier.point.distanceTo(hand) > capPx || speed > fastPxPerS) break
            j--
        }
        return j to (j > from)
    }
}

/**
 * 0.5.4: Quest's select point. The press lands where the steady cursor was a short, fixed time
 * before the pinch was recognised, about when the fingers started to close.
 */
object PinchSelect {
    /**
     * The newest of [samples] (oldest first) at least [backMs] before [nowMs], or the oldest one
     * when the hand has not been seen that long; but never one whose steady cursor
     * ([AimSample.hand]) is more than [capPx] from [here], the steady cursor now: then the oldest
     * newer one within it, so a pinch at the end of a sweep or after a tracking leap does not
     * land on the filter's trail (0.3.6's cap, Mike's 0.3.5 pinches 10 and 14). Null when none
     * qualifies, and the lock stays at [here]. The flag says the cap cut it short.
     */
    fun lookBack(samples: Collection<AimSample>, nowMs: Long, backMs: Long, maxMs: Long, here: Point, capPx: Float): Pair<AimSample?, Boolean> {
        val recent = samples.filter { it.timeMs >= nowMs - maxMs && it.timeMs < nowMs }
        val wanted = recent.indexOfLast { it.timeMs <= nowMs - backMs }.coerceAtLeast(0)
        if (recent.isEmpty()) return null to false
        for (i in wanted..recent.lastIndex) {
            if (recent[i].hand.distanceTo(here) <= capPx) return recent[i] to (i > wanted)
        }
        return null to true
    }
}

/**
 * Turns tracked hands into pointer signals: the hand ([aim], by default the wrist and the
 * knuckles the pinch does not move) aims the cursor, pinch with hysteresis clicks, and an open
 * palm held for [palmHoldMs] shows the dock (the Space's launcher). Since 0.4.1 the palm must
 * face the glasses camera ([PalmFacing], when [palmMustFaceCamera]), so an open hand with its
 * back to the camera does not open it by accident.
 *
 * Pinch (0.3.5 and 0.3.6, see [PinchLockSettings]): as soon as the thumb to index gap is
 * clearly closing the aim locks where the steady cursor was [PinchLockSettings.selectBackMs]
 * before (0.5.4, [PinchSelect]), and the cursor stops. The lock then ends in a
 * click (the ratio reaches the press threshold this hand has shown it reaches, see
 * [PinchCalibration], or stops closing) or a clear abort (the fingers reopen, or the hand is
 * lost). The press goes to the locked spot, the cursor does not move at all while pinched
 * until a deliberate drag, and the release goes to the locked spot too, so a click lands where
 * it was aimed. A drag carries the offset between the lock and the hand so it does not jump,
 * and its release goes where the cursor was before the fingers started opening.
 *
 * Moves are smoothed with [filter] (One Euro, then a soft dead zone) and predicted ahead by the
 * latency passed to [update] (or the filter's default, at most one frame since 0.5.4); a single
 * bad frame is skipped (0.5.4, see [PinchReadiness]); [update]'s time should be the frame's capture time, not when its
 * result came back, so smoothing and prediction see the hand's real timing. Every MoveTo, Down
 * and Up carries the time its position stands for, which the Space matches with the head pose.
 */
class HandGestureRecognizer(
    var displayWidth: Int,
    var displayHeight: Int,
    var airBox: AirBox = AirBox(),
    private val pinch: PinchDetector = PinchDetector.quest(),
    val palmHoldMs: Long = 450,
    val palmMustFaceCamera: Boolean = true,
    /**
     * 0.5.7: 450 ms (was 300) before a hand MediaPipe stopped finding is lost: the cursor, an aim
     * lock and a pinch hold through brief drop outs (Mike's 0.5.6 "hand 43%" frames) while the
     * camera finds it again, which the whole view search now does in a frame or two.
     */
    val lostAfterMs: Long = 450,
    val lock: PinchLockSettings = PinchLockSettings(),
    val filter: PointerFilterSettings = PointerFilterSettings(),
    var aim: AimPoint = AimPoint.STEADY,
    /** 0.5.0: the confidence gate; [PinchReadiness.OFF] lets every frame through. */
    val readiness: PinchReadiness = PinchReadiness(),
    /**
     * 0.5.0: pinch on Meta's measure, the index tip to the thumb's bones
     * ([HandFrame.pinchDistance]); false keeps the tip to tip [HandFrame.pinchRatio].
     */
    val boneMeasure: Boolean = true,
) {
    /**
     * 0.5.0, ray mode: the body anchored hand ray ([HandRayBuilder]) aims instead of the hand's
     * place in the picture through [airBox]. Its head frame direction becomes a display pixel
     * through [frustum] (the Space's view), so locks, drags and smoothing keep working in pixels,
     * about 30 per degree. Null is the image mode, as before 0.5.0.
     */
    var ray: HandRayBuilder? = null

    /** The view hand pixels stand for in ray mode, and the pixels per degree drags are measured in. */
    var frustum: ViewFrustum = ViewFrustum(displayWidth, displayHeight, DEFAULT_FOV_DEG)

    /** 0.5.0: the head pose at a camera capture time, for the ray's neck; null without head tracking. */
    var headAt: ((Long) -> Quat?)? = null

    /**
     * 0.5.44: how long before its capture time a frame was taken, ms; the ray is smoothed in the room with the head
     * pose of then. The Space's own figure ([com.hayeco.flipspaces.core.spatial.PointerSettings.handCameraDelayMs]).
     */
    var headDelayMs: Long = 20

    /** 0.5.44: whether the ray filter's state is in the room (true) or the head's frame; a change starts it again. */
    private var filteredInRoom = false

    /** 0.5.44: the last smoothed ray in the room, which a frame MediaPipe is unsure of holds. */
    private var steadyRoom: Vec3? = null

    /** 0.5.18: the phone being handled turns presses off ([PhoneHandling]); null in tests that do not ask. */
    var phoneHandling: PhoneHandling? = null

    /** 0.5.18: this hand was seen open above the picture's bottom band since it was found ([PinchLockSettings.lowBandShare]). */
    private var openAboveBand = false

    /** 0.5.18: when the fingers' current open run began, and how long the last one lasted. */
    private var openRunStartMs: Long? = null
    private var lastOpenRunMs = 0L

    /** 0.5.0: which hand this recognizer follows when [HandsRecognizer] tracks two apart. */
    var side: HandSide? = null

    /** The last ray built in ray mode (origin and direction, head frame), for the signals. */
    val lastRay: HandRayResult? get() = ray?.last

    /** The head frame ray through display pixel [p], in ray mode; null in image mode. */
    fun rayThrough(p: Point): Pair<Vec3, Vec3>? {
        val r = ray?.last ?: return null
        return r.origin to frustum.rayThrough(p.x, p.y)
    }

    private val filterX = filter.filter()
    private val filterY = filter.filter()

    /** 0.5.9: in ray mode the ray is smoothed in degrees ([RayAimFilter]) instead of by [filterX] and [filterY]. */
    private val rayFilter = filter.angle?.let { RayAimFilter(it) }
    private var angleSmoothed = false
    private val history = ArrayDeque<AimSample>()

    /** 0.5.4: the filtered aim after the dead zone ([PointerFilterSettings.deadZoneWeight]). */
    private var steady: Point? = null

    /**
     * 0.5.4, one bad frame is skipped: how many frames in a row were not ready or jumped, the
     * size of a skipped frame, and the last accepted unsmoothed aim and its time.
     */
    private var badStreak = 0
    private var skippedSize: Float? = null
    private var lastRaw: Point? = null
    private var lastRawMs = 0L

    /**
     * 0.5.2: the strength lock may fire: the strength was below its re-arm level since the last
     * one (and since the hand appeared, so a hand that comes in half closed does not freeze).
     */
    private var strengthArmed = false
    private var strengthLowSinceMs: Long? = null

    /**
     * 0.5.2: the held lock came from the pinch strength and the ratio has not yet closed to the
     * closing onset ([lockByStrength]); it started that way ([lockFromStrength]); the highest
     * strength since.
     */
    private var lockByStrength = false
    private var lockFromStrength = false
    private var lockStrengthMax = 0f
    private val openingRewind = PinchRewind()
    private var lastSeenMs = Long.MIN_VALUE
    private var lastCursor: Point? = null

    /** 0.5.7: where a free cursor stood on the last frame with a hand (null under a pinch or lock), for the coast. */
    private var coastFrom: Point? = null
    private var coasted = false

    /** 0.5.7: frames with no hand since the last one with it; the size check skips the first frame back. */
    private var missedFrames = 0
    private var palmSinceMs: Long? = null
    private var palmFired = false

    /**
     * 0.5.46 (Mike: "users should be able to open the app launcher by turning their hand toward them and pinching,
     * exactly like how meta quest does it"): a pinch made with the palm turned to the glasses ([PalmFacing]) for at
     * least [PALM_MENU_MS] before it opens or closes the launcher ([PointerSignal.ShowDock]) instead of pressing. An
     * aiming hand shows the camera its back, so its pinches are untouched. The app turns it on ([HandFeed]); off here,
     * as the replays' synthetic hands are drawn palm on.
     */
    var palmPinchOpensLauncher = false

    /**
     * 0.5.47 (Mike, vc67: the palm pinch "is backwards ... when my hand is turned it thinks it's normal, and when I don't
     * turn my hand it thinks I'm doing the turn gesture"): the palm side read the other way round from [PalmFacing]'s
     * rule for the Aura's picture and the NPU's handedness. True (the app's) swaps it; every pinch logs the reading
     * ([PinchTrace.PalmRead]).
     */
    var palmSideFlipped = false

    /** 0.5.47: the palm turned to the glasses now, with [palmSideFlipped] applied. */
    private fun palmToward(hand: HandFrame): Boolean = hand.rightHand != null && (PalmFacing.towardCamera(hand) != palmSideFlipped)

    /** 0.5.46: since when the palm has faced the glasses without a break, and whether the pinch now held is that menu pinch. */
    private var palmTowardSinceMs: Long? = null
    private var menuPinch = false

    /**
     * 0.5.50 (Mike: "if the user turns their palm and holds the pinch, then it activates the Recentre feature"): the menu
     * pinch's start and whether its hold already recentred. As on Meta Quest, the launcher opens when a short menu pinch
     * lets go (0.5.46 opened it on the press); held [PALM_RECENTRE_MS], the pinch recentres instead.
     */
    private var menuPinchMs = 0L
    private var menuRecentred = false

    /** 0.5.46: the palm faces the glasses now and has for [PALM_MENU_MS] (for drawing the menu hint at the hand too). */
    var palmMenuReady = false
        private set
    private var present = false

    /** Learned pinch thresholds, one per hand (MediaPipe's handedness; null when unknown). */
    private val calibrations = HashMap<Boolean?, PinchCalibration>()

    /** The held aim: the shown point, the time it stands for, and since when it is held. */
    private data class Lock(val point: Point, val pointMs: Long, val sinceMs: Long)

    private var held: Lock? = null
    private var pressOrigin: Point? = null
    private var pressMs = 0L
    private var dragOffset: Point? = null

    /** 0.5.5: the hand on the last pinched frame before the fingers began to open, for the release's flick rule. */
    private var heldHand: Point? = null

    /** 0.5.5: the drag rules fired within the tap time, when no drag may start yet. */
    private var dragWanted = false

    /** The armed lock: the ratio it closed from, its lowest ratio, frames since that low. */
    private var lockFrom = 0f
    private var lockMin = Float.MAX_VALUE
    private var sinceLow = 0
    private var onsetIgnored = false

    /** After a lock ends: where the cursor glides back from, then the remaining offset. */
    private var glideFrom: Point? = null
    private var glide: Point? = null
    private var glideMs = 0L

    /** 0.5.0: confidence gate state: since when the hand is not ready, the last hand size, logged once. */
    private var notReadySinceMs: Long? = null
    private var lastHandSize: Float? = null
    private var notReadyLogged = false

    /** 0.5.0: the image tip to tip ratio's low while pinched, for the release from the picture. */
    private var imageLow = Float.MAX_VALUE

    /** 0.5.4: frames in a row the picture showed the tips apart; two release, one does not. */
    private var imageOpen = 0

    /**
     * 0.5.11, the press gate ([PressGate]): since when the ratio is at or below the press
     * threshold, when the fingers last closed for real, and the last refusal logged (one line per
     * kind until the fingers open again).
     */
    private var belowSinceMs: Long? = null
    private var lastCloseMs: Long? = null

    /** 0.5.14: the last frame the fingers were open (at or above the release ratio), for the press gate and pinch onset. */
    private var lastOpenMs: Long? = null
    private var refusedKey: String? = null

    /** A press short of only its hold time: logged if the fingers open again without pressing. */
    private var pendingHold: String? = null
    private var pendingHoldDetail: String? = null

    /** 0.5.16: frames in a row at or below the press threshold, the last frame's time, and the aim in the room. */
    private var framesBelow = 0
    private var prevFrameMs = Long.MIN_VALUE
    private val roomAims = ArrayDeque<Triple<Long, Vec3, Quat>>()

    /**
     * 0.5.17: this hand's last release, a "hand moving" refusal held
     * for the rest of its closing, and the last frame that was not ready or was skipped.
     */
    private var lastReleaseMs: Long? = null
    private var movingHeld = false
    private var bounceHeld = false
    private var lastBadMs: Long? = null

    /**
     * 0.5.16: the ray's angular speed in the room and the head's own, deg/s, over the last
     * [SPEED_WINDOW_MS]; null without a head pose (image mode, no tracking).
     */
    private fun roomAimSpeed(dir: Vec3?, nowMs: Long): Pair<Float, Float>? {
        val head = headAt?.invoke(nowMs)
        if (dir == null || head == null) {
            roomAims.clear()
            return null
        }
        val w = head.rotate(dir).normalized()
        val then = roomAims.firstOrNull { it.first >= nowMs - SPEED_WINDOW_MS && it.first < nowMs }
        roomAims.addLast(Triple(nowMs, w, head))
        while (roomAims.size > 2 && roomAims.first().first < nowMs - 2 * SPEED_WINDOW_MS) roomAims.removeFirst()
        then ?: return 0f to 0f
        val dt = (nowMs - then.first).coerceAtLeast(1L) / 1000f
        val aim = kotlin.math.acos((w dot then.second).coerceIn(-1f, 1f)) * (180f / kotlin.math.PI.toFloat())
        val turn = (head * then.third.conjugate()).normalized()
        val headDeg = 2f * kotlin.math.acos(kotlin.math.abs(turn.w).coerceIn(0f, 1f)) * (180f / kotlin.math.PI.toFloat())
        return aim / dt to headDeg / dt
    }

    val pinched: Boolean get() = pinch.pinched

    /** Where the cursor was last shown, display pixels; null before the first hand. */
    val cursor: Point? get() = lastCursor

    /** The press and release ratios in use, for the setup screen. */
    val thresholds: Pair<Float, Float> get() = pinch.enterRatio to pinch.exitRatio

    /**
     * Feeds one camera frame's hand, or null when no hand was found, captured at [nowMs].
     * [latencyMs] is how far behind the frame is by the time the cursor shows (see
     * [LatencyEstimator]); null uses the filter's default prediction.
     */
    fun update(hand: HandFrame?, nowMs: Long, latencyMs: Float? = null): HandUpdate {
        if (hand == null) return missing(nowMs)
        lowInPicture = hand.nearBottom()
        val u = seen(hand, nowMs, latencyMs)
        return if (lowInPicture) u.copy(status = u.status.copy(nearBottom = true)) else u
    }

    /** 0.5.8: the last frame with a hand had it near the bottom of the picture; kept through a gap. */
    private var lowInPicture = false

    private fun seen(hand: HandFrame, nowMs: Long, latencyMs: Float?): HandUpdate {
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        // 0.5.7: back after a drop out. MediaPipe's first frame of a hand found again is not
        // compared in size with the last one before it (Mike's "hand size jumped 241%" on
        // re-acquire), and a cursor that coasted glides back to the hand rather than jumping.
        val afterGap = missedFrames > 0
        missedFrames = 0
        if (coasted) {
            coasted = false
            if (!pinch.pinched && held == null) glideFrom = lastCursor
        }
        // 0.5.30 (Mike: a smooth hand lost and found hand-off): a hand found again within [FOUND_GLIDE_MS] of the last
        // one glides from where the cursor was shown to the new hand (the glide's 90 ms), instead of jumping there.
        if (!present && lastCursor != null && lastSeenMs != Long.MIN_VALUE && nowMs - lastSeenMs in 0..FOUND_GLIDE_MS) glideFrom = lastCursor
        // Back after a gap with no empty frames in between: end what the gap would have ended.
        if (present && nowMs - lastSeenMs >= lock.lockLostMs && (held != null || pinch.pinched)) {
            val gap = missing(nowMs)
            signals += gap.signals
            traces += gap.traces
            missedFrames = 0
            coasted = false
        }
        lastSeenMs = nowMs
        present = true

        val calibration = if (lock.adaptive) calibrations.getOrPut(hand.rightHand) { PinchCalibration() } else null
        // Thresholds change only between pinches, never under one.
        if (calibration != null && !pinch.pinched) calibration.tune(pinch)

        val aimAt = hand.point(aim)
        val builder = ray
        val built = builder?.build(hand, nowMs, headAt?.invoke(nowMs))
        val raw = if (built != null) rayPixel(built.dir) else airBox.toDisplay(aimAt, displayWidth, displayHeight)
        val ratio = if (boneMeasure) hand.pinchDistance(hold = pinch.pinched) else hand.pinchRatio
        val notReady = readiness.reason(hand, if (afterGap) null else lastHandSize, if (afterGap) null else skippedSize)
        // 0.5.4: a single bad frame (a size jump, a finger off the picture, the aim leaping further
        // than a hand moves in a frame) is skipped: cursor, lock and pinch hold still for it.
        val leap = lastRaw?.let { r ->
            val dt = nowMs - lastRawMs
            dt <= LEAP_WINDOW_MS && raw.distanceTo(r) > lock.leapDegPerS * frustum.pixelsPerDegree * dt.coerceAtLeast(MIN_FRAME_MS) / 1000f
        } == true
        if (notReady != null || leap) lastBadMs = nowMs
        if ((notReady != null || leap) && badStreak == 0 && history.isNotEmpty() && lastCursor != null) {
            badStreak = 1
            skippedSize = hand.handSize
            val why = notReady ?: "aim leapt %.0f px in one frame".fmt(raw.distanceTo(lastRaw!!))
            val locked = held != null && dragOffset == null
            val status = HandStatus(true, null, pinch.pinched, if (pinch.pinched) 1f else 0f, lastCursor, ratio, locked, side, true, built)
            return HandUpdate(signals, status, traces + PinchTrace.FrameSkipped(lastCursor!!, why))
        }
        badStreak = if (notReady != null) badStreak + 1 else 0
        skippedSize = null
        lastRaw = raw
        lastRawMs = nowMs
        // 0.5.17: a size jump from the last frame kept, even one the readiness took as a real change
        // because it agreed with a skipped frame: its ratio is no proof of an open hand or a closing.
        val sizeJumped = !afterGap && lastHandSize?.let { it > 1e-4f && kotlin.math.abs(hand.handSize / it - 1f) > readiness.maxSizeJump } == true
        lastHandSize = hand.handSize
        if (notReady == null) {
            notReadySinceMs = null
            notReadyLogged = false
        } else if (notReadySinceMs == null) {
            notReadySinceMs = nowMs
        }
        val recent = history.filter { it.timeMs >= nowMs - lock.closingWindowMs }
        val recentMax = maxOf(recent.maxOfOrNull { it.ratio } ?: ratio, ratio)
        val recentMin = minOf(recent.minOfOrNull { it.ratio } ?: ratio, ratio)
        val wasPinched = pinch.pinched
        val closing = !wasPinched && ratio < lock.dampBelowRatio && recentMax - ratio >= lock.dampDrop
        val opening = ratio - recentMin >= lock.dampDrop && ratio > pinch.enterRatio
        // 0.5.2: the pinch strength before this frame's press, for the strength lock and the damping.
        val strengthNow = when {
            notReady != null -> 0f
            calibration != null -> calibration.value(ratio)
            else -> strength(ratio)
        }
        // 0.5.2: the closer the fingers, the harder the smoothing, down to the closing damp at the
        // strength lock, so the cursor has stopped by the time it freezes (Meta's and Android XR's feel).
        val strengthScale = lock.strengthLock?.takeIf { !wasPinched }?.let { s -> 1f - (1f - lock.dampFactor) * (strengthNow / s).coerceIn(0f, 1f) } ?: 1f
        val scale = minOf(if (closing) lock.dampFactor else 1f, strengthScale)
        val angular = rayFilter.takeIf { built != null }
        angleSmoothed = angular != null
        // 0.5.10: a frame MediaPipe is unsure of does not nudge the cursor; it holds where it was.
        val unsure = angular != null && hand.score < angular.settings.minScore && steady != null
        // 0.5.44: the head pose the Space puts this frame's ray in the room with (SpatialPointer: capture time less
        // the camera's delay), so the ray is smoothed in the room and handed on in the head's frame of its frame.
        val room = if (angular != null && angular.settings.inRoom) headAt?.invoke(nowMs - headDelayMs) else null
        if (angular != null && (room != null) != filteredInRoom) {
            angular.reset()
            filteredInRoom = room != null
        }
        val smooth = if (unsure) {
            // 0.5.44: held where it was in the room, not on the display (it would ride with the head).
            room?.let { q -> steadyRoom?.let { rayPixel(q.conjugate().rotate(it)) } } ?: steady!!
        } else if (angular != null) {
            val dir = if (room != null) {
                room.conjugate().rotate(angular.filter(room.rotate(built!!.dir), nowMs, scale).also { steadyRoom = it })
            } else {
                steadyRoom = null
                angular.filter(built!!.dir, nowMs, scale)
            }
            rayPixel(dir).also { steady = it }
        } else {
            steadied(Point(filterX.filter(raw.x, nowMs, scale), filterY.filter(raw.y, nowMs, scale)))
        }
        val speed = handSpeed(raw, nowMs)

        // 0.5.11: the press gate's inputs: time under the press threshold, the last real closing
        // and the aim's speed in degrees a second.
        // 0.5.17: only frames after this hand's last release count, so the previous pinch's own
        // closing (the open hand before it is the window's maximum) cannot vouch for the next one.
        val proofFrom = lastReleaseMs?.takeIf { lock.proofAfterRelease } ?: Long.MIN_VALUE
        val skipBroken = lock.proofSkipsBrokenFrames
        val windowMax = maxOf(
            history.filter { it.timeMs >= nowMs - lock.closeProofWindowMs && it.timeMs > proofFrom && !(skipBroken && it.broken) }.maxOfOrNull { it.ratio } ?: ratio,
            ratio,
        )
        if (notReady == null && windowMax - ratio >= lock.closeProofDrop) lastCloseMs = nowMs
        // 0.5.18: the bottom band of the picture, where the phone is held ([PinchLockSettings.lowBandShare]).
        val inLowBand = lock.lowBandShare > 0f && hand.nearBottom(1f - lock.lowBandShare, lock.lowBandWrist)
        val openNow = ratio >= pinch.exitRatio && hand.score >= readiness.minScore
        if (!inLowBand && openNow) openAboveBand = true
        // How long the fingers were held open, without a break, before this closing: a hand that
        // rests low and opens on purpose (0.5.6's, LowHandPinchTest) is open for a quarter second;
        // the fingers of a hand holding the phone flick open for a frame or three.
        if (openNow) {
            val start = openRunStartMs ?: nowMs.also { openRunStartMs = it }
            lastOpenRunMs = nowMs - start
        } else {
            openRunStartMs = null
        }
        val lowBandRefused = inLowBand && !openAboveBand && lastOpenRunMs < lock.lowBandOpenMs
        // 0.5.14: the fingers open on a frame MediaPipe is sure of, at the edge of the picture too.
        // 0.5.17: not on a frame whose hand size jumped (press 32's "closing from 1.34").
        if (ratio >= pinch.exitRatio && hand.score >= readiness.minScore && !(skipBroken && sizeJumped)) lastOpenMs = nowMs
        // 0.5.17: reopened since the last release (a threshold release's own frame counts).
        val reopened = lastReleaseMs.let { rel -> rel == null || (lastOpenMs ?: Long.MIN_VALUE) >= rel }
        val openForProof = lastOpenMs?.takeIf { it >= proofFrom }
        lock.movingHoldRise?.let {
            if (ratio > pinch.enterRatio + it) {
                movingHeld = false
                bounceHeld = false
            }
        }
        belowSinceMs = if (notReady != null || ratio > pinch.enterRatio) null else belowSinceMs ?: nowMs
        framesBelow = if (belowSinceMs == null) 0 else framesBelow + 1
        val frameGapMs = if (prevFrameMs == Long.MIN_VALUE) null else nowMs - prevFrameMs
        prevFrameMs = nowMs
        // 0.5.16: the aim's speed in the room for the velocity gate ([PinchLockSettings.pressSpeedInRoom]).
        val roomSpeed = roomAimSpeed(built?.dir, nowMs)
        if (ratio > pinch.enterRatio) {
            pendingHold?.let { if (!wasPinched) traces += PinchTrace.Refused(smooth, ratio, it, pendingHoldDetail.orEmpty()) }
            pendingHold = null
            pendingHoldDetail = null
        }
        if (ratio > pinch.enterRatio + lock.stableMargin) refusedKey = null
        val speedDeg = speed / displayPixelsPerDegree()
        // 0.5.16: in the room while the head pose is known; the display figure too while the head
        // turns fast enough to smear the picture.
        val gateSpeedDeg = when {
            !lock.pressSpeedInRoom || roomSpeed == null -> speedDeg
            roomSpeed.second > lock.pressHeadBlurDegPerS -> maxOf(roomSpeed.first, speedDeg)
            else -> roomSpeed.first
        }
        fun gate(needHold: Boolean): String? = PressGate.refusal(
            lock, ratio, pinch.enterRatio, belowSinceMs?.let { nowMs - it }, needHold, lastCloseMs?.let { nowMs - it },
            gateSpeedDeg, hand.score, readiness.minScore, openForProof?.let { nowMs - it }, framesBelow,
            sinceReleaseMs = lastReleaseMs?.let { nowMs - it },
            reopened = reopened || lock.rearmAfterReleaseMs <= 0,
            movingHeld = movingHeld,
            bounceHeld = bounceHeld,
            sinceBadFrameMs = lastBadMs?.takeIf { lock.badFrameQuietMs > 0 }?.let { nowMs - it },
            phoneHandled = phoneHandling?.muted(nowMs),
            lowBand = lowBandRefused,
            // 0.5.19: no press from glasses off the head.
            notWorn = phoneHandling?.notWorn(nowMs),
        )
        // 0.5.16: every refusal says what the gate saw, so the next log tells why.
        // 0.5.17: every press too, with the release, reopen and bad frame figures.
        fun detail(): String = "frames below %d, frame gap %s, open %s ago, closing %s ago, release %s ago%s, bad frame %s ago, aim %.0f deg/s %s (display %.0f), head %s, score %.2f".fmt(
            framesBelow, frameGapMs?.let { "$it ms" } ?: "n/a", lastOpenMs?.let { "${nowMs - it} ms" } ?: "never", lastCloseMs?.let { "${nowMs - it} ms" } ?: "never",
            lastReleaseMs?.let { "${nowMs - it} ms" } ?: "never", if (lastReleaseMs == null) "" else if (reopened) " (reopened)" else " (not reopened)",
            lastBadMs?.let { "${nowMs - it} ms" } ?: "never",
            gateSpeedDeg, if (roomSpeed != null && lock.pressSpeedInRoom) "in the room" else "on the display", speedDeg,
            roomSpeed?.let { "%.0f deg/s".fmt(it.second) } ?: "not known", hand.score,
        )
        fun refuse(why: String) {
            // A hold time refusal is the normal first frame of every pinch: logged only if no press follows.
            if (PressGate.onlyHold(why)) {
                pendingHold = why
                pendingHoldDetail = detail()
                return
            }
            // 0.5.17: a hand refused for moving stays refused for the rest of this closing.
            if (lock.movingHoldRise != null && PressGate.kind(why) == "hand moving") movingHeld = true
            // 0.5.17: so is a bounce: a closing that began too soon after the release.
            if (lock.movingHoldRise != null && PressGate.kind(why) == "too soon") bounceHeld = true
            val key = why.split(' ').take(2).joinToString(" ")
            if (key != refusedKey) traces += PinchTrace.Refused(smooth, ratio, why, detail())
            refusedKey = key
        }

        var isPinched: Boolean
        var how = ""
        var releasedBy = ""
        // 0.5.5: a press waiting for its aim lock (the ratio pressed with no lock held).
        var pressWaits = false
        // 0.5.5: a lock needs a steady sample from at least the select look back ago, so a hand
        // just found (MediaPipe's first frames of it are the least steady) never locks on itself.
        val lockable = history.any { it.timeMs <= nowMs - lock.selectBackMs }
        if (notReady != null) {
            // Not ready: nothing starts; a press holds for a moment, then ends as if the hand were lost.
            isPinched = wasPinched
            val heldFor = nowMs - (notReadySinceMs ?: nowMs)
            if (wasPinched && heldFor >= readiness.holdMs) {
                pinch.release("not ready")
                isPinched = false
                releasedBy = "not ready: $notReady"
            }
            if (!notReadyLogged) {
                notReadyLogged = true
                traces += PinchTrace.NotReady(smooth, notReady, if (wasPinched) "the press holds for ${readiness.holdMs} ms" else "no pinch starts")
            }
        } else {
            isPinched = pinch.update(ratio)
            if (isPinched && !wasPinched) {
                // 0.5.11: the press gate. Short of only the hold time, the aim still locks (below)
                // and the press comes on a later frame of the same closing.
                gate(needHold = true)?.let { why ->
                    pinch.unpress()
                    isPinched = false
                    refuse(why)
                    if (PressGate.onlyHold(why) && held == null) pressWaits = true
                }
            }
            if (isPinched && !wasPinched && held == null) {
                // 0.5.5: every press follows its aim lock by at least a frame. Mike's 0.5.4 log had
                // presses "locked 0 ms before" with the aim where the hand was that frame, which
                // then jumped 1128 px. Lock now (below) and press on the next frame.
                pinch.unpress()
                isPinched = false
                pressWaits = true
            }
            if (isPinched && !wasPinched) how = "ratio %.2f below %.2f".fmt(ratio, pinch.enterRatio)
            if (wasPinched && !isPinched) releasedBy = pinch.releasedBy
            // The picture's own say: tips clearly apart again means open, whatever the world landmarks.
            val imageRatio = hand[HandFrame.THUMB_TIP].distanceTo(hand[HandFrame.INDEX_TIP]) / hand.handSize
            val rise = lock.imageReleaseRise
            if (isPinched && wasPinched) {
                imageLow = minOf(imageLow, imageRatio)
                imageOpen = if (rise != null && imageRatio - imageLow >= rise) imageOpen + 1 else 0
                if (imageOpen >= IMAGE_RELEASE_FRAMES) {
                    pinch.release("the picture")
                    isPinched = false
                    releasedBy = "the picture shows the tips %.2f apart (from %.2f)".fmt(imageRatio, imageLow)
                }
            } else {
                imageLow = imageRatio
                imageOpen = 0
            }
        }

        // Closing onset: lock the aim before the press, unless the hand is still moving fast.
        // 0.5.2: also as soon as the pinch strength reaches the strength lock, rising.
        val ratioOnset = ratio < lock.onsetRatio && recentMax - ratio >= lock.onsetDrop
        val strengthOnset = lock.strengthLock?.let { strengthArmed && strengthNow >= it } == true
        if (notReady == null && !wasPinched && held == null && lockable && (ratioOnset || strengthOnset)) {
            if (speed > lock.fastMovePxPerS && !isPinched) {
                if (!onsetIgnored) traces += PinchTrace.OnsetIgnored(smooth, ratio, speed)
                onsetIgnored = true
            } else {
                traces += PinchTrace.ClosingOnset(smooth, ratio, recentMax, if (ratioOnset) null else strengthNow)
                if (ratioOnset) calibration?.noteOpen(recentMax)
                lockAim(nowMs, smooth, ratio, recentMax, signals, traces)
                lockByStrength = !ratioOnset
                lockFromStrength = !ratioOnset
                lockStrengthMax = strengthNow
                if (strengthOnset) strengthArmed = false
            }
        }
        if (pressWaits && held == null && lockable) {
            traces += PinchTrace.ClosingOnset(smooth, ratio, recentMax)
            lockAim(nowMs, smooth, ratio, recentMax, signals, traces)
            lockByStrength = false
            lockFromStrength = false
        }
        lock.strengthLock?.let {
            // Armed again only after the fingers stayed clearly open for a moment, not by the
            // bounce of a release (a quick second pinch still locks on the closing onset).
            if (strengthNow < it - lock.strengthRearm) {
                val since = strengthLowSinceMs ?: nowMs.also { t -> strengthLowSinceMs = t }
                if (nowMs - since >= lock.strengthRearmMs) strengthArmed = true
            } else {
                strengthLowSinceMs = null
            }
        }
        if (held != null && !isPinched) {
            lockStrengthMax = maxOf(lockStrengthMax, strengthNow)
            // Closed far enough for the ratio's own rules (fingers reopening, press when stable).
            if (lockByStrength && ratio < lock.onsetRatio) lockByStrength = false
        }
        if (ratio >= lock.onsetRatio) onsetIgnored = false

        // An armed lock becomes a press at the threshold, or once the fingers stop closing.
        val armed = held
        if (notReady == null && !wasPinched && !isPinched && armed != null) {
            if (armed.sinceMs == nowMs) {
                // Locked on this very frame: lockAim set the low.
            } else if (ratio < lockMin - lock.stableStep) {
                lockMin = ratio
                sinceLow = 0
            } else {
                lockMin = minOf(lockMin, ratio)
                sinceLow++
            }
            val near = ratio <= lockMin + lock.reopenRise / 2f
            how = when {
                // 0.5.5: never on the lock's own frame, so every press is "locked 33 ms before" or more.
                armed.sinceMs == nowMs -> ""
                ratio <= pinch.enterRatio -> "ratio %.2f at or below %.2f".fmt(ratio, pinch.enterRatio)
                // 0.5.11: the stable and held rules only within stableMargin of the press threshold.
                sinceLow >= lock.stableFrames && near && ratio < lock.onsetRatio && ratio <= pinch.enterRatio + lock.stableMargin &&
                    lockFrom - lockMin >= lock.minPressDrop ->
                    "stable at %.2f from %.2f".fmt(lockMin, lockFrom)
                // 0.5.2: never for a strength lock whose ratio has not closed to the onset: a hand
                // resting half closed is not a press.
                !lockByStrength && nowMs - armed.sinceMs >= lock.armTimeoutMs && near && ratio <= minOf(lock.abortRatio, pinch.enterRatio + lock.stableMargin) ->
                    "held %d ms at %.2f".fmt(nowMs - armed.sinceMs, lockMin)
                else -> ""
            }
            if (how.isNotEmpty()) {
                val why = gate(needHold = how.startsWith("ratio"))
                if (why != null) {
                    refuse(why)
                    how = ""
                } else {
                    pinch.press(minOf(lockMin, ratio))
                    isPinched = true
                }
            }
        }

        if (isPinched) pendingHold = null
        // 0.5.46: the palm toward the glasses, without a break, for the launcher's pinch.
        if (palmToward(hand)) {
            if (palmTowardSinceMs == null) palmTowardSinceMs = nowMs
        } else if (!isPinched) {
            palmTowardSinceMs = null
        }
        palmMenuReady = palmPinchOpensLauncher && hand.rightHand != null && palmTowardSinceMs?.let { nowMs - it >= PALM_MENU_MS } == true
        val pose = HandPoseClassifier.classify(hand, isPinched)
        val world = hand.world != null

        var shown: Point
        var shownMs = nowMs
        when {
            !wasPinched && isPinched -> {
                if (held == null) {
                    traces += PinchTrace.ClosingOnset(smooth, ratio, recentMax)
                    lockAim(nowMs, smooth, ratio, recentMax, signals, traces)
                }
                val l = held!!
                pressOrigin = smooth
                pressMs = nowMs
                dragOffset = null
                heldHand = smooth
                dragWanted = false
                // 0.5.46: the palm turned to the glasses: the launcher, not a press (Meta Quest's menu pinch).
                menuPinch = palmMenuReady
                menuPinchMs = nowMs
                menuRecentred = false
                if (palmPinchOpensLauncher) traces += PinchTrace.PalmRead(PalmFacing.turn(hand), hand.rightHand, palmToward(hand), menuPinch, palmSideFlipped)
                // 0.5.4: the user's hand, as the hand traces name it, not MediaPipe's mirrored label.
                // 0.5.50: a menu pinch sends nothing yet: the launcher on its release, a recentre if it is held.
                if (!menuPinch) signals += PointerSignal.Down(l.point, l.pointMs, pinch = true, rightHand = userRight(hand))
                if (!menuPinch) traces += PinchTrace.Press(l.point, smooth, ratio, nowMs - l.sinceMs, world, how, pinch.enterRatio, pinch.exitRatio, lastOpenMs?.let { nowMs - it }, detail())
                shown = l.point
                shownMs = l.pointMs
            }
            wasPinched && !isPinched -> {
                val l = held ?: Lock(smooth, nowMs, nowMs)
                val heldMs = nowMs - pressMs
                // 0.5.5: the drift from the lock, as the trace logs it; the frame before the
                // fingers opened counts too, so the release's own pull does not spoil a tap.
                val drift = minOf(smooth.distanceTo(l.point), history.lastOrNull()?.hand?.distanceTo(l.point) ?: Float.MAX_VALUE)
                val origin = pressOrigin
                val before = heldHand
                var flickEnd: Point? = null
                // 0.5.5: no drag starts within the tap time, so a pinch whose drag rules fired
                // then (a sweep, Mike's pinches 10 and 14) and whose hand ended past the tap drift
                // ([PinchTap]) becomes its drag here, in one move to where the hand was on the
                // last held frame (the release's own pull does not count). Under the drift it is a tap.
                if (dragOffset == null && dragWanted && origin != null && before != null && drift >= PinchTap.driftLimitPx(hand.handSize, lock)) {
                    dragOffset = l.point - origin
                    val end = before + dragOffset!!
                    signals += PointerSignal.MoveTo(end.x, end.y, nowMs)
                    traces += PinchTrace.DragUnlock(l.point, before, before.distanceTo(origin), heldMs)
                    flickEnd = end
                }
                val dragged = dragOffset != null
                val (at, atMs) = if (flickEnd != null) {
                    flickEnd to nowMs
                } else if (dragged) {
                    openingRewind.onset(history.toList() + AimSample(nowMs, lastCursor ?: smooth, ratio), nowMs, closing = false).let { it.point to it.pointMs }
                } else {
                    l.point to l.pointMs
                }
                // 0.5.46: a menu pinch sent no press, so it sends no release. 0.5.50: a short one opens the launcher now.
                if (!menuPinch) signals += PointerSignal.Up(at, atMs) else if (!menuRecentred) signals += PointerSignal.ShowDock
                menuPinch = false
                menuRecentred = false
                traces += PinchTrace.Release(at, l.point, smooth, ratio, heldMs, dragged, releasedBy)
                calibration?.noteClosed(minOf(lockMin, pinch.tightest))
                clearPress()
                // 0.5.17: the next press needs its own closing after this release, and a reopen.
                lastReleaseMs = nowMs
                if (lock.proofAfterRelease) lastCloseMs = null
                glideFrom = at
                shown = at
                shownMs = atMs
            }
            isPinched -> {
                // 0.5.50: a menu pinch held long enough recentres, once.
                if (menuPinch && !menuRecentred && nowMs - menuPinchMs >= PALM_RECENTRE_MS) {
                    menuRecentred = true
                    signals += PointerSignal.Recentre
                }
                val l = held ?: Lock(smooth, nowMs, nowMs).also { held = it }
                // 0.5.4: while the fingers are still closing after the press, the hand's motion is
                // the pinch settling, not a drag: the drag is measured from where it settled.
                // Mike's 0.5.3 taps drifted up to 161 px between the lock and the release. A hand
                // sweeping faster than a pinch pulls it (0.3.5's pinches 10 and 14) still drags.
                val settling = dragOffset == null && speed < minOf(lock.fastMovePxPerS, lock.settleBelowPxPerS) &&
                    history.lastOrNull()?.let { ratio < it.ratio - lock.stableStep } == true
                if (settling) pressOrigin = smooth
                if (!opening) heldHand = smooth
                val origin = pressOrigin ?: smooth.also { pressOrigin = it }
                val heldMs = nowMs - pressMs
                if (dragOffset == null && !opening) {
                    val moved = smooth.distanceTo(origin)
                    val ppd = frustum.pixelsPerDegree
                    val beyond = moved > lock.dragStartDeg * ppd
                    val sweeping = beyond && moved * 1000f / heldMs.coerceAtLeast(1L) > lock.dragFastDegPerS * ppd
                    val wants = (beyond && heldMs >= lock.dragMinMs) || sweeping || moved > lock.dragFarDeg * ppd
                    // 0.5.46: a flick, further than any tap drifts, drags at once ([PinchLockSettings.flickStartDeg]).
                    val flick = lock.flickStartDeg?.let { moved > it * ppd } == true
                    val allowed = PinchTap.dragAllowed(heldMs, lock) || flick
                    // 0.5.5: never a drag while it could still be a tap ([PinchTap]); the release
                    // decides. A hand sweeping faster than a pinch pulls it counts too.
                    if ((wants || speed > lock.fastMovePxPerS) && !allowed) dragWanted = true
                    if ((wants || flick) && allowed) {
                        // The drag keeps the hand's whole travel, but the cursor glides there from the
                        // lock; a sweep (0.5.0) goes straight there, it is already late.
                        dragOffset = l.point - origin
                        glideFrom = if (sweeping) null else l.point
                        traces += PinchTrace.DragUnlock(l.point, smooth, moved, heldMs)
                    }
                }
                val offset = dragOffset
                if (offset == null) {
                    shown = l.point
                    shownMs = l.pointMs
                } else {
                    val (p, atMs) = predicted(smooth + offset, nowMs, latencyMs)
                    val q = glided(p, nowMs)
                    signals += PointerSignal.MoveTo(q.x, q.y, atMs)
                    shown = q
                    shownMs = atMs
                }
            }
            else -> {
                val l = held
                val strengthFloor = (lock.strengthLock ?: 0f) - lock.strengthRearm
                val reason = when {
                    l == null -> null
                    notReady != null && nowMs - (notReadySinceMs ?: nowMs) >= lock.lockLostMs -> "not ready: $notReady"
                    notReady != null -> null
                    // 0.5.2: a strength lock lets go when the strength falls back, not on the ratio's 0.52.
                    lockByStrength && strengthNow < strengthFloor -> "fingers reopened (strength %.2f)".fmt(strengthNow)
                    !lockByStrength && (ratio > lock.abortRatio || ratio >= lockMin + lock.reopenRise) -> "fingers reopened"
                    // 0.5.54: a half closed hand that moves on is aiming, so the cursor follows it again.
                    lockByStrength && lock.strengthLockMoveDeg != null && nowMs - l.sinceMs >= lock.strengthLockMoveAfterMs &&
                        smooth.distanceTo(l.point) > lock.strengthLockMoveDeg * frustum.pixelsPerDegree ->
                        "the hand moved on half closed (%.0f px)".fmt(smooth.distanceTo(l.point))
                    lockFromStrength && nowMs - l.sinceMs >= lock.strengthLockTimeoutMs -> "fingers stayed half closed"
                    !lockFromStrength && nowMs - l.sinceMs >= lock.armTimeoutMs -> "fingers stayed half open"
                    else -> null
                }
                if (l != null && reason == null) {
                    shown = l.point
                    shownMs = l.pointMs
                } else {
                    if (l != null) {
                        traces += PinchTrace.ClosingAborted(l.point, smooth, ratio, nowMs - l.sinceMs, reason!!)
                        clearPress()
                        glideFrom = l.point
                    }
                    // No prediction while the fingers close (0.5.2: or the strength is building).
                    val (p, atMs) = predicted(smooth, nowMs, if (closing || strengthNow >= PREDICT_OFF_STRENGTH) 0f else latencyMs)
                    val q = glided(p, nowMs)
                    signals += PointerSignal.MoveTo(q.x, q.y, atMs)
                    shown = q
                    shownMs = atMs
                }
            }
        }
        lastCursor = shown
        coastFrom = if (!isPinched && held == null) shown else null
        history.addLast(AimSample(nowMs, shown, ratio, shownMs, smooth, raw, broken = sizeJumped))
        while (history.size > 2 && history.first().timeMs < nowMs - HISTORY_KEEP_MS) history.removeFirst()

        // 0.5.46: with the palm pinch on (Meta Quest's), the held open palm no longer opens the launcher too: a palm
        // held up while the pinch comes would open it and the pinch close it again.
        if (!palmPinchOpensLauncher && pose == HandPose.OPEN_PALM && (!palmMustFaceCamera || PalmFacing.towardCamera(hand))) {
            val since = palmSinceMs ?: nowMs.also { palmSinceMs = it }
            if (!palmFired && nowMs - since >= palmHoldMs) {
                palmFired = true
                signals += PointerSignal.ShowDock
            }
        } else {
            palmSinceMs = null
            palmFired = false
        }

        val strength = when {
            isPinched -> 1f
            notReady != null -> 0f
            calibration != null -> calibration.value(ratio)
            else -> strength(ratio)
        }
        val locked = held != null && dragOffset == null
        val hold = if (menuPinch && isPinched && !menuRecentred) ((nowMs - menuPinchMs).toFloat() / PALM_RECENTRE_MS).coerceIn(0f, 1f) else 0f
        val status = HandStatus(true, pose, isPinched, strength, shown, ratio, locked, side, notReady == null, built, palmMenu = palmMenuReady && !isPinched, palmHold = hold)
        return HandUpdate(signals, status, traces)
    }

    /**
     * Locks the aim where the steady cursor (filtered and dead zoned, without prediction or
     * glide) was [PinchLockSettings.selectBackMs] before this frame, and moves the cursor there.
     *
     * 0.5.4: a short fixed look back on the filtered ray, Quest's select point, replaces 0.3.5's
     * rewind, which walked back along the shown cursor while the ratio fell and went at least
     * 100 ms back: on Mike's 17 to 21 fps it rewound 33 to 167 ms, onto cursor positions pushed
     * up to 122 ms ahead by the prediction, so taps landed 41 to 98 px from where he aimed.
     */
    private fun lockAim(
        nowMs: Long,
        smooth: Point,
        ratio: Float,
        from: Float,
        signals: MutableList<PointerSignal>,
        traces: MutableList<PinchTrace>,
    ) {
        val (before, capped) = PinchSelect.lookBack(history, nowMs, lock.selectBackMs, lock.historyMs, smooth, lock.selectCapPx)
        val at = before?.hand ?: smooth
        val atMs = before?.timeMs ?: nowMs
        held = Lock(at, atMs, nowMs)
        lockFrom = from
        lockMin = ratio
        sinceLow = 0
        glideFrom = null
        glide = null
        signals += PointerSignal.MoveTo(at.x, at.y, atMs, held = true)
        traces += PinchTrace.Locked(at, smooth, nowMs - atMs, capped)
    }

    /** 0.5.11: display pixels per degree of aim, for speeds in degrees a second. */
    private fun displayPixelsPerDegree(): Float = (frustum.pixelsPerDegree * displayWidth / frustum.widthPx).coerceAtLeast(1e-3f)

    /** The smoothed aim's velocity in display pixels a second, from whichever filter smooths it (0.5.9). */
    private fun velocityPx(): Pair<Float, Float> {
        val f = rayFilter
        if (f == null || !angleSmoothed) return filterX.velocity to filterY.velocity
        val (yaw, pitch) = f.velocityDegPerS
        val ppd = frustum.pixelsPerDegree * displayWidth / frustum.widthPx
        return yaw * ppd to -pitch * ppd
    }

    /** 0.5.30: the velocity the prediction pushes along ([RayAimFilter.leadVelocityDegPerS] in ray mode), display px a second. */
    private fun leadVelocityPx(): Pair<Float, Float> {
        val f = rayFilter
        if (f == null || !angleSmoothed || f.settings.finalMinCutoffHz > 0f) return velocityPx()
        val (yaw, pitch) = f.leadVelocityDegPerS
        val ppd = frustum.pixelsPerDegree * displayWidth / frustum.widthPx
        return yaw * ppd to -pitch * ppd
    }

    /** 0.5.4: [filtered] through the soft dead zone, from the last steady point. */
    private fun steadied(filtered: Point): Point {
        val prev = steady ?: return filtered.also { steady = it }
        val step = filtered - prev
        val w = filter.deadZoneWeight(kotlin.math.hypot(step.x, step.y))
        return Point(prev.x + step.x * w, prev.y + step.y * w).also { steady = it }
    }

    /** 0.5.4: true for the user's right hand: this recognizer's side, else MediaPipe's label turned round. */
    private fun userRight(hand: HandFrame): Boolean? =
        side?.let { it == HandSide.RIGHT } ?: hand.rightHand?.let { HandsRecognizer.userSide(it, airBox.mirror) == HandSide.RIGHT }

    /**
     * The display pixel a head frame ray direction points through, on [frustum]; a direction
     * at or behind the side of the view is pushed just in front of it so it still maps.
     */
    private fun rayPixel(dir: Vec3): Point {
        val d = if (dir.z > -0.05f) Vec3(dir.x, dir.y, -0.05f).normalized() else dir
        val (x, y) = frustum.pixelOf(d) ?: (frustum.widthPx / 2f to frustum.heightPx / 2f)
        // In the frustum's pixels, scaled to the display the recognizer reports in.
        return Point(x * displayWidth / frustum.widthPx, y * displayHeight / frustum.heightPx)
    }

    /**
     * How fast the hand moved over the last ~100 ms, in display pixels a second. 0.5.2: on the
     * unsmoothed aim, since the pinch damping slows the smoothed one while the fingers close.
     */
    private fun handSpeed(raw: Point, nowMs: Long): Float {
        val then = history.firstOrNull { it.timeMs >= nowMs - SPEED_WINDOW_MS && it.timeMs < nowMs } ?: return 0f
        val dt = (nowMs - then.timeMs).coerceAtLeast(1L)
        return raw.distanceTo(then.raw) * 1000f / dt
    }

    /** [p] plus what is left of the offset from where the last lock ended, fading over glideMs. */
    private fun glided(p: Point, nowMs: Long): Point {
        glideFrom?.let {
            glide = it - p
            glideMs = nowMs
            glideFrom = null
            return it
        }
        val g = glide ?: return p
        val k = if (lock.glideMs <= 0f) 0f else kotlin.math.exp(-(nowMs - glideMs) / lock.glideMs)
        glideMs = nowMs
        val left = Point(g.x * k, g.y * k)
        glide = if (kotlin.math.hypot(left.x, left.y) < 0.5f) null else left
        return p + left
    }

    /** 0 with the fingers apart (at or above the release ratio), 1 at the pinch ratio. */
    private fun strength(ratio: Float): Float =
        ((pinch.exitRatio - ratio) / (pinch.exitRatio - pinch.enterRatio)).coerceIn(0f, 1f)

    /**
     * No hand at [nowMs]. An armed lock is dropped once the hand has been gone for
     * [PinchLockSettings.lockLostMs]; after [lostAfterMs] everything resets, and a press still
     * held ends as a click at its lock (a drag is cancelled), so no lock is left hanging.
     */
    private fun missing(nowMs: Long): HandUpdate {
        if (!present) return HandUpdate(emptyList(), HandStatus(false, cursor = lastCursor))
        val gone = nowMs - lastSeenMs
        val traces = mutableListOf<PinchTrace>()
        val l = held
        if (l != null && !pinch.pinched && gone >= lock.lockLostMs) {
            traces += PinchTrace.HandLost(l.point, nowMs - l.sinceMs, "lock dropped without a press")
            clearPress()
            glideFrom = l.point
        }
        if (gone < lostAfterMs) {
            missedFrames++
            val coast = coast(gone)
            val locked = held != null && dragOffset == null
            return HandUpdate(listOfNotNull(coast), HandStatus(present, cursor = lastCursor, pinched = pinch.pinched, locked = locked, nearBottom = lowInPicture), traces)
        }
        val signals = mutableListOf<PointerSignal>()
        if (pinch.pinched) {
            val at = held
            if (at != null && dragOffset == null) {
                signals += PointerSignal.Up(at.point, at.pointMs)
                traces += PinchTrace.HandLost(at.point, nowMs - at.sinceMs, "press released at the lock")
            } else {
                signals += PointerSignal.Cancel
                traces += PinchTrace.HandLost(at?.point ?: lastCursor ?: Point(0f, 0f), nowMs - pressMs, "drag cancelled")
            }
        }
        reset()
        return HandUpdate(signals, HandStatus(false, cursor = lastCursor), traces)
    }

    /**
     * 0.5.7: a free cursor moving faster than [PointerFilterSettings.coastMinSpeed] keeps going
     * along the filter's velocity for [PointerFilterSettings.coastMs] of a gap, slowing to a stop,
     * so a hand that drops out of a frame or two mid sweep does not stall the cursor.
     */
    private fun coast(goneMs: Long): PointerSignal? {
        val from = coastFrom ?: return null
        if (pinch.pinched || held != null || goneMs > filter.coastMs) return null
        val (vx, vy) = velocityPx()
        if (kotlin.math.hypot(vx, vy) < filter.coastMinSpeed) return null
        val p = Point(
            (from.x + filter.coast(vx, goneMs.toFloat())).coerceIn(0f, (displayWidth - 1).toFloat()),
            (from.y + filter.coast(vy, goneMs.toFloat())).coerceIn(0f, (displayHeight - 1).toFloat()),
        )
        lastCursor = p
        coasted = true
        return PointerSignal.MoveTo(p.x, p.y, lastSeenMs + goneMs)
    }

    private fun clearPress() {
        held = null
        pressOrigin = null
        dragOffset = null
        heldHand = null
        dragWanted = false
        lockMin = Float.MAX_VALUE
        sinceLow = 0
        lockByStrength = false
        lockFromStrength = false
        lockStrengthMax = 0f
    }

    fun reset() {
        palmTowardSinceMs = null
        menuPinch = false
        menuRecentred = false
        palmMenuReady = false
        pinch.reset()
        filterX.reset()
        filterY.reset()
        rayFilter?.reset()
        steadyRoom = null
        angleSmoothed = false
        steady = null
        badStreak = 0
        skippedSize = null
        lastRaw = null
        history.clear()
        palmSinceMs = null
        palmFired = false
        clearPress()
        glideFrom = null
        glide = null
        present = false
        lowInPicture = false
        openAboveBand = false
        openRunStartMs = null
        lastOpenRunMs = 0L
        coastFrom = null
        coasted = false
        missedFrames = 0
        onsetIgnored = false
        notReadySinceMs = null
        notReadyLogged = false
        lastHandSize = null
        imageLow = Float.MAX_VALUE
        imageOpen = 0
        belowSinceMs = null
        lastCloseMs = null
        lastOpenMs = null
        refusedKey = null
        pendingHold = null
        pendingHoldDetail = null
        framesBelow = 0
        prevFrameMs = Long.MIN_VALUE
        roomAims.clear()
        lastReleaseMs = null
        movingHeld = false
        bounceHeld = false
        lastBadMs = null
        openAboveBand = false
        openRunStartMs = null
        lastOpenRunMs = 0L
        strengthArmed = false
        strengthLowSinceMs = null
        ray?.reset()
    }

    /**
     * [smooth] pushed ahead along the hand's velocity, kept on the display, and the time the
     * pushed point stands for (capture time plus the lead).
     */
    private fun predicted(smooth: Point, nowMs: Long, latencyMs: Float?): Pair<Point, Long> {
        val (vx, vy) = leadVelocityPx()
        val seconds = filter.leadSeconds(kotlin.math.hypot(vx, vy), latencyMs ?: filter.predictionMs, ray = angleSmoothed)
        val p = Point(
            (smooth.x + vx * seconds).coerceIn(0f, (displayWidth - 1).toFloat()),
            (smooth.y + vy * seconds).coerceIn(0f, (displayHeight - 1).toFloat()),
        )
        return p to nowMs + (seconds * 1000f).toLong()
    }

    private companion object {
        /** 0.5.46: how long the palm must face the glasses before a pinch opens the launcher. */
        const val PALM_MENU_MS = 200L

        /** 0.5.50: how long a palm pinch is held to recentre instead (a deliberate hold, as on Meta Quest). */
        const val PALM_RECENTRE_MS = 650L

        /** Long enough for the closing lock (250 ms) and the release rewind (150 ms). */
        const val HISTORY_KEEP_MS = 500L
        const val SPEED_WINDOW_MS = 100L

        /** 0.5.30: a hand found again this soon after the last one glides in from the cursor shown. */
        const val FOUND_GLIDE_MS = 1000L

        /** 0.5.4: an aim leap counts only against a frame this recent (else the hand was gone a while). */
        const val LEAP_WINDOW_MS = 120L
        const val MIN_FRAME_MS = 33L

        /** 0.5.4: frames in a row the picture must show the tips apart before it releases. */
        const val IMAGE_RELEASE_FRAMES = 2

        /** 0.5.2: from this pinch strength on, the cursor is not pushed ahead along the hand's speed. */
        const val PREDICT_OFF_STRENGTH = 0.2f

        /** The Space's default diagonal field of view (SpaceStore), about 30 px per degree on 1920x1080. */
        const val DEFAULT_FOV_DEG = 70f
    }
}

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)
