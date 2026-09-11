package com.lazydoglab.zisee.ar.render

import android.opengl.GLES20
import com.lazydoglab.zisee.ar.annotation.PlacementState
import com.lazydoglab.zisee.ar.annotation.StrokeRibbon
import com.lazydoglab.zisee.ar.session.SpatialStroke
import com.lazydoglab.zisee.ar.spatial.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** GL owner is ArCameraRenderer. A reusable VBO holds screen triangles for surface geometry
 * and a separate upright number badge. No text bitmap, texture readback or per-marker buffer.
 */
internal class AnnotationOverlayRenderer : AutoCloseable {
    private val vertices = ByteBuffer.allocateDirect(65536 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var program = 0
    private var vbo = 0
    private var width = 1f
    private var height = 1f
    private var red = 0.37f
    private var green = 0.83f
    private var blue = 0.84f
    private var alpha = 1f

    fun markers(markers: List<ProjectedMarker>, outputWidth: Int, outputHeight: Int) {
        begin(outputWidth, outputHeight)
        val size = (minOf(width, height) * 0.075f).coerceIn(32f, 64f).coerceAtMost(minOf(width, height) * 0.9f)
        for (item in markers) {
            val x = item.point.x * width; val y = item.point.y * height
            val pending = item.marker.placementState == PlacementState.STABILIZING
            color(0.37f, 0.83f, 0.84f, if (pending) 0.55f else 0.95f)
            val ring = item.surfaceRing
            for (i in 0 until 48) {
                if (if (pending) i % 6 >= 3 else i % 12 >= 10) continue
                val a = i * Math.PI * 2 / 48; val b = (i + 1) * Math.PI * 2 / 48
                val p = ring.getOrNull(i); val q = ring.getOrNull((i + 1) % 48)
                line(p?.x?.times(width) ?: (x + cos(a).toFloat() * size * 0.44f),
                    p?.y?.times(height) ?: (y + sin(a).toFloat() * size * 0.44f),
                    q?.x?.times(width) ?: (x + cos(b).toFloat() * size * 0.44f),
                    q?.y?.times(height) ?: (y + sin(b).toFloat() * size * 0.44f), if (item.marker.selected) 3f else 2f)
            }
            disc(x, y, 2.5f)
            // HUD remains upright even on a sloping surface; its size is in source pixels.
            val badgeX = (x + size * 0.38f).coerceIn(size * 0.25f, width - size * 0.25f)
            val badgeY = (y - size * 0.4f).coerceIn(size * 0.25f, height - size * 0.25f)
            color(0.03f, 0.09f, 0.12f, 0.95f); disc(badgeX, badgeY, size * 0.24f)
            color(0.8f, 1f, 1f, if (pending) 0.7f else 1f)
            val text = item.marker.displayNumber.takeIf { it > 0 }?.toString() ?: "•"
            if (text != "•") {
                val digitWidth = minOf(size * 0.14f, size * 0.4f / (text.length * 1.35f - 0.35f))
                val start = badgeX - (text.length * digitWidth * 1.35f - digitWidth * 0.35f) / 2f
                text.forEachIndexed { index, digit -> digit(digit - '0', start + index * digitWidth * 1.35f,
                    badgeY - digitWidth, digitWidth) }
            }
        }
        flush()
    }

    fun strokes(strokes: List<SpatialStroke>, camera: WorldPose, intrinsics: CameraIntrinsics,
        outputWidth: Int, outputHeight: Int) {
        begin(outputWidth, outputHeight)
        color(0.37f, 0.83f, 0.84f, 0.9f)
        for (stroke in strokes) {
            if (stroke.tracking != ArTracking.TRACKING) continue
            val inverse = Rotation(-stroke.pose.rotation.x, -stroke.pose.rotation.y, -stroke.pose.rotation.z, stroke.pose.rotation.w)
            val cameraLocal = inverse.rotate(camera.position - stroke.pose.position)
            val ribbon = StrokeRibbon.build(stroke.geometry, cameraLocal)
            var index = 0
            while (index + 8 < ribbon.size) {
                fun point(offset: Int) = MarkerProjection.projectPoint(stroke.pose.transform(
                    Vec3(ribbon[index + offset], ribbon[index + offset + 1], ribbon[index + offset + 2])), camera, intrinsics)
                val a = point(0); val b = point(3); val c = point(6)
                if (a != null && b != null && c != null) {
                    if (vertices.remaining() < 18) flush()
                    vertex(a.x * width, a.y * height); vertex(b.x * width, b.y * height); vertex(c.x * width, c.y * height)
                }
                index += 9
            }
        }
        flush()
    }

    private fun begin(w: Int, h: Int) { require(w > 0 && h > 0); width = w.toFloat(); height = h.toFloat(); vertices.clear() }
    private fun color(r: Float, g: Float, b: Float, a: Float) { red = r; green = g; blue = b; alpha = a }
    private fun vertex(x: Float, y: Float) { vertices.put(x * 2 / width - 1).put(1 - y * 2 / height).put(red).put(green).put(blue).put(alpha) }
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float) {
        val dx = x2 - x1; val dy = y2 - y1
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) return
        if (vertices.remaining() < 36) flush()
        val x = -dy / length * thickness * 0.5f; val y = dx / length * thickness * 0.5f
        vertex(x1 - x, y1 - y); vertex(x1 + x, y1 + y); vertex(x2 - x, y2 - y)
        vertex(x2 - x, y2 - y); vertex(x1 + x, y1 + y); vertex(x2 + x, y2 + y)
    }
    private fun disc(x: Float, y: Float, radius: Float) {
        for (i in 0 until 24) {
            if (vertices.remaining() < 18) flush()
            val a = i * Math.PI / 12; val b = (i + 1) * Math.PI / 12
            vertex(x, y); vertex(x + cos(a).toFloat() * radius, y + sin(a).toFloat() * radius)
            vertex(x + cos(b).toFloat() * radius, y + sin(b).toFloat() * radius)
        }
    }
    private fun digit(value: Int, x: Float, y: Float, w: Float) {
        val mask = intArrayOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f)[value]
        val segments = arrayOf(floatArrayOf(0f,0f,1f,0f), floatArrayOf(1f,0f,1f,1f), floatArrayOf(1f,1f,1f,2f),
            floatArrayOf(0f,2f,1f,2f), floatArrayOf(0f,1f,0f,2f), floatArrayOf(0f,0f,0f,1f), floatArrayOf(0f,1f,1f,1f))
        for (i in segments.indices) if (mask and (1 shl i) != 0) {
            val s = segments[i]; line(x + s[0]*w, y + s[1]*w, x + s[2]*w, y + s[3]*w, (w * 0.2f).coerceAtLeast(1.2f))
        }
    }

    private fun flush() {
        val count = vertices.position() / 6
        if (count == 0) return
        if (program == 0) initialize()
        vertices.flip()
        GLES20.glUseProgram(program)
        GLES20.glViewport(0, 0, width.toInt(), height.toInt())
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * 24, vertices)
        GLES20.glEnableVertexAttribArray(0); GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 24, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(0); GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        vertices.clear()
    }

    private fun initialize() {
        var vertex = 0; var fragment = 0
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { GLES20.glDeleteShader(shader); error("AR overlay shader compilation failed") }
            return shader
        }
        try {
            vertex = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; attribute vec4 c; varying vec4 color; void main(){ gl_Position=vec4(p,0.,1.); color=c; }")
            fragment = compile(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec4 color; void main(){ gl_FragColor=color; }")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
            GLES20.glBindAttribLocation(program, 0, "p"); GLES20.glBindAttribLocation(program, 1, "c")
            GLES20.glLinkProgram(program)
            val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "AR overlay program link failed" }
            val buffers = IntArray(1); GLES20.glGenBuffers(1, buffers, 0); vbo = buffers[0]
            check(vbo != 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.capacity() * 4, null, GLES20.GL_DYNAMIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        } catch (error: Exception) { close(); throw error }
        finally { if (vertex != 0) GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment) }
    }

    override fun close() {
        if (vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        vbo = 0; program = 0
    }
}
