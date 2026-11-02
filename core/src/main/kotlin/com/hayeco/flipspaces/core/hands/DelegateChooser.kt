package com.hayeco.flipspaces.core.hands

import java.util.Locale

/**
 * 0.5.2: MediaPipe on the GPU or the CPU, whichever is faster on this phone while the Space draws.
 *
 * Mike's 0.5.1 log had GPU inference at 34 to 77 ms a frame (9 to 15 tracked of the camera's 30
 * fps): the GPU delegate shares the GPU with the Space and SurfaceFlinger. So the runner measures
 * [samples] results on the GPU and, if they average over [fastEnoughMs], [samples] on the CPU
 * (XNNPACK), and keeps the CPU only when it is at least [margin] faster. Decided once per camera
 * session; every step is one log line ([line]).
 */
class DelegateChooser(
    val samples: Int = 45,
    val fastEnoughMs: Float = 28f,
    val margin: Float = 0.85f,
    /**
     * 0.5.16: the Space's draw comes first. MediaPipe on the GPU took 30 to
     * 39 ms of GPU time per camera frame while the Space's side by side frames waited for the GPU
     * (10.6 and 24.5 % missed). With the Space drawing, the CPU is tried even when the GPU is fast,
     * and kept when it keeps up with the camera ([cpuKeepsUpMs], a camera frame at 30 fps) or is
     * within [spaceFirstSlack] of the GPU: a slightly slower hand model costs less than a Space frame
     * that misses. MediaPipe's tasks API has no GPU priority to lower, so this is the lever.
     */
    val spaceFirst: Boolean = false,
    val cpuKeepsUpMs: Float = 33f,
    val spaceFirstSlack: Float = 1.15f,
) {
    enum class Phase { GPU, CPU, DONE }

    var phase = Phase.GPU
        private set

    /** The delegate to keep once [phase] is DONE: true for the GPU. */
    var keepGpu = true
        private set

    private val times = ArrayList<Long>()
    private var gpuAverage = 0f
    private var cpuAverage = 0f

    /** The last decision, for the log. */
    var line: String? = null
        private set

    /** 0.5.3: the CPU trial is waiting for the Space to run steadily (logged once). */
    private var waitingLogged = false

    /**
     * One result's inference time on [gpu]. Returns the delegate to switch to now (true GPU,
     * false CPU), or null to stay.
     *
     * 0.5.3: [trialAllowed] says whether the Space has run steadily for a while. Switching to the
     * CPU rebuilds MediaPipe's graph (and switching back rebuilds the GPU one, new EGL context,
     * shader compiles); in Mike's 0.5.2 plug-in that rebuild came 0.8 s before SurfaceFlinger
     * stalled. So a slow GPU only starts the trial once the Space is steady; until then the GPU
     * is measured again.
     */
    fun record(gpu: Boolean, inferenceMs: Long, trialAllowed: Boolean = true, spaceDraws: Boolean = false): Boolean? {
        if (spaceDraws) spaceSeen = true
        if (inferenceMs <= 0 || inferenceMs > 2_000) return null
        // 0.5.16: a GPU kept before the Space was up is measured again once it draws.
        if (phase == Phase.DONE && spaceFirst && spaceDraws && keepGpu && !decidedForSpace) {
            phase = Phase.GPU
            times.clear()
            waitingLogged = false
        }
        when (phase) {
            Phase.DONE -> return null
            Phase.GPU -> {
                if (!gpu) {
                    // No GPU delegate at all (it failed to build): nothing to compare.
                    phase = Phase.DONE
                    keepGpu = false
                    line = "MediaPipe runs on the CPU (no GPU delegate)"
                    return null
                }
                times += inferenceMs
                if (times.size < samples) return null
                gpuAverage = times.average().toFloat()
                times.clear()
                val forSpace = spaceFirst && spaceDraws
                // 0.5.20 ("MediaPipe on the GPU at 24 ms a frame: kept"
                // in the moment the glasses' own 3D switch took the Space away, then the GPU
                // all session while the Space's late draw waited 2 to 10 ms p50 for it): with the Space
                // first, a measure taken while a Space that was drawing is away decides nothing; the
                // GPU is measured again until the Space draws, and then judged for it.
                if (spaceFirst && spaceSeen && !spaceDraws) {
                    if (!awayLogged) {
                        awayLogged = true
                        line = "MediaPipe on the GPU at %.0f ms a frame while the Space is away: nothing decided until it draws again".fmt(gpuAverage)
                    }
                    return null
                }
                awayLogged = false
                if (gpuAverage <= fastEnoughMs && !forSpace) {
                    phase = Phase.DONE
                    line = "MediaPipe on the GPU at %.0f ms a frame: kept".fmt(gpuAverage)
                    return null
                }
                // 0.5.17 (CPU 63 ms against GPU 24, the GPU kept): a CPU measured
                // too slow for the Space earlier in this app session is not tried again (each trial
                // rebuilds MediaPipe's graph twice and tracks at half rate for its 45 frames), unless
                // the GPU has since become slower than that CPU would have to be to win.
                val slowCpu = cpuTooSlowMs
                if (forSpace && slowCpu != null && slowCpu > maxOf(cpuKeepsUpMs, gpuAverage * spaceFirstSlack)) {
                    phase = Phase.DONE
                    keepGpu = true
                    decidedForSpace = true
                    line = "MediaPipe on the GPU at %.0f ms a frame while the Space draws: kept, the CPU took %.0f ms a frame earlier this session".fmt(gpuAverage, slowCpu)
                    return null
                }
                if (!trialAllowed) {
                    if (!waitingLogged) {
                        waitingLogged = true
                        line = "MediaPipe on the GPU at %.0f ms a frame: the CPU trial waits until the Space runs steadily".fmt(gpuAverage)
                    }
                    return null
                }
                phase = Phase.CPU
                line = if (forSpace) "MediaPipe on the GPU at %.0f ms a frame while the Space draws: trying the CPU so the Space gets the GPU".fmt(gpuAverage)
                else "MediaPipe on the GPU at %.0f ms a frame: trying the CPU".fmt(gpuAverage)
                return false
            }
            Phase.CPU -> {
                if (gpu) return null
                times += inferenceMs
                if (times.size < samples) return null
                cpuAverage = times.average().toFloat()
                times.clear()
                phase = Phase.DONE
                val forSpace = spaceFirst && spaceSeen
                decidedForSpace = forSpace
                val cpuOk = if (forSpace) cpuAverage <= maxOf(cpuKeepsUpMs, gpuAverage * spaceFirstSlack) else cpuAverage < gpuAverage * margin
                keepGpu = !cpuOk
                if (forSpace && keepGpu) cpuTooSlowMs = cpuAverage
                line ="MediaPipe: GPU %.0f ms, CPU %.0f ms a frame: keeping the %s%s".fmt(
                    gpuAverage, cpuAverage, if (keepGpu) "GPU" else "CPU",
                    when {
                        !forSpace -> ""
                        keepGpu -> " (the CPU is over %.0f ms and %.0f %% slower: it would not keep up with the camera)".fmt(maxOf(cpuKeepsUpMs, gpuAverage * spaceFirstSlack), (cpuAverage / gpuAverage - 1f) * 100)
                        else -> " (the Space's draw gets the GPU)"
                    },
                )
                return if (keepGpu) true else null
            }
        }
    }

    /**
     * 0.5.3: the Space stalled: no trial (and no graph rebuild) for the rest of this camera
     * session. Whatever delegate runs now is kept; nothing to switch.
     */
    fun abandon(why: String) {
        // 0.5.16: nor a Space-first trial later in the session.
        decidedForSpace = true
        if (phase == Phase.DONE) return
        keepGpu = phase == Phase.GPU
        phase = Phase.DONE
        times.clear()
        line = "MediaPipe stays on the ${if (keepGpu) "GPU" else "CPU"}: $why"
    }

    /** 0.5.16: the Space drew during this measure. */
    private var spaceSeen = false
    private var decidedForSpace = false

    /** 0.5.20: the "Space is away" line said once per absence. */
    private var awayLogged = false

    /**
     * 0.5.17: the CPU's time a frame when a Space-first trial found it too slow, kept for the app's
     * session: [reset] (a new camera session) does not forget it.
     */
    var cpuTooSlowMs: Float? = null
        private set

    fun reset() {
        spaceSeen = false
        decidedForSpace = false
        phase = Phase.GPU
        keepGpu = true
        times.clear()
        line = null
        waitingLogged = false
    }
}

private fun String.fmt(vararg args: Any?) = String.format(Locale.ROOT, this, *args)
