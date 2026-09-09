package com.lazydoglab.zisee.ar.session

import android.content.Context
import com.lazydoglab.zisee.ar.annotation.ArMessage
import com.lazydoglab.zisee.ar.collaboration.ArControllerEndpoint
import com.lazydoglab.zisee.ar.collaboration.ArFieldEndpoint
import com.lazydoglab.zisee.ar.render.ArCameraRenderer
import com.lazydoglab.zisee.ar.render.ArFramePool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import java.util.UUID

/** Owns one AR camera/controller/GL worker. The collaboration channel owns this endpoint. */
class ArVideoCapture private constructor(
    private val helper: SurfaceTextureHelper,
    private val source: VideoSource,
    private val onFailure: () -> Unit,
) : ArFieldEndpoint {
    private val handler = helper.handler
    private val dispatcher = handler.asCoroutineDispatcher("ZiseeAr")
    override val sessionId: UUID = UUID.randomUUID()
    private lateinit var backend: ArCoreBackend
    private lateinit var controller: ArSessionController
    private lateinit var endpoint: ArControllerEndpoint
    private lateinit var renderer: ArCameraRenderer
    private lateinit var pool: ArFramePool
    override val depthSupported: Boolean get() = backend.depthSupported
    @Volatile private var closed = false
    private var rotationDegrees = 0
    private var outputSize: Pair<Int, Int>? = null
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            try {
                val snapshot = controller.capture()
                check(controller.state.value != ArSessionState.FAILED)
                if (snapshot != null) {
                    // Preserve raw image aspect ratio; no implicit 16:9 source cropping.
                    val width = snapshot.intrinsics.width
                    val height = snapshot.intrinsics.height
                    if (outputSize != width to height) {
                        source.adaptOutputFormat(width, height, 30)
                        outputSize = width to height
                    }
                    val buffer = pool.capture(width, height) {
                        backend.renderCamera(snapshot.frame, renderer, width, height)
                    }
                    if (buffer != null) {
                        val tagged = com.lazydoglab.zisee.ar.render.ArTextureBuffer(buffer,
                            com.lazydoglab.zisee.ar.render.ArFrameIdentity(sessionId, snapshot.frame))
                        val frame = VideoFrame(tagged, rotationDegrees, snapshot.frame.timestampNs)
                        try { source.capturerObserver.onFrameCaptured(frame) }
                        finally { frame.release() }
                    }
                }
                handler.postDelayed(this, 33)
            } catch (_: Exception) { onFailure() }
        }
    }

    suspend fun setGeometry(rotation: Int, width: Int, height: Int) {
        if (closed) return
        withContext(dispatcher) {
            if (!closed) {
                backend.setDisplayGeometry(rotation, width, height)
                rotationDegrees = backend.imageRotationDegrees()
            }
        }
    }

    override suspend fun execute(message: ArMessage) = endpoint.execute(message)

    override suspend fun close() {
        if (closed) return // The drained pool may already have shut down its HandlerThread.
        withContext(NonCancellable + dispatcher) {
            if (!closed) {
                closed = true
                handler.removeCallbacks(tick)
                try { source.capturerObserver.onCapturerStopped() }
                finally {
                    try { controller.close() }
                    finally { try { renderer.close() } finally { pool.close() } }
                }
            }
        }
    }

    companion object {
        /** Caller has prepared ARCore and acquired an exclusive camera lease. Consumes the lease. */
        suspend fun start(context: Context, shared: EglBase.Context, source: VideoSource,
            lease: ArCameraLease, rotation: Int, width: Int, height: Int,
            onState: (ArSessionState) -> Unit = {},
            onFailure: () -> Unit): ArVideoCapture = withContext(NonCancellable) {
            val helper = try { requireNotNull(SurfaceTextureHelper.create("ZiseeAr", shared)) }
                catch (error: Exception) { lease.close(); throw error }
            val capture = ArVideoCapture(helper, source, onFailure)
            withContext(capture.dispatcher) {
                try {
                    capture.backend = ArCoreBackend.create(context, lease)
                    capture.renderer = ArCameraRenderer()
                    capture.pool = ArFramePool(helper.handler) { helper.dispose() }
                    capture.controller = ArSessionController(capture.sessionId, { capture.backend },
                        onEvent = { onState(capture.controller.state.value) })
                    capture.backend.setDisplayGeometry(rotation, width, height)
                    check(capture.controller.start())
                    capture.rotationDegrees = capture.backend.imageRotationDegrees()
                    capture.endpoint = ArControllerEndpoint(capture.controller, capture.depthSupported, capture.dispatcher)
                    source.capturerObserver.onCapturerStarted(true)
                    helper.handler.post(capture.tick)
                    capture
                } catch (error: Exception) {
                    try { if (capture::backend.isInitialized) capture.backend.close() }
                    catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                    try { if (capture::renderer.isInitialized) capture.renderer.close() }
                    catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                    if (capture::pool.isInitialized) capture.pool.close() else helper.dispose()
                    throw error
                }
            }
        }
    }
}
