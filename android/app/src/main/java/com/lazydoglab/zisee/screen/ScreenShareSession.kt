package com.lazydoglab.zisee.screen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource

/**
 * The capture half of one call's screen sharing: a dedicated non-main owner thread, the single-use
 * consent and projection lifecycle, and the texture pipeline that feeds the screen [VideoSource].
 *
 * [ScreenShareController] and [AndroidScreenProjection] each demand one owner thread and the RTC
 * executor has no Looper, so this owns a [HandlerThread] and posts every controller call onto it.
 * The caller keeps the track: [onActive] is the only signal that frames really are flowing, and it
 * follows a real first frame rather than consent or VirtualDisplay creation.
 *
 * Consent data never leaves [start], and nothing is retained across attempts: another share needs a
 * new request and fresh system consent.
 */
class ScreenShareSession(
    private val context: Context,
    callId: String,
    private val eglContext: EglBase.Context,
    private val source: VideoSource,
    private val logger: AppLogger,
    private val onActive: (ScreenShareRequest, Boolean) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("ZiseeScreen").apply { start() }
    private val handler = Handler(thread.looper)
    private val mutableState = MutableStateFlow(ScreenShareState())
    val state = mutableState.asStateFlow()
    private lateinit var controller: ScreenShareController
    private val ready = CompletableDeferred<Unit>()
    private var texture: SurfaceTextureHelper? = null
    private var surface: Surface? = null
    private var capturing = false
    @Volatile private var closed = false

    init {
        handler.post {
            controller = ScreenShareController(callId) { event ->
                logger.info(AppEvent.SCREEN_SHARE_STATE, event.name)
                val current = controller.state.value
                mutableState.value = current
                when (event) {
                    ScreenShareEvent.FIRST_FRAME -> current.request?.let { onActive(it, true) }
                    ScreenShareEvent.STOPPED -> current.request?.let { onActive(it, false) }
                    ScreenShareEvent.FAILED -> {
                        logger.error(AppEvent.SCREEN_SHARE_FAILED, current.reason?.name)
                        current.request?.let { onActive(it, false) }
                    }
                    ScreenShareEvent.CLEANUP_FAILED -> logger.error(AppEvent.SCREEN_SHARE_FAILED, "CLEANUP")
                    else -> Unit
                }
            }
            ready.complete(Unit)
        }
    }

    /** The system consent dialog is only worth showing once this returns a request. */
    suspend fun request(): ScreenShareRequest? = onOwner {
        if (closed) null else runCatching { controller.request() }.getOrNull()
    }

    /**
     * [resultCode] and [data] are the untouched system consent result. The mediaProjection
     * foreground service must already be running: obtaining the projection without it throws, which
     * the controller reports as an ordinary start failure rather than taking the call down.
     */
    suspend fun start(request: ScreenShareRequest, resultCode: Int, data: Intent): Boolean = onOwner {
        if (closed) return@onOwner false
        val size = displaySize()
        val started = controller.start(request, size) { projection(request, resultCode, data, size) }
        // A share that never produces a frame must not sit in "preparing" for the rest of the call.
        if (started) handler.postDelayed({ if (!closed) controller.firstFrameTimeout(request) }, FIRST_FRAME_TIMEOUT_MS)
        else releaseCapture()
        started
    }

    suspend fun stop(reason: ScreenShareReason = ScreenShareReason.USER) {
        onOwner {
            if (!closed) controller.stop(reason)
            releaseCapture()
        }
    }

    /**
     * Blocks briefly on the owner thread. The RTC session disposes the borrowed [VideoSource] right
     * after this returns, so the capture pipeline has to be gone by then rather than merely queued;
     * and a projection must never outlive the call it belongs to. Never call this on the main
     * thread. A stuck owner thread is abandoned instead of hanging the caller.
     */
    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            runCatching { controller.close() }
            releaseCapture()
        }
        thread.quitSafely()
        runCatching { thread.join(CLOSE_TIMEOUT_MS) }
        if (thread.isAlive) logger.error(AppEvent.SCREEN_SHARE_FAILED, "CLOSE_TIMEOUT")
    }

    /** Builds the texture pipeline before the projection, so the borrowed Surface is already valid. */
    private fun projection(request: ScreenShareRequest, resultCode: Int, data: Intent, size: ScreenSize): ScreenProjection {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: throw IllegalStateException("screen_consent_unavailable")
        val helper = SurfaceTextureHelper.create("ZiseeScreenTexture", eglContext)
            ?: throw IllegalStateException("screen_capture_unavailable")
        texture = helper
        helper.setTextureSize(size.width, size.height)
        val output = Surface(helper.surfaceTexture)
        surface = output
        helper.startListening(object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                if (closed) return
                source.capturerObserver.onFrameCaptured(frame)
                // Frames arrive on the texture helper's thread; the controller owns this one.
                handler.post { if (!closed) controller.onFrame(request) }
            }
        })
        source.capturerObserver.onCapturerStarted(true)
        capturing = true
        // getMediaProjection throws without a running mediaProjection foreground service; let the
        // controller turn that into START_FAILED and release whatever is already built.
        val projection: MediaProjection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("screen_projection_unavailable")
        // Ownership only transfers once the backend exists. Failing after the projection is granted
        // but before anything owns it would otherwise leave the user's screen captured by nobody.
        return try {
            AndroidScreenProjection(projection, output, densityDpi(), handler) { resized ->
                helper.setTextureSize(resized.width, resized.height)
            }
        } catch (error: Throwable) {
            runCatching { projection.stop() }
            throw error
        }
    }

    /** Idempotent, and safe over a partially built pipeline: every step is attempted. */
    private fun releaseCapture() {
        if (capturing) {
            capturing = false
            runCatching { source.capturerObserver.onCapturerStopped() }
        }
        val oldTexture = texture
        val oldSurface = surface
        texture = null
        surface = null
        // The backend borrows the Surface, so releasing it is only safe once the controller has
        // closed the VirtualDisplay that was drawing into it.
        runCatching { oldTexture?.stopListening() }
        runCatching { oldSurface?.release() }
        runCatching { oldTexture?.dispose() }
    }

    private fun displaySize(): ScreenSize {
        val manager = context.getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) manager?.currentWindowMetrics?.bounds else null
        val width = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val height = bounds?.height() ?: context.resources.displayMetrics.heightPixels
        return ScreenCaptureSize.of(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    private fun densityDpi() = context.resources.displayMetrics.densityDpi.coerceAtLeast(1)

    private suspend fun <T> onOwner(block: () -> T): T {
        ready.await()
        val result = CompletableDeferred<Result<T>>()
        handler.post { result.complete(runCatching(block)) }
        return result.await().getOrThrow()
    }

    companion object {
        /** Long enough for a slow OEM to compose the first mirrored frame, short enough that a
         * share which will never start says so instead of hanging on "preparing". */
        const val FIRST_FRAME_TIMEOUT_MS = 8_000L
        /** Releasing a VirtualDisplay and its Surface is quick; waiting forever for one is not. */
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}
