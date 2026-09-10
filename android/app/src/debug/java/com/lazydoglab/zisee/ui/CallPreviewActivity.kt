package com.lazydoglab.zisee.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lazydoglab.zisee.rtc.CameraMode
import com.lazydoglab.zisee.ui.theme.ZiseeTheme
import androidx.lifecycle.lifecycleScope
import com.lazydoglab.zisee.rtc.VideoFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/**
 * Non-exported debug-only layout fixture. No identity, network, camera, or microphone access.
 *
 * Instrumentation launches it with the scenario in extras and screenshots it. `interactive` is the
 * entry from Settings: the same surface, with the scenario switches mounted in its own 通话选项
 * sheet so a tester can walk the call and AR states by hand on a real device.
 */
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
        val interactive = intent.getBooleanExtra("interactive", false)
        setContent {
            ZiseeTheme {
                CallUiPreview(feeds, PreviewScenario(localMode = localMode, remoteMode = remoteMode,
                    showMeHint = scene), interactive) { finish() }
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
