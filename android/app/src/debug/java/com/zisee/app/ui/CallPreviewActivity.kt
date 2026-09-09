package com.zisee.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zisee.app.call.CallUiState
import com.zisee.app.call.state.CallPhase
import com.zisee.app.call.state.CallSession
import com.zisee.app.call.state.CallState
import com.zisee.app.rtc.CameraMode
import com.zisee.app.rtc.CameraPresentation
import com.zisee.app.rtc.MediaStats
import com.zisee.app.rtc.ShowMeState
import com.zisee.app.ui.theme.ZiseeTheme
import androidx.lifecycle.lifecycleScope
import com.zisee.app.rtc.VideoFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Non-exported debug-only layout fixture. No identity, network, camera, or microphone access. */
class CallPreviewActivity : ComponentActivity() {
    private var egl: EglBase? = null
    private var feeds = emptyList<VideoFeed>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "scene" is both ends showing their scene (four tiles), "mine" only this end (three).
        val scene = intent.getBooleanExtra("scene", false)
        val mine = intent.getBooleanExtra("mine", false)
        val localMode = if (scene || mine) CameraMode.DUAL else CameraMode.FACE
        val remoteMode = if (scene) CameraMode.DUAL else CameraMode.FACE
        // Synthetic patterns exercise real SurfaceView renderers without recording any media.
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        val shared = EglBase.create().also { egl = it }
        feeds = List(4) { index -> VideoFeed(shared.eglBaseContext, index == 0) }
        val rotations = listOf("localRotation", "remoteRotation", "localRotation", "remoteRotation")
            .map { intent.getIntExtra(it, 90).takeIf { angle -> angle in setOf(0, 90, 180, 270) } ?: 90 }
        lifecycleScope.launch(Dispatchers.Default) {
            val buffers = rotations.mapIndexed { index, rotation -> pattern(rotation, index) }
            try {
                while (isActive) {
                    feeds.forEachIndexed { index, feed ->
                        feed.onFrame(VideoFrame(buffers[index], rotations[index], System.nanoTime()))
                    }
                    delay(100)
                }
            } finally { buffers.forEach { it.release() } }
        }
        setContent {
            ZiseeTheme {
                // A non-IDLE phase requires a session, so the fixture supplies a complete one.
                ActiveCall(CallUiState(peerName = "林然", status = "通话中", stats = MediaStats(videoFrames = 1),
                    local = feeds[0], remote = feeds[1], localBack = feeds[2], remoteBack = feeds[3],
                    machine = CallState(CallPhase.CONNECTED, CallSession("preview", "peer")),
                    showMe = ShowMeState(localMode), remotePresentation = CameraPresentation(remoteMode, true),
                    showMeHint = scene), {}, {}, {}, {}, {}, { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        feeds.forEach { it.close() }
        egl?.release(); egl = null
    }

    private fun pattern(rotation: Int, index: Int): JavaI420Buffer {
        val width = 320; val height = 180
        val result = JavaI420Buffer.allocate(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            val (uprightX, uprightY) = when (rotation) {
                90 -> (height - 1 - y).toFloat() / height to x.toFloat() / width
                180 -> (width - 1 - x).toFloat() / width to (height - 1 - y).toFloat() / height
                270 -> y.toFloat() / height to (width - 1 - x).toFloat() / width
                else -> x.toFloat() / width to y.toFloat() / height
            }
            // An upright arrow plus a border makes rotation, mirroring and cropping visible.
            val arrow = (uprightY in 0.2f..0.7f && uprightX in 0.46f..0.54f) ||
                (uprightY in 0.1f..0.3f && kotlin.math.abs(uprightX - 0.5f) < uprightY - 0.1f)
            val border = uprightX < 0.03f || uprightX > 0.97f || uprightY < 0.03f || uprightY > 0.97f
            val marker = uprightX in 0.1f..0.2f && uprightY in 0.75f..0.85f
            result.dataY.put(y * result.strideY + x, (if (arrow || border || marker) 220 else 55 + index * 20).toByte())
        }
        for (y in 0 until height / 2) for (x in 0 until width / 2) {
            result.dataU.put(y * result.strideU + x, (105 + index * 12).toByte())
            result.dataV.put(y * result.strideV + x, (145 - index * 12).toByte())
        }
        return result
    }
}
