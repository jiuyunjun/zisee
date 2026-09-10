package com.lazydoglab.zisee.ar.session

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Looper
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.ar.render.ArCameraRenderer
import com.lazydoglab.zisee.ar.render.CameraTextureMapping
import com.lazydoglab.zisee.ar.render.CurrentCameraTexture
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
    private val sensorOrientation: Int,
) : ArBackend {
    private val owner = Thread.currentThread()
    private val eglContext = EGL14.eglGetCurrentContext()
    private var closed = false
    private var running = false
    private var displayRotation = 0
    private var lastCapturedTimestampNs = 0L
    private val currentTexture = CurrentCameraTexture()
    override val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

    private fun checkOwner() {
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        check(eglContext != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == eglContext)
        check(!closed)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        checkOwner()
        require(rotation in 0..3 && width > 0 && height > 0)
        session.setDisplayGeometry(rotation, width, height)
        displayRotation = rotation
    }

    fun imageRotationDegrees(): Int {
        checkOwner()
        return (sensorOrientation - displayRotation * 90 + 360) % 360
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
        currentTexture.invalidate()
        if (running) { session.pause(); running = false }
    }

    override fun capture(): HistoricalFrame? {
        checkOwner()
        check(running)
        currentTexture.invalidate()
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
        val snapshot = HistoricalFrame(
            VideoFrameReference(MediaTrack.BACK_CAMERA, frame.timestamp), camera.pose.toWorldPose(),
            CameraIntrinsics(dimensions[0], dimensions[1], focal[0], focal[1], centre[0], centre[1]),
            tracking, depth, planes,
        )
        // Transform while this is still the current native frame. NaN catches a no-op conversion.
        val uv = FloatArray(8) { Float.NaN }
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED,
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), Coordinates2d.TEXTURE_NORMALIZED, uv)
        currentTexture.publish(CurrentCameraTexture.Frame(snapshot.frame, dimensions[0], dimensions[1],
            CameraTextureMapping(uv)))
        return snapshot
    }

    /** Returns false for historical, invalidated or uncaptured references. The target framebuffer
     * is owned by the caller; encoding needs a separate retained RGB buffer, not this mutable OES.
     * Output is raw CPU-image oriented: apply display rotation exactly once downstream.
     */
    fun renderCamera(reference: VideoFrameReference, renderer: ArCameraRenderer,
        outputWidth: Int, outputHeight: Int): Boolean {
        checkOwner()
        if (!running) return false
        val frame = currentTexture.find(reference) ?: return false
        renderer.draw(cameraTextureId, frame.mapping, frame.width, frame.height, outputWidth, outputHeight)
        return true
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
                // The video samples the GPU texture through the CPU image's field of view (see
                // capture). Default configs can pair a 4:3 CPU image with a 16:9 texture; the image's
                // corners then fall outside the texture and CLAMP_TO_EDGE smears the edge columns
                // across both sides of the picture. Prefer a rear 30 fps config whose texture has the
                // image's aspect, largest texture first; otherwise keep ARCore's default.
                val configs = session.getSupportedCameraConfigs(com.google.ar.core.CameraConfigFilter(session)
                    .setFacingDirection(com.google.ar.core.CameraConfig.FacingDirection.BACK)
                    .setTargetFps(java.util.EnumSet.of(com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_30)))
                fun aspect(size: android.util.Size) = size.width.toFloat() / size.height
                val matching = configs.filter { kotlin.math.abs(aspect(it.imageSize) - aspect(it.textureSize)) < 0.01f }
                    .maxByOrNull { it.textureSize.width.toLong() * it.textureSize.height }
                if (matching != null) session.cameraConfig = matching
                val chosen = session.cameraConfig
                com.lazydoglab.zisee.core.logging.AndroidAppLogger.info(
                    com.lazydoglab.zisee.core.logging.AppEvent.AR_CAMERA_CONFIG,
                    "image=${chosen.imageSize.width}x${chosen.imageSize.height} " +
                        "texture=${chosen.textureSize.width}x${chosen.textureSize.height} " +
                        "matched=${matching != null} configs=${configs.size}")
                session.configure(Config(session).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    imageStabilizationMode = Config.ImageStabilizationMode.OFF
                    // No Cloud Anchors, Geospatial, recording, or persistent world map.
                })
                GLES20.glGenTextures(1, textures, 0)
                check(textures[0] != 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val orientation = requireNotNull(manager.getCameraCharacteristics(session.cameraConfig.cameraId)
                    .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION))
                return ArCoreBackend(session, cameraLease, textures[0], orientation)
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
