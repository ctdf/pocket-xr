package com.hayeco.flipspaces.spatial

import android.opengl.GLES30
import com.hayeco.flipspaces.core.spatial.EdgeFade

/**
 * 0.5.61 (Mike: the left eye's "wall" is pixel 0 of its picture; black is see-through on the glasses): the soft fade to
 * black on each eye's edges, the last pass on the window, after the late warp, so it is fixed to the display and not to
 * the room. Each pixel near an edge is multiplied by [EdgeFade.alpha] (blend zero, source alpha; the window's alpha
 * kept), drawn only on four scissored strips per eye that do not overlap, so the inside of the picture costs nothing.
 * GL thread only; [forget] on a new context.
 */
internal class EdgeFadeGl {
    private var program = 0
    private var uRect = -1
    private var uWidths = -1

    /**
     * Fades the eye areas [areas] (x, y, w, h in GL window pixels, from the bottom left) of the bound framebuffer, each
     * by its [widths]. The blend and scissor are left as the renderer keeps them (blend on, scissor off).
     */
    fun fade(areas: List<IntArray>, widths: List<EdgeFade.Widths>) {
        if (program == 0) {
            program = link(VERTEX, FRAGMENT)
            uRect = GLES30.glGetUniformLocation(program, "uRect")
            uWidths = GLES30.glGetUniformLocation(program, "uWidths")
        }
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ZERO, GLES30.GL_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        for ((i, a) in areas.withIndex()) {
            val w = widths[i]
            if (w.none) continue
            val x = a[0]; val y = a[1]; val aw = a[2]; val ah = a[3]
            GLES30.glViewport(x, y, aw, ah)
            GLES30.glUniform4f(uRect, x.toFloat(), y.toFloat(), aw.toFloat(), ah.toFloat())
            GLES30.glUniform4f(uWidths, w.left.toFloat(), w.right.toFloat(), w.top.toFloat(), w.bottom.toFloat())
            val l = w.left.coerceIn(0, aw)
            val r = w.right.coerceIn(0, aw - l)
            val inner = aw - l - r
            // Left and right strips the whole height; top and bottom between them (GL's y is from the bottom).
            strip(x, y, l, ah)
            strip(x + aw - r, y, r, ah)
            strip(x + l, y + ah - w.top.coerceIn(0, ah), inner, w.top.coerceIn(0, ah))
            strip(x + l, y, inner, w.bottom.coerceIn(0, ah - w.top.coerceIn(0, ah)))
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBlendFunc(GlKit.BLEND_SRC, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun strip(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        GLES30.glScissor(x, y, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** The context is gone: forget the program without deleting it. */
    fun forget() {
        program = 0
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
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

        /**
         * [EdgeFade.alpha] in GLSL: the pixel's place in the eye's area (pixels counted from 0, the top row 0), one
         * smoothstep ramp per edge, black with that alpha so the blend multiplies the picture by it.
         */
        const val FRAGMENT = """#version 300 es
precision highp float;
uniform vec4 uRect;
uniform vec4 uWidths;
out vec4 outColor;
float ramp(float d, float w) { return w <= 0.0 ? 1.0 : smoothstep(0.0, w, d); }
void main() {
    vec2 p = gl_FragCoord.xy - 0.5 - uRect.xy;
    float x = p.x;
    float y = uRect.w - 1.0 - p.y;
    float a = ramp(x, uWidths.x) * ramp(uRect.z - 1.0 - x, uWidths.y) * ramp(y, uWidths.z) * ramp(uRect.w - 1.0 - y, uWidths.w);
    outColor = vec4(0.0, 0.0, 0.0, a);
}
"""
    }
}
