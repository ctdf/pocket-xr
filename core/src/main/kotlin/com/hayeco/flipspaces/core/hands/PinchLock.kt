package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import java.util.Locale

/**
 * How a pinch holds the cursor still (0.3.5) and becomes a click (0.3.6). Mike's 0.3.3 log
 * showed quick pinches (130 to 200 ms) where most of the aim drift happens while the fingers
 * close, before the press fires, and a 20 px wobble that the 36 px freeze let through. His 0.3.5
 * log showed natural pinches that bottom out at a ratio of 0.32 to 0.38, which never reached
 * the old 0.25 press, and rewinds of 290 to 660 px across fast hand moves. So:
 *
 * - Closing onset: the thumb to index ratio is below [onsetRatio] and has fallen by at least
 *   [onsetDrop] within [closingWindowMs], and the hand is not moving faster than
 *   [fastMovePxPerS]. The aim locks there, on the steady cursor (filtered, dead zoned, not
 *   predicted) [selectBackMs] before, but never more than [selectCapPx] from it (0.5.4; before
 *   that a rewind of up to [historyMs] along the shown cursor).
 * - While the gap is closing (fallen [dampDrop] within [closingWindowMs], below
 *   [dampBelowRatio]) the filter smooths harder by [dampFactor] and prediction is off.
 * - A lock presses as soon as the ratio is at the press threshold (one frame is enough while
 *   locked; [adaptive] learns the threshold per hand, see [PinchCalibration]), or when the
 *   ratio stops closing: no new low for [stableFrames] frames (by more than [stableStep]) after
 *   falling at least [minPressDrop] from where it started, or when [armTimeoutMs] passes with
 *   the fingers still closed.
 * - A lock that never pressed ends only when the fingers reopen: the ratio rises above
 *   [abortRatio] or [reopenRise] above its lowest point. A lock also ends when the hand is lost
 *   for [lockLostMs].
 * - While pinched the cursor does not move at all until a deliberate drag: the hand has moved
 *   [dragStartDeg] from where it was at the press and the pinch has lasted [dragMinMs], or it
 *   has moved [dragFarDeg] at once, or past [dragStartDeg] at [dragFastDegPerS] (a sweep). Never
 *   while the fingers are opening. 0.5.0: in degrees of
 *   the ray, Quest's slop rather than 0.4.8's 48 px; Mike's 0.4.8 taps moved 54 to 131 px
 *   (about 1.8 to 4.3 degrees through the air box) after 230 ms and all became drags.
 * - 0.5.0: the release also comes from the picture: once the thumb and index tips are
 *   [imageReleaseRise] (of the hand's size in the picture) further apart than at the pinch's
 *   tightest, the pinch ends, whatever the world landmarks, which lag when tracking slows.
 * - The release goes to the locked spot. Afterwards the cursor glides from the locked spot back
 *   onto the hand with time constant [glideMs] instead of jumping.
 */
data class PinchLockSettings(
    val onsetRatio: Float = 0.45f,
    val onsetDrop: Float = 0.08f,
    val closingWindowMs: Long = 100,
    val dampDrop: Float = 0.04f,
    val dampBelowRatio: Float = 0.8f,
    val dampFactor: Float = 0.3f,
    val historyMs: Long = 250,
    val abortRatio: Float = 0.52f,
    val armTimeoutMs: Long = 400,
    /**
     * 0.5.11: 2 degrees (was 3) of the hand from where the pinch settled before a drag or scroll
     * starts: the small dead zone on the hand (not the head) Mike asked for, still above a tap's
     * drift (the tap rule keeps short pinches taps whatever they drift).
     */
    val dragStartDeg: Float = 2f,
    val dragMinMs: Long = 250,
    val dragFarDeg: Float = 6f,
    /** 0.5.0: past [dragStartDeg] this fast (since the press) is a drag at once, a sweep, not a tap. */
    val dragFastDegPerS: Float = 25f,
    val glideMs: Float = 90f,
    val adaptive: Boolean = true,
    val stableFrames: Int = 2,
    val stableStep: Float = 0.015f,
    val minPressDrop: Float = 0.08f,
    val reopenRise: Float = 0.1f,
    /** 0.5.4: the select point is never further than this from the steady cursor at the lock ([PinchSelect]). */
    val selectCapPx: Float = 50f,
    val fastMovePxPerS: Float = 1200f,
    /**
     * 0.5.8: 250 ms (was 100). With two hands tracked at 15 fps a frame is 66 ms, so 100 ms was a
     * single missed frame: Mike's 0.5.6 log had locks "dropped without a press" by one gap. A lock
     * and a press now hold, the cursor on its last pose, through any gap under this; a press still
     * held ends at the recognizer's lostAfterMs (450 ms, 0.5.7) only if the hand stays lost.
     */
    val lockLostMs: Long = 250,
    val imageReleaseRise: Float? = 0.25f,
    /**
     * 0.5.2, Meta and Android XR: the cursor freezes as soon as the pinch strength (0 open, 1 at
     * the press) reaches this, rising, not only once the ratio has dropped fast enough for the
     * closing onset. Null turns it off. It arms again once the strength falls [strengthRearm]
     * below it, so a hand resting half closed does not freeze the cursor over and over.
     */
    val strengthLock: Float? = 0.4f,
    val strengthRearm: Float = 0.15f,
    /** 0.5.2: how long the strength must stay below the re-arm level before the strength lock arms again. */
    val strengthRearmMs: Long = 100,
    /** 0.5.2: a strength lock that never pressed lets go after this long. */
    val strengthLockTimeoutMs: Long = 900,
    /**
     * 0.5.54 (Mike, 10/28: hand tracking "freezes for a second or two" at random moments; 19 of the 22 strength locks on
     * 10-27 that ran into [strengthLockTimeoutMs] held the cursor 0.9 s while the hand moved 60 to 641 px away): a strength
     * lock whose fingers have not closed to the onset lets go once it has held [strengthLockMoveAfterMs] and the hand is
     * more than this many degrees from the lock. A hand moving on half closed is aiming, not pinching. Null is 0.5.53.
     */
    val strengthLockMoveDeg: Float? = 2.5f,
    val strengthLockMoveAfterMs: Long = 250,
    /**
     * The press lands where the steady cursor was this long before the lock (Quest's select
     * point). 0.5.4: a fixed 66 ms, two frames at 30 fps: the strength lock fires about 40% of
     * the way into a 150 ms pinch, so this is about when the fingers started to close. 0.5.2 had
     * at least 100 ms and up to 250 ms, on the predicted cursor.
     */
    val selectBackMs: Long = 66,
    /**
     * 0.5.4: an aim that moves faster than this between two frames (10 degrees in one 33 ms
     * frame, faster than a hand aims) is a bad frame and skipped once; the next frame decides.
     */
    val leapDegPerS: Float = 300f,
    /**
     * 0.5.5: a pinch released within this long whose hand drift from the lock is under
     * [tapDriftPx] (scaled to the hand's distance, [PinchTap]) is a tap, never a drag, and no
     * drag starts before it. Mike's 0.5.4 log: a 131 ms pinch with 26 px of drift became a drag.
     */
    val tapMaxMs: Long = 250,
    val tapDriftPx: Float = 40f,
    /** The hand size in the picture ([HandFrame.handSize]) at which [tapDriftPx] applies as is; a closer, bigger hand gets more. */
    val tapHandSize: Float = 0.10f,
    val tapDriftMaxScale: Float = 2f,
    /**
     * 0.5.11, false pinches (Mike: "it often thinks I am pinch selecting when I am not"). Every
     * press, whichever rule makes it, now passes [PressGate]. Common practice for camera hand
     * tracking (headset pinch guidance, MediaPipe gesture demos): a distance
     * normalised by hand size with separate press and release thresholds, a minimum time under
     * the press threshold, no press while the hand moves fast, no press from an unsure or half
     * seen hand, and a press only after a real closing movement.
     *
     * [pressHoldMs]: the ratio must stay at or below the press threshold this long (time, not
     * frames, so it holds at 15 and at 30 fps): one noisy frame never clicks.
     */
    val pressHoldMs: Long = 30,
    /**
     * A press needs a real closing: within [closeProofKeepMs] before it (a pinch presses 100 to 450
     * ms after its fingers start to close) the ratio fell at least
     * [closeProofDrop] inside [closeProofWindowMs]. A deliberate pinch closes at 0.3 to 1 ratio a
     * second; a relaxed hand curling or drifting moves well under 0.2 a second.
     */
    val closeProofDrop: Float = 0.09f,
    val closeProofWindowMs: Long = 500,
    val closeProofKeepMs: Long = 600,
    /**
     * 0.5.14: a press also counts as a real closing when the fingers were
     * open (at or above the release ratio, on a frame MediaPipe was sure of) within this long: a
     * fingertip distance crossed from open, kept from a resting curled hand by needing the open state first. 17 of Mike's 65 pinch starts
     * were refused for "no real closing", most where the closing frames had a tip at the picture's
     * edge and so did not count. 0 turns it off.
     */
    val openProofMs: Long = 1200,
    /**
     * Velocity gate: while the hand's aim moves faster than [pressMaxDegPerS] a press needs the
     * fingers clearly touching (ratio at or below [movingPressRatio]), so an open hand sweeping
     * past, whose ratio dips for a frame, never clicks, while Mike's deliberate pinches during a
     * sweep (0.3.5's pinches 10 and 14, which bottom at 0.15 and 0.19) still become drags.
     */
    val pressMaxDegPerS: Float = 40f,
    val movingPressRatio: Float = 0.22f,
    /**
     * The lock's "stable" and "held" press rules press a pinch that stops short of the press
     * threshold, but now only within this much above it (0.5.10 allowed up to 0.45 and 0.52,
     * where a relaxed half curled hand rests).
     */
    val stableMargin: Float = 0.02f,
    /**
     * 0.5.16 (10 of 26 pinches refused against 5 of 27): the hold is also met
     * by this many frames in a row at or below the press threshold, whatever their timestamps say.
     * At 30 fps two frames are 33 ms apart only on average: the camera frames are stamped when they
     * arrive over USB, and under load (the GPU contended, the phone warm) two in a row can be 25 ms
     * apart, so the second frame of a real pinch failed the 30 ms hold, and a pinch whose ratio then
     * bounced back over the threshold was refused. One noisy frame still never clicks. 0 is 0.5.15.
     */
    val pressHoldFrames: Int = 2,
    /**
     * 0.5.16: the velocity gate judges the aim in the room, not on the display. The ray is built in
     * the head's frame, so a head turn with the hand held still moved the aim on the display as fast
     * as the head turned (Mike's glances reach 150 deg/s) and the press was refused as "hand moving".
     * With the head pose known, the speed is that of the ray in the room; while the head itself
     * turns faster than [pressHeadBlurDegPerS] (the camera's picture smears) the display figure
     * still counts too. False is 0.5.15.
     */
    val pressSpeedInRoom: Boolean = true,
    val pressHeadBlurDegPerS: Float = 120f,
    /**
     * 0.5.17 (9 of the 13 false presses). The fast release
     * ends a pinch inside the hysteresis band (opening fast at 0.41 to 0.47 against a release
     * threshold of 0.47 to 0.52), so the hand is still half closed and MediaPipe's wobble pressed
     * again. For this long after a release a press needs the fingers to have reopened: the ratio
     * at or above the release threshold at or after the release. 0 turns it off (0.5.16).
     */
    val rearmAfterReleaseMs: Long = 1000,
    /**
     * 0.5.17 (pattern B, press 54): no press sooner than this after the same hand's release.
     * 128 ms from release to press is faster than a person pinches twice; no real press in the
     * log came sooner than 399 ms. 0 turns it off.
     */
    val minReleaseGapMs: Long = 200,
    /**
     * 0.5.17: at a release the closing proof is cleared, and the proof (a fall of [closeProofDrop],
     * or the fingers open) counts only from frames after the release, so the previous pinch's own
     * closing cannot vouch for the next one. False is 0.5.16.
     */
    val proofAfterRelease: Boolean = true,
    /**
     * 0.5.17 (pattern C, presses 3 and 31): a "hand moving" refusal holds for the rest of that
     * closing, until the ratio rises this far above the press threshold: only fingers clearly
     * touching ([movingPressRatio]) may still press in it. Presses 3 and 31 came at 0.37 and 0.30
     * a frame or two after the refusal as the hand slowed dropping out of view; 0.3.5's pinch 14,
     * a real pinch right after a 650 px jump, presses at 0.19 once the hand has stopped. Null is
     * 0.5.16 (the next frame was judged fresh).
     */
    val movingHoldRise: Float? = 0.08f,
    /**
     * 0.5.17 (pattern C, presses 19 and 32): no press within this long of a frame of this hand
     * that was not ready (a tip at the picture's edge, a size jump, an unsure score) or was
     * skipped as bad. 0 turns it off.
     *
     * 0.5.18: 250 ms (was 150). The two false presses as Mike opened
     * the phone came 166 and 167 ms after a bad frame.
     */
    val badFrameQuietMs: Long = 250,
    /**
     * 0.5.18 (the last three false presses, one of which closed a window):
     * no press from a hand with a pinch finger or the wrist in the bottom [lowBandShare] of the
     * camera picture (where the phone is held and looked at) unless that hand was seen open above
     * the band first, or held open for [lowBandOpenMs] in it (0.5.6's low hand that pinches on
     * purpose, LowHandPinchTest, opens for a quarter second; the fingers holding the phone flick
     * open for a frame or three). 0 turns it off.
     */
    val lowBandShare: Float = 0.12f,
    val lowBandOpenMs: Long = 250,
    /**
     * 0.5.45 (Mike, vc65: pinching the three buttons under a window is "a bit annoying"; his log: three of those
     * pinches refused as "low in the camera"): aiming low puts the wrist in the bottom of the picture well before
     * the fingers (his were at about 0.80 of the picture, the wrist in the band), so the band now looks at the pinch
     * fingers only (true is 0.5.18 to 0.5.44's fingers or wrist). A hand curled round the phone has its fingers in the
     * band too (FalsePress0518Test); [PhoneHandling] also catches folds, opens and touches.
     */
    val lowBandWrist: Boolean = false,
    /**
     * 0.5.45 (Mike, vc65: hand scrolling "doesn't work well at all"; his flick's drag counted 87 px of a 300 px move):
     * while the fingers still close after the press the drag's origin follows the hand (the pinch settling, not a
     * drag), up to 0.5.44 at any speed under [fastMovePxPerS], 1200 px/s; a flick starts as the fingers close, so
     * its first third went into the settling. Now only below this speed (px/s, about 15 deg/s).
     */
    val settleBelowPxPerS: Float = 450f,
    /**
     * 0.5.46 (Mike, vc66: "flick-scroll is still bad"; his flicks' drags unlocked 265 ms in, after the hand had moved 16
     * degrees, so the page sat still, jumped, and was let go 100 ms later): a pinch whose hand has moved this far (degrees)
     * drags at once, inside the tap time too. A tap drifts at most [tapDriftPx] scaled up to [tapDriftMaxScale], about
     * 2.6 degrees. Null keeps 0.5.5 to 0.5.45's rule (no drag inside the tap time).
     */
    val flickStartDeg: Float? = 5f,
    /**
     * 0.5.17 (press 19): above this aim speed even fingers clearly touching ([movingPressRatio])
     * do not press. Mike's real pinches during a sweep (0.3.5's pinch 10) move about 65 deg/s.
     */
    val movingAlwaysDegPerS: Float = 80f,
    /**
     * 0.5.17 (press 32, after "hand size jumped 69 %" frames and a closing "from 1.34"): a frame
     * whose hand size jumped from the last one kept is no proof of an open hand or of a closing.
     * False is 0.5.16.
     */
    val proofSkipsBrokenFrames: Boolean = true,
) {
    init {
        require(onsetRatio < abortRatio) { "Abort ratio must be above the onset ratio" }
        require(dampFactor > 0f && dampFactor <= 1f) { "Damp factor is between 0 and 1" }
        require(dragStartDeg <= dragFarDeg) { "Drag start must not be beyond the far drag" }
        require(historyMs > 0 && closingWindowMs > 0 && glideMs >= 0f) { "Times must be positive" }
        require(stableFrames >= 1 && selectCapPx >= 0f && fastMovePxPerS > 0f && leapDegPerS > 0f) { "Need a frame, a cap and speeds" }
        require(strengthLock == null || strengthLock in 0.05f..1f) { "The strength lock is a pinch strength, 0.05 to 1" }
        require(selectBackMs in 0..historyMs) { "The select point must be within the kept history" }
        require(tapMaxMs >= 0 && tapDriftPx >= 0f && tapHandSize > 0f && tapDriftMaxScale >= 1f) { "Tap limits must be positive" }
        require(pressHoldMs >= 0 && closeProofDrop >= 0f && closeProofWindowMs > 0 && closeProofKeepMs >= 0) { "Press gate limits must be positive" }
        require(pressMaxDegPerS > 0f && movingPressRatio >= 0f && stableMargin >= 0f) { "Press gate speeds and ratios must be positive" }
        require(rearmAfterReleaseMs >= 0 && minReleaseGapMs >= 0 && badFrameQuietMs >= 0 && movingAlwaysDegPerS > 0f) { "Re-arm limits must be positive" }
    }

    companion object {
        /** 0.5.16's press gate: none of 0.5.17's re-arm, bounce, moving hold and bad frame rules, for the before and after tests. */
        val V0516 = PinchLockSettings(
            rearmAfterReleaseMs = 0,
            minReleaseGapMs = 0,
            proofAfterRelease = false,
            movingHoldRise = null,
            badFrameQuietMs = 0,
            movingAlwaysDegPerS = Float.MAX_VALUE,
            proofSkipsBrokenFrames = false,
            lowBandShare = 0f,
        )

        /** 0.5.17's press gate (a 150 ms bad frame quiet, no low band rule), for the tests of 0.5.18. */
        val V0517 = PinchLockSettings(badFrameQuietMs = 150, lowBandShare = 0f)

        /** 0.5.18 to 0.5.44's low band: 12 % of the picture, the pinch fingers or the wrist. */
        val V0544 = PinchLockSettings(lowBandWrist = true, settleBelowPxPerS = 1200f, flickStartDeg = null)
    }
}

/**
 * 0.5.18: the phone in Mike's hands. The two false presses 1.5 s
 * apart came while he opened the phone (89 ms after it opened), with the hand the camera saw
 * holding it. Hand presses are off for [afterFoldMs] after the phone folds or opens, while a finger
 * is on the phone's trackpad and for [afterTouchMs] after it lifts. The cover watcher and the
 * trackpad tell it ([postureChanged], [touch]); the hand recognizer asks ([muted]). Any thread.
 */
class PhoneHandling(val afterFoldMs: Long = 1000, val afterTouchMs: Long = 250) {
    @Volatile private var touches = 0
    @Volatile private var lastTouchUpMs = Long.MIN_VALUE / 2
    @Volatile private var lastPostureMs = Long.MIN_VALUE / 2

    /** A finger went down on the phone's trackpad ([down]) or every finger lifted, on the uptime clock. */
    fun touch(down: Boolean, nowMs: Long) {
        if (down) {
            touches = 1
        } else {
            touches = 0
            lastTouchUpMs = nowMs
        }
    }

    /** The phone folded or opened at [nowMs]. */
    fun postureChanged(nowMs: Long) {
        lastPostureMs = nowMs
    }

    /** Why hand presses are off at [nowMs], or null when they are not. */
    fun muted(nowMs: Long): String? = when {
        touches > 0 -> "a finger is on the phone's trackpad"
        nowMs - lastTouchUpMs < afterTouchMs -> "the phone's trackpad was touched %d ms ago (needs %d)".fmt(nowMs - lastTouchUpMs, afterTouchMs)
        nowMs - lastPostureMs < afterFoldMs -> "the phone was folded or opened %d ms ago (needs %d)".fmt(nowMs - lastPostureMs, afterFoldMs)
        else -> null
    }

    // ---- 0.5.19: the glasses on a head ----

    @Volatile private var rollDeg = 0f
    @Volatile private var pitchDeg = 0f
    @Volatile private var wornAtMs = Long.MIN_VALUE / 2
    @Volatile private var staticSinceMs: Long? = null

    /**
     * The head tracker's pose ([rollDeg], [pitchDeg]) and whether the IMU lies static (the table's
     * stillness, which no worn head reaches), on the uptime clock, about ten times a second.
     */
    fun glasses(rollDeg: Float, pitchDeg: Float, static: Boolean, nowMs: Long) {
        this.rollDeg = rollDeg
        this.pitchDeg = pitchDeg
        wornAtMs = nowMs
        staticSinceMs = if (static) staticSinceMs ?: nowMs else null
        val tilted = kotlin.math.abs(rollDeg) > maxTiltDeg || kotlin.math.abs(pitchDeg) > maxPitchDeg
        tiltSinceMs = if (tilted) tiltSinceMs ?: nowMs else null
    }

    /** 0.5.54: since when the glasses have been tilted past [maxTiltDeg] or [maxPitchDeg] without a break, or null. */
    @Volatile private var tiltSinceMs: Long? = null

    /**
     * Why the glasses are not on a head at [nowMs] (rolled or pitched over [maxTiltDeg], or static for
     * over [staticMs]), or null: worn, or no head pose for [staleMs] (no head tracking says nothing).
     * Mike's 8 presses in under a minute came with roll 173.6 degrees, the glasses upside down
     * on the table; one closed an app.
     *
     * 0.5.54 (Mike, 10/28: hand tracking froze for a second or two at random moments): every answer here pauses the hand
     * model, so the cursor stops. On 10-27 a worn head rolled 61 to 69 degrees paused it ten times (0.1 to 0.7 s, and
     * 5.5 s and 3.4 s with a hand in use). Glasses really off a head rolled 99 to 180 degrees or
     * pitched 77 to 81 for seconds to minutes. So a tilt counts only past [maxTiltDeg] 90 (sideways or worse) or
     * [maxPitchDeg], held for [tiltHoldMs]; upside down (over [upsideDownDeg]) still counts at once.
     */
    fun notWorn(nowMs: Long): String? {
        if (nowMs - wornAtMs > staleMs) return null
        val still = staticSinceMs?.let { nowMs - it }
        val tiltHeld = tiltSinceMs?.let { nowMs - it >= tiltHoldMs } == true
        return when {
            kotlin.math.abs(rollDeg) > upsideDownDeg -> "roll %.0f deg (over %.0f): the glasses are upside down".fmt(rollDeg, upsideDownDeg)
            tiltHeld && kotlin.math.abs(rollDeg) > maxTiltDeg -> "roll %.0f deg (over %.0f for %.1f s): the glasses are not upright".fmt(rollDeg, maxTiltDeg, tiltHoldMs / 1000.0)
            tiltHeld && kotlin.math.abs(pitchDeg) > maxPitchDeg -> "pitch %.0f deg (over %.0f for %.1f s): the glasses are not upright".fmt(pitchDeg, maxPitchDeg, tiltHoldMs / 1000.0)
            still != null && still > staticMs -> "the glasses have lain still for %.1f s (over %.0f)".fmt(still / 1000.0, staticMs / 1000.0)
            else -> null
        }
    }

    /** 0.5.54: 90 (was 60, which a worn head reached). */
    val maxTiltDeg = 90f
    val maxPitchDeg = 75f
    val upsideDownDeg = 150f
    val tiltHoldMs = 1_500L
    val staticMs = 5_000L
    val staleMs = 2_000L

    companion object {
        /** The app's one: the cover watcher and the trackpad write it, the hand recognizers read it. */
        val shared = PhoneHandling()
    }
}

/**
 * 0.5.11: the checks every press passes ([PinchLockSettings.pressHoldMs] and the fields after it).
 * Null lets the press through; otherwise why not, for the log. Pure, so tests can call it.
 */
object PressGate {
    /**
     * [belowMs]: how long the ratio has been at or below the press threshold (null when it is
     * not, or the rule does not need it: the lock's stable and held rules have their own time);
     * [sinceCloseMs]: time since the last real closing (null: none seen); [speedDegPerS]: the
     * hand's aim speed; [score]: MediaPipe's confidence, against [minScore].
     */
    fun refusal(
        s: PinchLockSettings,
        ratio: Float,
        pressRatio: Float,
        belowMs: Long?,
        needHold: Boolean,
        sinceCloseMs: Long?,
        speedDegPerS: Float,
        score: Float,
        minScore: Float,
        sinceOpenMs: Long? = null,
        /** 0.5.16: frames in a row at or below the press threshold, this one included ([PinchLockSettings.pressHoldFrames]). */
        framesBelow: Int = 0,
        /** 0.5.17: since this hand's last release (null: none yet). */
        sinceReleaseMs: Long? = null,
        /** 0.5.17: the ratio reached the release threshold at or after that release. */
        reopened: Boolean = true,
        /** 0.5.17: a "hand moving" refusal earlier in this closing ([PinchLockSettings.movingHoldRise]). */
        movingHeld: Boolean = false,
        /** 0.5.17: since this hand's last not ready or skipped frame (null: none kept). */
        sinceBadFrameMs: Long? = null,
        /** 0.5.17: a "too soon" refusal earlier in this closing: the whole bounce is refused, not only its first 200 ms. */
        bounceHeld: Boolean = false,
        /** 0.5.18: why the phone is being handled now ([PhoneHandling.muted]), or null. */
        phoneHandled: String? = null,
        /** 0.5.18: the hand is in the bottom band of the picture and was not seen open above it ([PinchLockSettings.lowBandShare]). */
        lowBand: Boolean = false,
        /** 0.5.19: why the glasses are not on a head ([PhoneHandling.notWorn]), or null. */
        notWorn: String? = null,
    ): String? = when {
        score < minScore -> "hand score %.2f below %.2f".fmt(score, minScore)
        notWorn != null -> "not worn: $notWorn"
        phoneHandled != null -> "phone in hand: $phoneHandled"
        lowBand -> "low in the camera: a pinch finger or the wrist in the bottom %.0f %% of the picture, the hand not seen open above it nor held open %d ms in it".fmt(s.lowBandShare * 100, s.lowBandOpenMs)
        sinceReleaseMs != null && sinceReleaseMs < s.minReleaseGapMs ->
            "too soon after the release (%d ms, needs %d)".fmt(sinceReleaseMs, s.minReleaseGapMs)
        bounceHeld ->
            "too soon after the release: this closing began within %d ms of it (the fingers must open again)".fmt(s.minReleaseGapMs)
        sinceReleaseMs != null && sinceReleaseMs <= s.rearmAfterReleaseMs && !reopened ->
            "not reopened since the release %d ms ago (the fingers never got back to the release threshold)".fmt(sinceReleaseMs)
        (sinceCloseMs == null || sinceCloseMs > s.closeProofKeepMs) && (sinceOpenMs == null || s.openProofMs <= 0 || sinceOpenMs > s.openProofMs) ->
            "no real closing (the fingers never fell %.2f within %d ms, and were not open within %d ms)".fmt(s.closeProofDrop, s.closeProofWindowMs, s.openProofMs)
        movingHeld && ratio > s.movingPressRatio ->
            "moving earlier in this closing (until the fingers open %.2f above the press threshold only a ratio of %.2f presses, it is %.2f)".fmt(
                s.movingHoldRise ?: 0f, s.movingPressRatio, ratio,
            )
        sinceBadFrameMs != null && sinceBadFrameMs < s.badFrameQuietMs ->
            "bad frame %d ms ago (no press within %d ms of a not ready or skipped frame)".fmt(sinceBadFrameMs, s.badFrameQuietMs)
        speedDegPerS > s.pressMaxDegPerS && (ratio > s.movingPressRatio || speedDegPerS > s.movingAlwaysDegPerS) ->
            "hand moving %.0f deg/s (over %.0f needs a ratio of %.2f, it is %.2f; over %.0f nothing presses)".fmt(
                speedDegPerS, s.pressMaxDegPerS, s.movingPressRatio, ratio, s.movingAlwaysDegPerS.coerceAtMost(9999f),
            )
        needHold && (belowMs ?: -1L) < s.pressHoldMs && (s.pressHoldFrames <= 0 || framesBelow < s.pressHoldFrames) ->
            "held below %.2f for %d ms of %d".fmt(pressRatio, maxOf(belowMs ?: 0L, 0L), s.pressHoldMs) +
                (if (s.pressHoldFrames > 0) ", %d frame%s of %d".fmt(framesBelow, if (framesBelow == 1) "" else "s", s.pressHoldFrames) else "")
        else -> null
    }

    /** 0.5.16: the refusal's kind for the hand numbers' tally: "hand score", "no real closing", "hand moving", "held below". */
    fun kind(reason: String): String = when {
        reason.startsWith("hand score") -> "hand score"
        reason.startsWith("no real closing") -> "no real closing"
        reason.startsWith("hand moving") -> "hand moving"
        reason.startsWith("held below") -> "held below"
        reason.startsWith("too soon") -> "too soon"
        reason.startsWith("not reopened") -> "not reopened"
        reason.startsWith("moving earlier") -> "moving earlier"
        reason.startsWith("bad frame") -> "bad frame"
        reason.startsWith("phone in hand") -> "phone in hand"
        reason.startsWith("not worn") -> "not worn"
        reason.startsWith("low in the camera") -> "low in the camera"
        else -> reason.split(' ').take(2).joinToString(" ")
    }

    /** Only the hold time is missing: the press may come on a later frame of the same closing. */
    fun onlyHold(reason: String?): Boolean = reason != null && reason.startsWith("held below")
}

/**
 * 0.5.5: the tap rule. A pinch shorter than [PinchLockSettings.tapMaxMs] whose hand drifted less
 * than [driftLimitPx] from its lock is a tap. The drift limit grows with the hand's size in the
 * picture: a hand nearer the glasses (bigger) sits closer to the body, so the same finger pull
 * swings the ray through a wider angle. It never shrinks below [PinchLockSettings.tapDriftPx].
 */
object PinchTap {
    fun driftLimitPx(handSize: Float, s: PinchLockSettings): Float =
        s.tapDriftPx * (handSize / s.tapHandSize).coerceIn(1f, s.tapDriftMaxScale)

    fun isTap(heldMs: Long, driftPx: Float, handSize: Float, s: PinchLockSettings): Boolean =
        heldMs < s.tapMaxMs && driftPx < driftLimitPx(handSize, s)

    /** No drag may start while a pinch could still be a tap. */
    fun dragAllowed(heldMs: Long, s: PinchLockSettings): Boolean = heldMs >= s.tapMaxMs
}

/**
 * One step of a pinch, logged at INFO under FlipSpacesHands so the next session shows the drift.
 * [raw] is where the smoothed hand is now; [locked] is where the cursor is held.
 */
sealed interface PinchTrace {
    fun line(): String

    data class ClosingOnset(val raw: Point, val ratio: Float, val fromRatio: Float, val strength: Float? = null) : PinchTrace {
        override fun line() = "Pinch closing at %s, ratio %.2f (from %.2f)".fmt(raw.xy(), ratio, fromRatio) +
            (strength?.let { ", strength %.2f: cursor frozen".fmt(it) } ?: "")
    }

    /**
     * [rewoundMs]: how far back the steady cursor the lock took was (0.5.4: the fixed select look
     * back); [capped] says the 50 px cap made it newer.
     */
    data class Locked(val locked: Point, val raw: Point, val rewoundMs: Long, val capped: Boolean = false) : PinchTrace {
        override fun line() = "Pinch aim locked at %s, rewound %d ms%s, hand at %s (drift %.0f px)".fmt(
            locked.xy(), rewoundMs, if (capped) " (capped)" else "", raw.xy(), raw.distanceTo(locked),
        )
    }

    /** 0.5.4: one bad frame skipped ([reason]); the cursor stayed at [at] and the pinch did not change. */
    data class FrameSkipped(val at: Point, val reason: String) : PinchTrace {
        override fun line() = "Hand frame skipped at %s: %s (one bad frame, the next one decides)".fmt(at.xy(), reason)
    }

    data class ClosingAborted(
        val locked: Point,
        val raw: Point,
        val ratio: Float,
        val lockedMs: Long,
        val reason: String = "fingers reopened",
    ) : PinchTrace {
        override fun line() = "Pinch closing aborted after %d ms (%s), ratio %.2f, lock %s, hand %s".fmt(lockedMs, reason, ratio, locked.xy(), raw.xy())
    }

    /** A closing onset skipped because the hand was moving at [speedPxPerS]. */
    data class OnsetIgnored(val raw: Point, val ratio: Float, val speedPxPerS: Float) : PinchTrace {
        override fun line() = "Pinch closing ignored at %s, ratio %.2f: hand moving %.0f px/s".fmt(raw.xy(), ratio, speedPxPerS)
    }

    /**
     * [how] says what made it a press ("ratio 0.36 <= 0.40", "stable at 0.36", "held 400 ms");
     * [pressRatio] and [releaseRatio] are the thresholds in use.
     */
    data class Press(
        val locked: Point,
        val raw: Point,
        val ratio: Float,
        val lockedMs: Long,
        val world: Boolean,
        val how: String = "",
        val pressRatio: Float = 0f,
        val releaseRatio: Float = 0f,
        /** 0.5.14: since the fingers were last open, ms (pinch onset); null when not seen. */
        val fromOpenMs: Long? = null,
        /** 0.5.17: what the press gate saw, on every press, not only on refusals. */
        val detail: String = "",
    ) : PinchTrace {
        override fun line() = "Pinch down at %s (locked %d ms before), hand at %s (drift %.0f px), ratio %.2f (%s)".fmt(
            locked.xy(), lockedMs, raw.xy(), raw.distanceTo(locked), ratio, if (world) "world" else "image",
        ) + (if (how.isEmpty()) "" else ", %s, thresholds %.2f / %.2f".fmt(how, pressRatio, releaseRatio)) +
            (fromOpenMs?.let { ", onset %d ms from open".fmt(it) } ?: "") +
            (if (detail.isEmpty()) "" else " [gate: $detail]")
    }

    /** 0.5.0: the hand stopped being ready for a pinch ([PinchReadiness]); [ended] says what that did. */
    data class NotReady(val raw: Point, val reason: String, val ended: String = "") : PinchTrace {
        override fun line() = "Pinch not ready at %s: %s%s".fmt(raw.xy(), reason, if (ended.isEmpty()) "" else ", $ended")
    }

    /** 0.5.11: a press the [PressGate] refused, and why ("hand moving 63 deg/s", "held below 0.38 for 20 ms of 30"). */
    data class Refused(val at: Point, val ratio: Float, val reason: String, val detail: String = "") : PinchTrace {
        override fun line() = "Pinch refused at %s, ratio %.2f: %s".fmt(at.xy(), ratio, reason) + if (detail.isEmpty()) "" else " [$detail]"
    }

    /** 0.5.0: [trace] from one hand of two. */
    data class Hand(val side: com.hayeco.flipspaces.core.pointer.HandSide, val trace: PinchTrace) : PinchTrace {
        override fun line() = "[${side.label} hand] ${trace.line()}"
    }

    /** A two hand stretch began or ended ([HandsRecognizer]). */
    data class Stretch(val scale: com.hayeco.flipspaces.core.pointer.PointerSignal.Scale) : PinchTrace {
        override fun line() = "Two hand stretch %s, factor %.2f".fmt(scale.phase.name.lowercase(Locale.ROOT), scale.factor)
    }

    /** The hand was lost while a lock or press was held: [ended] says what that did. */
    data class HandLost(val locked: Point, val lockedMs: Long, val ended: String) : PinchTrace {
        override fun line() = "Pinch lock at %s ended after %d ms: hand lost, %s".fmt(locked.xy(), lockedMs, ended)
    }

    /**
     * 0.5.47: how a pinch's palm side was read: [turn] ([PalmFacing.turn]), the hand's label, palm toward the glasses or
     * not (after [flipped]), and whether the pinch opened the launcher.
     */
    data class PalmRead(val turn: Float, val labelRight: Boolean?, val toward: Boolean, val launcher: Boolean, val flipped: Boolean) : PinchTrace {
        override fun line() = "Pinch palm read: turn %.2f, labelled %s, %s%s: %s".fmt(
            turn, labelRight?.let { if (it) "Right" else "Left" } ?: "unknown", if (toward) "palm toward the glasses" else "back of the hand",
            if (flipped) " (side flipped)" else "", if (launcher) "launcher" else "press",
        )
    }

    data class DragUnlock(val locked: Point, val raw: Point, val movedPx: Float, val heldMs: Long) : PinchTrace {
        override fun line() = "Pinch drag unlocked after %d ms, hand moved %.0f px, lock %s, hand %s".fmt(heldMs, movedPx, locked.xy(), raw.xy())
    }

    /** [how] (0.5.0) says what released it: the threshold, a hard or fast release, the picture, not ready. */
    data class Release(
        val at: Point,
        val locked: Point,
        val raw: Point,
        val ratio: Float,
        val heldMs: Long,
        val dragged: Boolean,
        val how: String = "",
    ) : PinchTrace {
        override fun line() = "Pinch up at %s after %d ms%s, lock %s, hand at %s (drift %.0f px), ratio %.2f".fmt(
            at.xy(), heldMs, if (dragged) " (drag)" else "", locked.xy(), raw.xy(), raw.distanceTo(locked), ratio,
        ) + if (how.isEmpty()) "" else ", $how"
    }
}

private fun Point.xy() = "%.0f,%.0f".fmt(x, y)

private fun String.fmt(vararg args: Any?) = String.format(Locale.ROOT, this, *args)
