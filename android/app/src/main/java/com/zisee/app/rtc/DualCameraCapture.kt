package com.zisee.app.rtc

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Two independent GPU surfaces, never an ImageAnalysis/Bitmap or a composited video. */
class DualCameraCapture(private val context: Context, private val egl: EglBase.Context) {
    private var provider: ProcessCameraProvider? = null
    private var selectors: List<CameraSelector>? = null
    private var owner: LifecycleOwner? = null
    private val bridges = mutableListOf<Bridge>()
    private val previews = mutableListOf<Preview>()
    @Volatile private var targetRotation = Surface.ROTATION_0

    /**
     * CameraX reports frame rotation relative to this. Follow the effective display rotation so
     * Face Call and Show Me both respect the user's screen rotation lock.
     */
    suspend fun setTargetRotation(rotation: Int) = withContext(Dispatchers.Main.immediate) {
        targetRotation = rotation
        previews.forEach { it.targetRotation = rotation }
    }

    suspend fun supported(): Boolean = withContext(Dispatchers.Main.immediate) {
        val future = ProcessCameraProvider.getInstance(context)
        val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
            future.addListener({
                if (continuation.isActive) try { continuation.resume(future.get()) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }, ContextCompat.getMainExecutor(context))
        }
        provider = cameraProvider
        val pair = cameraProvider.availableConcurrentCameraInfos.firstOrNull { infos ->
            infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT } &&
                infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        } ?: return@withContext false
        selectors = listOf(CameraSelector.LENS_FACING_FRONT, CameraSelector.LENS_FACING_BACK).map { facing ->
            pair.first { it.lensFacing == facing }.cameraSelector
        }
        true
    }

    suspend fun start(front: VideoSource, back: VideoSource) {
        withContext(Dispatchers.Main.immediate) {
            val lifecycleOwner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            owner = lifecycleOwner
            lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
            val sources = listOf(front, back)
            val configs = requireNotNull(selectors).mapIndexed { index, selector ->
                val bridge = Bridge(sources[index], "ZiseeDual$index")
                bridges.add(bridge)
                // Conservative concurrent format; the primary may be upgraded after device validation.
                val preview = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()).setTargetRotation(targetRotation).build()
                previews.add(preview)
                preview.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    bridge.helper.setTextureSize(request.resolution.width, request.resolution.height)
                    request.setTransformationInfoListener(ContextCompat.getMainExecutor(context)) { bridge.rotation = it.rotationDegrees }
                    val surface = Surface(bridge.helper.surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { surface.release() }
                }
                ConcurrentCamera.SingleCameraConfig(selector, UseCaseGroup.Builder().addUseCase(preview).build(), lifecycleOwner)
            }
            requireNotNull(provider).bindToLifecycle(configs)
        }
        // A successful bind is not proof that either camera is producing frames.
        withTimeout(5_000) { bridges.forEach { it.firstFrame.await() } }
    }

    suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            (owner?.lifecycle as? LifecycleRegistry)?.currentState = Lifecycle.State.DESTROYED
            if (owner != null) provider?.unbindAll()
            owner = null
        }
        bridges.forEach { it.close() }
        bridges.clear()
        previews.clear()
    }

    private inner class Bridge(private val source: VideoSource, name: String) {
        val helper = requireNotNull(SurfaceTextureHelper.create(name, egl))
        val firstFrame = CompletableDeferred<Unit>()
        @Volatile var rotation = 0
        init {
            source.capturerObserver.onCapturerStarted(true)
            helper.startListening { frame ->
                source.capturerObserver.onFrameCaptured(VideoFrame(frame.buffer, rotation, frame.timestampNs))
                firstFrame.complete(Unit)
            }
        }
        fun close() {
            helper.stopListening()
            source.capturerObserver.onCapturerStopped()
            helper.dispose()
        }
    }
}
