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

/** Non-exported debug-only layout fixture. No identity, network, camera, or microphone access. */
class CallPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "scene" is both ends showing their scene (four tiles), "mine" only this end (three).
        val scene = intent.getBooleanExtra("scene", false)
        val mine = intent.getBooleanExtra("mine", false)
        val localMode = if (scene || mine) CameraMode.DUAL else CameraMode.FACE
        val remoteMode = if (scene) CameraMode.DUAL else CameraMode.FACE
        setContent {
            ZiseeTheme {
                // A non-IDLE phase requires a session, so the fixture supplies a complete one.
                ActiveCall(CallUiState(peerName = "林然", status = "通话中", stats = MediaStats(videoFrames = 1),
                    machine = CallState(CallPhase.CONNECTED, CallSession("preview", "peer")),
                    showMe = ShowMeState(localMode), remotePresentation = CameraPresentation(remoteMode, true),
                    showMeHint = scene), {}, {}, {}, {}, {}, { finish() })
            }
        }
    }
}
