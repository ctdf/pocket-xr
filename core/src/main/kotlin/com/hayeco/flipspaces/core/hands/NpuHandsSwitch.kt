package com.hayeco.flipspaces.core.hands

/**
 * 1.1.15: "Hands on the NPU" (Developer card) applied to the hands already running. 0.5.43 read the setting only when hands
 * started, and a camera reconnect keeps the same runner, so Mike's 1.1.14 log (vc145, 11-05) switched it off at
 * 15:19:03.236 ("hands on the NPU: off (Developer; from the next hands start)") and the NPU still ran every frame until the
 * log ended at 15:24:29: 748 ENN ExecuteModel calls, "Hands on the NPU check" lines at 15:19:15.910 to 15:20:34.969, and
 * every Camera line said "MediaPipe on NPU". Hands never started again in that session, so "the next hands start" never
 * came.
 *
 * Off now drops the NPU between two frames: MediaPipe is already built behind it, so the frames keep flowing and nothing
 * more goes to the NPU (no frames, no checks). On builds the NPU's models on their own thread while MediaPipe keeps
 * tracking and swaps them in between two frames; MediaPipe goes to the CPU first, as at a start with the NPU, so the GPU
 * stays the Space's.
 */
object NpuHandsSwitch {
    const val NPU = "NPU"

    enum class Step {
        /** Nothing to do. */
        KEEP,

        /** Off while the NPU runs: MediaPipe takes the next frame, the NPU's models close. */
        DROP,

        /** Off while the NPU's models were being built: close them when they are done, never use them. */
        DISCARD,

        /** On with MediaPipe on the GPU: move it to the CPU first (a delegate move, tracking on). */
        MEDIAPIPE_TO_CPU,

        /** On with MediaPipe on the CPU: build the NPU's models on their own thread. */
        BUILD,

        /** On and the NPU's models are built: they take the next frame. */
        INSTALL,
    }

    /**
     * What the feeding thread does between two frames. [wanted] is the setting now, [npuActive] the NPU running the
     * frames, [built] models waiting to be swapped in, [building] a build still running, [failed] the NPU could not be
     * built since it was last switched on (not this phone, or its models failed), [mediaPipeOnGpu] where MediaPipe runs.
     */
    fun step(wanted: Boolean, npuActive: Boolean, built: Boolean, building: Boolean, failed: Boolean, mediaPipeOnGpu: Boolean): Step = when {
        !wanted && npuActive -> Step.DROP
        !wanted && built -> Step.DISCARD
        !wanted -> Step.KEEP
        npuActive -> Step.KEEP
        built -> Step.INSTALL
        building || failed -> Step.KEEP
        mediaPipeOnGpu -> Step.MEDIAPIPE_TO_CPU
        else -> Step.BUILD
    }
}
