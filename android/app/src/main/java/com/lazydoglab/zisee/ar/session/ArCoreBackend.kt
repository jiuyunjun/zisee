package com.lazydoglab.zisee.ar.session

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Looper
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.ar.spatial.*
import com.lazydoglab.zisee.media.MediaTrack
import java.nio.ByteOrder

/** The integration layer must first stop/release CameraX and WebRTC camera capture, then grant
 * exclusive rear-camera ownership. close restores the previous capture mode, even on AR failure.
 * This is deliberately not inferred from a Boolean or from device concurrent-camera support.
 */
fun interface ArCameraLease : AutoCloseable { override fun close() }

/** Actual ARCore adapter; no CPU camera bitmap conversion or image retention.
 * Create/use/close on one GL worker with a current EGL context. It owns the OES camera texture,
 * Session and lease, but not the caller's EGL context. Stop GL callbacks before destroying EGL.
 */
class ArCoreBackend private constructor(
    private val session: Session,
    private val cameraLease: ArCameraLease,
    val cameraTextureId: Int,
) : ArBackend {
    private val owner = Thread.currentThread()
    private var closed = false
    private var running = false
    private var lastCapturedTimestampNs = 0L
    override val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

    private fun checkOwner() {
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        check(!closed)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        checkOwner()
        require(rotation in 0..3 && width > 0 && height > 0)
        session.setDisplayGeometry(rotation, width, height)
    }

    override fun resume() {
        checkOwner()
        if (running) return
        session.setCameraTextureName(cameraTextureId)
        session.resume()
        running = true
    }

    override fun pause() {
        checkOwner()
        if (running) { session.pause(); running = false }
    }

    override fun capture(): HistoricalFrame? {
        checkOwner()
        check(running)
        val frame = session.update()
        if (frame.timestamp <= lastCapturedTimestampNs) return null
        lastCapturedTimestampNs = frame.timestamp
        val camera = frame.camera
        val intrinsics = camera.imageIntrinsics
        val dimensions = intrinsics.imageDimensions
        val focal = intrinsics.focalLength
        val centre = intrinsics.principalPoint
        val tracking = camera.trackingState.toTracking()
        val depth = if (depthSupported && tracking == ArTracking.TRACKING) {
            try {
                frame.acquireDepthImage16Bits().use { image ->
                    // A reused depth image is not historical evidence for a different video frame.
                    if (image.timestamp != frame.timestamp) null else {
                        val width = minOf(image.width, 160)
                        val height = minOf(image.height, 120)
                        val plane = image.planes[0]
                        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        val base = buffer.position()
                        val values = ShortArray(width * height)
                        for (y in 0 until height) for (x in 0 until width) {
                            val sourceX = ((x + 0.5f) * image.width / width).toInt().coerceAtMost(image.width - 1)
                            val sourceY = ((y + 0.5f) * image.height / height).toInt().coerceAtMost(image.height - 1)
                            values[y * width + x] = buffer.getShort(base + sourceY * plane.rowStride + sourceX * plane.pixelStride)
                        }
                        DepthSnapshot(width, height, values)
                    }
                }
            } catch (_: NotYetAvailableException) { null } // Expected while depth is warming up; planes remain available.
        } else null
        val planes = if (tracking == ArTracking.TRACKING) session.getAllTrackables(Plane::class.java)
            .asSequence().filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .mapNotNull { plane ->
                val polygon = plane.polygon.duplicate()
                if (polygon.remaining() / 2 !in 3..128) null else {
                    val vertices = buildList { while (polygon.remaining() >= 2) add(Vec3(polygon.get(), 0f, polygon.get())) }
                    PlaneSnapshot(plane.centerPose.toWorldPose(), vertices)
                }
            }.take(16).toList() else emptyList()
        return HistoricalFrame(
            VideoFrameReference(MediaTrack.BACK_CAMERA, frame.timestamp), camera.pose.toWorldPose(),
            CameraIntrinsics(dimensions[0], dimensions[1], focal[0], focal[1], centre[0], centre[1]),
            tracking, depth, planes,
        )
    }

    override fun createAnchor(pose: WorldPose): LocalAnchor {
        checkOwner()
        check(running)
        val anchor = session.createAnchor(pose.toNativePose())
        return object : LocalAnchor {
            override val pose: WorldPose get() { checkOwner(); return anchor.pose.toWorldPose() }
            override val tracking: ArTracking get() { checkOwner(); return anchor.trackingState.toTracking() }
            override fun detach() { checkOwner(); anchor.detach() }
        }
    }

    override fun close() {
        if (closed) return
        checkOwner()
        // All cleanup is attempted, even if native pause or close throws.
        try { pause() } finally {
            closed = true
            try { session.close() } finally {
                try { GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0) }
                finally { cameraLease.close() }
            }
        }
    }

    companion object {
        /** prepare() must have returned READY; ownership of lease transfers even if creation fails. */
        fun create(context: Context, cameraLease: ArCameraLease): ArCoreBackend {
            var session: Session? = null
            val textures = IntArray(1)
            try {
                check(Looper.myLooper() != Looper.getMainLooper())
                check(GLES20.glGetString(GLES20.GL_VERSION) != null) { "A current EGL context is required" }
                session = Session(context.applicationContext)
                session.configure(Config(session).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    // No Cloud Anchors, Geospatial, recording, or persistent world map.
                })
                GLES20.glGenTextures(1, textures, 0)
                check(textures[0] != 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                return ArCoreBackend(session, cameraLease, textures[0])
            } catch (error: Exception) {
                try { session?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                if (textures[0] != 0) GLES20.glDeleteTextures(1, textures, 0)
                try { cameraLease.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}

private fun Pose.toWorldPose(): WorldPose {
    val t = translation
    val q = rotationQuaternion
    return WorldPose(Vec3(t[0], t[1], t[2]), Rotation(q[0], q[1], q[2], q[3]))
}
private fun WorldPose.toNativePose() = Pose(
    floatArrayOf(position.x, position.y, position.z),
    floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
)
private fun TrackingState.toTracking() = when (this) {
    TrackingState.TRACKING -> ArTracking.TRACKING
    TrackingState.PAUSED -> ArTracking.PAUSED
    TrackingState.STOPPED -> ArTracking.STOPPED
}
