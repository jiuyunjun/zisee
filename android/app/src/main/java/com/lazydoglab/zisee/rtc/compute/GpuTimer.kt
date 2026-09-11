package com.lazydoglab.zisee.rtc.compute

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * GL_EXT_disjoint_timer_query around one frame's GL work, on the owning GL thread. Diagnostic only:
 * any GL error disables it and is drained so the processor's own GL checks never trip on it.
 */
internal class GpuTimer private constructor(private val query: Int) {
    private var enabled = true
    private var active = false

    fun begin() {
        if (!enabled) return
        GLES20.glGetIntegerv(GPU_DISJOINT_EXT, IntArray(1), 0) // Reading clears a stale disjoint flag.
        GLES30.glBeginQuery(TIME_ELAPSED_EXT, query)
        active = ok()
    }

    fun end() {
        if (!active) return
        active = false
        GLES30.glEndQuery(TIME_ELAPSED_EXT)
        ok()
    }

    /** Call after glFinish; null when unavailable, not ready, or the measurement was disjoint. */
    fun resultNs(): Long? {
        if (!enabled) return null
        val value = IntArray(1)
        GLES30.glGetQueryObjectuiv(query, GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
        if (!ok() || value[0] == 0) return null
        GLES30.glGetQueryObjectuiv(query, GLES30.GL_QUERY_RESULT, value, 0)
        val disjoint = IntArray(1).also { GLES20.glGetIntegerv(GPU_DISJOINT_EXT, it, 0) }
        if (!ok() || disjoint[0] != 0) return null
        return value[0].toLong() and 0xffff_ffffL
    }

    fun close() {
        GLES30.glDeleteQueries(1, intArrayOf(query), 0)
        drainGlErrors()
    }

    private fun ok(): Boolean {
        if (!drainGlErrors()) enabled = false
        return enabled
    }

    companion object {
        private const val TIME_ELAPSED_EXT = 0x88BF
        private const val GPU_DISJOINT_EXT = 0x8FBB

        fun createOrNull(): GpuTimer? {
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: return null
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return null
            if (!version.startsWith("OpenGL ES 3") || "GL_EXT_disjoint_timer_query" !in extensions.split(' ')) return null
            val ids = IntArray(1)
            GLES30.glGenQueries(1, ids, 0)
            return if (drainGlErrors() && ids[0] != 0) GpuTimer(ids[0]) else null
        }

        private fun drainGlErrors(): Boolean {
            var clean = true
            repeat(8) { if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return clean; clean = false }
            return false
        }
    }
}
