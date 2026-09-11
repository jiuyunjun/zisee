package com.lazydoglab.zisee.ar.render

import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Looper
import org.webrtc.GlRectDrawer

/** Draws an OES camera image into the current caller-owned framebuffer and viewport.
 * Owns only its shader. Construct/draw/close on the same non-main thread AND EGL context.
 * The caller sets framebuffer, blend/depth/scissor/color-mask state. Draw changes GL program,
 * texture/attribute bindings and viewport, like the existing WebRTC drawers.
 * Finish this draw before the next ARCore update; never hand the borrowed OES id to an encoder.
 */
class ArCameraRenderer : AutoCloseable {
    private val owner = Thread.currentThread()
    private val eglContext = EGL14.eglGetCurrentContext()
    private val drawer = GlRectDrawer()
    private var markerProgram = 0
    private val annotations = AnnotationOverlayRenderer()
    private var closed = false
    init { checkOwner() }

    private fun checkOwner() {
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        check(eglContext != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == eglContext)
        check(!closed)
    }

    fun draw(textureId: Int, mapping: CameraTextureMapping, imageWidth: Int, imageHeight: Int,
        outputWidth: Int, outputHeight: Int) {
        checkOwner()
        require(textureId > 0 && imageWidth > 0 && imageHeight > 0 && outputWidth > 0 && outputHeight > 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
        drawer.drawOes(textureId, mapping.glMatrix(), imageWidth, imageHeight, 0, 0, outputWidth, outputHeight)
    }

    /** Draws small, high-contrast billboards into the same source frame as the camera image. */
    fun drawMarkers(markers: List<ProjectedMarker>, outputWidth: Int, outputHeight: Int, rotationDegrees: Int = 0) {
        checkOwner()
        if (markers.isEmpty()) return
        annotations.markers(markers.filter { it.marker.kind != com.lazydoglab.zisee.ar.session.MarkerKind.CIRCLE }, outputWidth, outputHeight, rotationDegrees)
        if (markerProgram == 0) markerProgram = createMarkerProgram()
        GLES20.glUseProgram(markerProgram)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val position = GLES20.glGetAttribLocation(markerProgram, "aPosition")
        val kind = GLES20.glGetUniformLocation(markerProgram, "uKind")
        GLES20.glEnableVertexAttribArray(position)
        markers.filter { it.marker.kind == com.lazydoglab.zisee.ar.session.MarkerKind.CIRCLE }.groupBy { it.marker.kind }.forEach { (markerKind, group) ->
            val values = FloatArray(group.size * 2)
            group.forEachIndexed { index, item ->
                values[index * 2] = item.point.x * 2f - 1f
                values[index * 2 + 1] = 1f - item.point.y * 2f
            }
            val buffer = java.nio.ByteBuffer.allocateDirect(values.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(values).apply { position(0) }
            GLES20.glUniform1i(kind, markerKind.ordinal)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, buffer)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, group.size)
        }
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun drawStrokes(strokes: List<com.lazydoglab.zisee.ar.session.SpatialStroke>,
        camera: com.lazydoglab.zisee.ar.spatial.WorldPose, intrinsics: com.lazydoglab.zisee.ar.spatial.CameraIntrinsics,
        width: Int, height: Int) {
        checkOwner()
        annotations.strokes(strokes, camera, intrinsics, width, height)
    }

    private fun createMarkerProgram(): Int {
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
            val status = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "AR marker shader compile failed" }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; void main(){ gl_Position=vec4(aPosition,0.0,1.0); gl_PointSize=40.0; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; uniform int uKind; void main(){ vec2 p=gl_PointCoord-vec2(0.5); float r=length(p); float a=0.0; if(uKind==2){ a=step(0.31,r)*step(r,0.46); } else if(uKind==1){ float head=step(r,0.20); float shaft=step(abs(p.x),0.055)*step(0.15,p.y); float wing=step(abs(abs(p.x)-(0.32-p.y)),0.07)*step(p.y,0.18); a=max(head,max(shaft,wing)); } else { float head=step(r,0.25); float stem=step(abs(p.x),0.06)*step(0.0,p.y); a=max(head,stem); } vec3 c=vec3(0.37,0.83,0.84); gl_FragColor=vec4(c,a); }")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it)
            val status = IntArray(1); GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            check(status[0] != 0) { "AR marker shader link failed" }
        }
    }

    override fun close() {
        if (closed) return
        checkOwner()
        drawer.release()
        annotations.close()
        if (markerProgram != 0) GLES20.glDeleteProgram(markerProgram)
        closed = true
    }
}
