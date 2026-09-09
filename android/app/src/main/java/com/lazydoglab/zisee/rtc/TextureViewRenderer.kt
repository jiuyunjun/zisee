package com.lazydoglab.zisee.rtc

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import java.util.concurrent.CountDownLatch
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
 */
class TextureViewRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val eglRenderer = EglRenderer("TextureViewRenderer(0x${Integer.toHexString(hashCode())})")

    init { surfaceTextureListener = this }

    fun init(sharedContext: EglBase.Context) {
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTexture?.let { eglRenderer.createEglSurface(it) }
    }

    fun setMirror(mirror: Boolean) = eglRenderer.setMirror(mirror)

    fun release() = eglRenderer.release()

    override fun onFrame(frame: VideoFrame) = eglRenderer.onFrame(frame)

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        eglRenderer.createEglSurface(surfaceTexture)
    }
    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    /** Must not let the platform release the SurfaceTexture before the render thread is done with it. */
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        val completion = CountDownLatch(1)
        eglRenderer.releaseEglSurface { completion.countDown() }
        try { completion.await() } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        return true
    }
}
