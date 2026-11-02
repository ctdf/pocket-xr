package com.hayeco.flipspaces.spatial

import android.opengl.GLES30

/**
 * 0.5.25: the per-eye late warp's GL side, side by side 3D only. Two eye buffers
 * the size of the window ([com.hayeco.flipspaces.core.spatial.LateWarpFrames] says which one a frame draws into);
 * [warp] puts one on the window turned by the newest pose, each eye through its own homography
 * ([com.hayeco.flipspaces.core.spatial.EyeWarp]), the top and the bottom row's pose blended by height as the
 * per-row pose does it. One full screen triangle per eye, one bilinear tap a pixel: well under 1 ms of GPU.
 * GL thread only; every object belongs to the context it was made in ([release] on a new one).
 */
internal class EyeWarpGl {
    private var program = 0
    private var uTex = -1
    private var uTop = -1
    private var uBottom = -1
    private var uEye = -1
    private var uClear = -1
    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private val fence = LongArray(2)
    var width = 0
        private set
    var height = 0
        private set

    /** The buffers for a [w] by [h] window, made or remade when its size changes. */
    fun ensure(w: Int, h: Int) {
        if (program == 0) {
            program = link(VERTEX, FRAGMENT)
            uTex = GLES30.glGetUniformLocation(program, "uTex")
            uTop = GLES30.glGetUniformLocation(program, "uTop")
            uBottom = GLES30.glGetUniformLocation(program, "uBottom")
            uEye = GLES30.glGetUniformLocation(program, "uEye")
            uClear = GLES30.glGetUniformLocation(program, "uClear")
        }
        if (w == width && h == height && tex[0] != 0) return
        deleteBuffers()
        GLES30.glGenTextures(2, tex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        width = w
        height = h
    }

    /** Draw into buffer [slot] from here (the eye passes' viewports are window pixels, the same in it). */
    fun bindScene(slot: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[slot])
    }

    /** The scene in [slot] went to the GPU: a fence to know when it is done. Back to the window. */
    fun sceneSubmitted(slot: Int) {
        if (fence[slot] != 0L) GLES30.glDeleteSync(fence[slot])
        fence[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glFlush()
    }

    /** True when the scene in [slot] is finished on the GPU (or nothing is pending there). */
    fun sceneDone(slot: Int): Boolean {
        val f = fence[slot]
        if (f == 0L) return true
        val r = GLES30.glClientWaitSync(f, 0, 0L)
        if (r == GLES30.GL_ALREADY_SIGNALED || r == GLES30.GL_CONDITION_SATISFIED) {
            GLES30.glDeleteSync(f)
            fence[slot] = 0L
            return true
        }
        return r == GLES30.GL_WAIT_FAILED
    }

    /**
     * Buffer [slot] onto the window (bound already), eye by eye: [eyes] are the viewports (x, y, w, h), [top] and
     * [bottom] each eye's homography for the top and the bottom row's pose; [clear] the colour outside the picture.
     * 0.5.27: [dest] the window viewports each eye is put in, when the picture adjust moves them away from [eyes] (which
     * stay where each eye was drawn in the buffer); the homographies are then on [dest]'s own coordinates.
     */
    fun warp(slot: Int, eyes: List<IntArray>, top: List<FloatArray>, bottom: List<FloatArray>, clear: FloatArray, dest: List<IntArray> = eyes) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[slot])
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform4f(uClear, clear[0], clear[1], clear[2], 1f)
        for ((i, e) in eyes.withIndex()) {
            val d = dest[i]
            GLES30.glViewport(d[0], d[1], d[2], d[3])
            GLES30.glUniformMatrix3fv(uTop, 1, false, top[i], 0)
            GLES30.glUniformMatrix3fv(uBottom, 1, false, bottom[i], 0)
            GLES30.glUniform4f(uEye, e[0].toFloat() / width, e[1].toFloat() / height, e[2].toFloat() / width, e[3].toFloat() / height)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun deleteBuffers() {
        for (i in 0..1) if (fence[i] != 0L) {
            GLES30.glDeleteSync(fence[i])
            fence[i] = 0L
        }
        if (tex[0] != 0) GLES30.glDeleteTextures(2, tex, 0)
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(2, fbo, 0)
        tex.fill(0)
        fbo.fill(0)
        width = 0
        height = 0
    }

    /** The context is gone (a new one has no such objects): forget them without deleting. */
    fun forget() {
        program = 0
        tex.fill(0)
        fbo.fill(0)
        fence.fill(0L)
        width = 0
        height = 0
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("GL shader failed: ${GLES30.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("GL link failed: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }

    private companion object {
        /** One triangle that covers the viewport, from gl_VertexID (no vertex buffer). */
        const val VERTEX = """#version 300 es
out vec2 vNdc;
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    vNdc = p;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

        /** The window pixel's place in the drawn eye: the two rows' homographies blended by height, then divided. */
        const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform sampler2D uTex;
uniform mat3 uTop;
uniform mat3 uBottom;
uniform vec4 uEye;
uniform vec4 uClear;
out vec4 outColor;
void main() {
    float w = clamp((1.0 - vNdc.y) * 0.5, 0.0, 1.0);
    vec3 p = vec3(vNdc, 1.0);
    vec3 h = mix(uTop * p, uBottom * p, w);
    if (h.z <= 1e-5) { outColor = uClear; return; }
    vec2 n = h.xy / h.z;
    if (abs(n.x) > 1.0 || abs(n.y) > 1.0) { outColor = uClear; return; }
    outColor = texture(uTex, uEye.xy + (n * 0.5 + 0.5) * uEye.zw);
}
"""
    }
}
