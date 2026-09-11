package com.lazydoglab.zisee.rtc

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A self-view for screens shown before any call media exists (the incoming-call answer screen):
 * a single front-camera preview built on CameraX, same as [DualCameraCapture]'s bridge, but
 * feeding a plain [VideoFeed] instead of a WebRTC [org.webrtc.VideoSource] since nothing here
 * is ever sent to a peer. Deliberately independent of [NativeRtcSession]'s own capture pipeline
 * so the two never contend for the camera device; the caller must [close] this before that
 * session's `start()` runs.
 */
class FrontCameraPreview(private val context: Context) {
    private val egl = EglBase.create()
    val feed = VideoFeed(egl.eglBaseContext, mirrored = true)
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var helper: SurfaceTextureHelper? = null
    private var cameraInfo: CameraInfo? = null

    /** Returns whether a first frame arrived within the timeout; false leaves [feed] with no source. */
    suspend fun start(): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (continuation.isActive) try { continuation.resume(future.get()) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }, ContextCompat.getMainExecutor(context))
            }
            val frontCameraInfo = CameraSelector.DEFAULT_FRONT_CAMERA.filter(cameraProvider.availableCameraInfos).firstOrNull()
                ?: return@withContext false
            cameraInfo = frontCameraInfo
            provider = cameraProvider
            val lifecycleOwner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            owner = lifecycleOwner
            lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
            val surfaceHelper = requireNotNull(SurfaceTextureHelper.create("ZiseeIncomingPreview", egl.eglBaseContext))
            helper = surfaceHelper
            val firstFrame = CompletableDeferred<Unit>()
            var transform: CameraTextureTransform? = null
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                surfaceHelper.setTextureSize(request.resolution.width, request.resolution.height)
                request.setTransformationInfoListener(ContextCompat.getMainExecutor(context)) { info ->
                    transform = CameraTextureTransform(
                        info.rotationDegrees, info.hasCameraTransform(),
                        frontCameraInfo.getSensorRotationDegrees(Surface.ROTATION_0), true,
                    )
                }
                val surface = Surface(surfaceHelper.surfaceTexture)
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { surface.release() }
            }
            surfaceHelper.startListening { frame ->
                val current = transform ?: return@startListening
                val buffer = frame.buffer as TextureBufferImpl
                val corrected = buffer.applyTransformMatrix(current.textureCorrection(), buffer.width, buffer.height)
                val output = VideoFrame(corrected, current.rotationDegrees, frame.timestampNs)
                try { feed.onFrame(output); firstFrame.complete(Unit) } finally { output.release() }
            }
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            withTimeoutOrNull(3_000) { firstFrame.await() } != null
        } catch (error: Exception) {
            close()
            false
        }
    }

    /**
     * Awaits the framework's own confirmation that the device handle is shut, not merely that
     * unbind returned: [NativeRtcSession.start] opens the same front camera moments later, and
     * Camera2's close is asynchronous underneath CameraX's synchronous-looking unbind.
     */
    suspend fun close() = withContext(Dispatchers.Main.immediate + NonCancellable) {
        val info = cameraInfo
        (owner?.lifecycle as? LifecycleRegistry)?.currentState = Lifecycle.State.DESTROYED
        if (owner != null) provider?.unbindAll()
        owner = null
        helper?.stopListening()
        helper?.dispose()
        helper = null
        feed.close()
        if (info != null) withTimeoutOrNull(2_000) {
            while (info.cameraState.value?.type != CameraState.Type.CLOSED) delay(20)
        }
        egl.release()
    }
}
