package com.lazydoglab.zisee.rtc

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * A [SurfaceViewRenderer][org.webrtc.SurfaceViewRenderer] punches a hole through the window; the
 * compositor draws its content independent of the normal view tree, so a Compose `clip()` (or any
 * View outline) on an ancestor never rounds it — the picture stays a hard rectangle inside a
 * rounded frame. A `TextureView` instead draws as an ordinary GPU-textured view during the normal
 * draw pass, so it clips and animates like any other Composable.
 *
 * Every call tile can become the main (unclipped) view or a rounded thumbnail as the user taps to
 * swap them, and the same renderer instance must survive that swap without recreating its surface
 * (recreating one flashes black — see [com.lazydoglab.zisee.ui.ActiveCall]'s "Fixed call sites" comment).
 * So every tile, not only thumbnails, renders through this class: a per-tile split between
 * SurfaceView and TextureView would force a surface recreation the moment a tile changes role.
 *
 * The tradeoff is TextureView's extra GPU composition pass versus SurfaceView's cheaper direct
 * scanout; unmeasured here per a full call, so a future power/thermal pass should confirm it holds
 * up at sustained high frame rates before this is taken as settled.
 *
 * Rounding has to happen here, through [cornerRadius], rather than through a Compose `clip()` on an
 * ancestor: an ancestor clip puts this view inside someone else's render layer, and a TextureView
 * drawn into a layer it does not own composites as nothing at all — the tile keeps its border and
 * label and the picture inside it goes transparent. The view's own outline clip stays on this
 * view's render node, which is the one its texture layer is attached to.
 */
class TextureViewRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val eglRenderer = EglRenderer("TextureViewRenderer(0x${Integer.toHexString(hashCode())})")
    data class DisplayedArFrame(val identity: com.lazydoglab.zisee.ar.render.ArFrameIdentity,
        val geometry: VideoGeometry, val mirrored: Boolean)
    private val submitted = com.lazydoglab.zisee.ar.render.FrameIdentityIndex<DisplayedArFrame>()
    private val displayed = kotlinx.coroutines.flow.MutableStateFlow<DisplayedArFrame?>(null)
    val displayedArFrame = displayed.asStateFlow()
    @Volatile private var mirror = false
    private val renderTokens = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

    init { surfaceTextureListener = this }

    /** Corner radius in pixels; 0 leaves the picture square. */
    var cornerRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            outlineProvider = if (value <= 0f) null else object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, value)
                }
            }
            clipToOutline = value > 0f
            invalidateOutline()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A main/thumbnail swap updates the radius before AndroidView receives its new bounds.
        // Rebuild the outline using the final bounds, including swaps with an unchanged radius.
        invalidateOutline()
    }

    fun init(sharedContext: EglBase.Context) {
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer(), true)
        surfaceTexture?.let { eglRenderer.createEglSurface(it) }
    }

    fun setMirror(mirror: Boolean) {
        if (this.mirror != mirror) { submitted.clear(); displayed.value = null }
        this.mirror = mirror
        eglRenderer.setMirror(mirror)
    }

    fun release() { submitted.clear(); displayed.value = null; eglRenderer.release() }

    override fun onFrame(frame: VideoFrame) = onIdentifiedFrame(frame, null)

    fun onIdentifiedFrame(frame: VideoFrame, identity: com.lazydoglab.zisee.ar.render.ArFrameIdentity?) {
        val token = renderTokens.updateAndGet { maxOf(it + 1, System.nanoTime()) }
        submitted.put(token, identity?.let {
            DisplayedArFrame(it, VideoGeometry(frame.buffer.width, frame.buffer.height, frame.rotation), mirror)
        })
        frame.buffer.retain()
        val output = VideoFrame(frame.buffer, frame.rotation, token)
        try { eglRenderer.onFrame(output) } finally { output.release() }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        eglRenderer.createEglSurface(surfaceTexture)
    }
    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // This timestamp belongs to the texture latched by Android, not the newest received frame.
        displayed.value = submitted.get(surfaceTexture.timestamp)
    }
    /** Must not let the platform release the SurfaceTexture before the render thread is done with it. */
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        submitted.clear(); displayed.value = null
        val completion = CountDownLatch(1)
        eglRenderer.releaseEglSurface { completion.countDown() }
        try { completion.await() } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        return true
    }
}
