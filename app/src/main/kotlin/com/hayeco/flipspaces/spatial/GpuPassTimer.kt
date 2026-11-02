package com.hayeco.flipspaces.spatial

import android.opengl.GLES30
import com.hayeco.flipspaces.core.spatial.GpuPassTimes

/**
 * 0.5.16: GL timer queries (GL_EXT_disjoint_timer_query) around each pass
 * of a Space frame, so the log can tell the Space's own GPU work from waiting for a GPU busy with
 * other work ([GpuPassTimes]). The late draw's figure runs from the pose sample to the frame's GPU
 * fence and counts both.
 *
 * Results are read [RING] frames later and never waited for: a slot whose results are not in yet
 * when it comes round again is dropped. A frame during which the driver reports the GPU disjoint
 * (a clock or power change) is counted apart. GL thread only; [init] again for each new context.
 */
class GpuPassTimer(private val passes: Int, private val times: GpuPassTimes) {
    private var ids = IntArray(0)
    private val used = Array(RING) { BooleanArray(passes) }
    private val pending = BooleanArray(RING)
    private var frame = 0
    private var slot = 0
    private var active = false
    private var supported = false

    /** Results not in yet when their slot came round again. */
    var dropped = 0L
        private set

    fun init() {
        val ext = runCatching { GLES30.glGetString(GLES30.GL_EXTENSIONS) }.getOrNull().orEmpty()
        supported = "GL_EXT_disjoint_timer_query" in ext
        pending.fill(false)
        frame = 0
        active = false
        if (!supported) {
            times.unavailable = "this GPU's driver has no GL_EXT_disjoint_timer_query"
            return
        }
        ids = IntArray(RING * passes)
        GLES30.glGenQueries(ids.size, ids, 0)
        // Clears a disjoint left from before.
        disjointNow()
        times.unavailable = null
    }

    /** Before the frame's first pass: collects the results of the slot this frame reuses. */
    fun beginFrame() {
        if (!supported) return
        slot = frame % RING
        if (pending[slot]) collect(slot)
        pending[slot] = false
        used[slot].fill(false)
    }

    fun begin(pass: Int) {
        if (!supported || active || pass !in 0 until passes) return
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, ids[slot * passes + pass])
        used[slot][pass] = true
        active = true
    }

    fun end() {
        if (!supported || !active) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        active = false
    }

    fun endFrame() {
        if (!supported) return
        end()
        pending[slot] = used[slot].any { it }
        frame++
    }

    private fun collect(s: Int) {
        val out = IntArray(1)
        for (p in 0 until passes) {
            if (!used[s][p]) continue
            GLES30.glGetQueryObjectuiv(ids[s * passes + p], GLES30.GL_QUERY_RESULT_AVAILABLE, out, 0)
            if (out[0] == 0) {
                dropped++
                return
            }
        }
        if (disjointNow()) {
            times.addDisjoint()
            return
        }
        val ns = LongArray(passes)
        for (p in 0 until passes) {
            if (!used[s][p]) continue
            GLES30.glGetQueryObjectuiv(ids[s * passes + p], GLES30.GL_QUERY_RESULT, out, 0)
            // Unsigned 32 bits of nanoseconds: up to 4.29 s, far beyond any pass.
            ns[p] = out[0].toLong() and 0xffffffffL
        }
        times.addFrame(ns)
    }

    private fun disjointNow(): Boolean {
        val d = IntArray(1)
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, d, 0)
        return d[0] != 0
    }

    companion object {
        private const val RING = 4
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
    }
}
