package com.lazydoglab.zisee

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lazydoglab.zisee.ui.CallHomeActions
import com.lazydoglab.zisee.ui.ZiseeApp
import com.lazydoglab.zisee.ui.ZiseeViewModel
import com.lazydoglab.zisee.ui.theme.ZiseeTheme
import com.lazydoglab.zisee.call.CallViewModel
import com.lazydoglab.zisee.call.CallPictureInPicture
import com.lazydoglab.zisee.ui.CallScreen
import com.lazydoglab.zisee.ui.IdentityState
import com.lazydoglab.zisee.invite.InviteLink
import kotlinx.coroutines.flow.MutableStateFlow
import com.lazydoglab.zisee.rtc.CameraMode
import com.lazydoglab.zisee.ui.InAppMiniCall
import com.lazydoglab.zisee.ui.SystemPipCall
import androidx.compose.foundation.layout.Box

class MainActivity : ComponentActivity() {
    private val callModel: CallViewModel by lazy { (application as ZiseeApplication).container.callModel }
    private lateinit var callPip: CallPictureInPicture
    private val pipMode = MutableStateFlow(false)
    private val viewModel: ZiseeViewModel by viewModels {
        ZiseeViewModel.factory((application as ZiseeApplication).container)
    }

    /** An invite from a link, held until an identity exists to place the call with. */
    private val opened = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callPip = CallPictureInPicture(this)
        enableEdgeToEdge()
        opened.value = InviteLink.token(intent?.dataString)
        setContent {
            // Android 13+ gates notifications behind a runtime grant; without it a
            // woken process cannot show the incoming-call notification (CALL_DELIVERY.md §18).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            val save by viewModel.save.collectAsStateWithLifecycle()
            val connection by viewModel.connection.collectAsStateWithLifecycle()
            val call by callModel.state.collectAsStateWithLifecycle()
            val inPip by pipMode.collectAsStateWithLifecycle()
            val invite by opened.collectAsStateWithLifecycle()
            var minimized by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(call.busy) { if (!call.busy) minimized = false }
            // Home may enter PiP even for a local AR field: onPause ends that field while PiP
            // continues to show the peer. The in-app minimize action has a stricter AR guard.
            val pipEligible = call.busy && call.local != null
            SideEffect {
                callPip.update(pipEligible, call.stats.videoWidth, call.stats.videoHeight, call.muted)
            }
            LaunchedEffect(identity) {
                (identity as? IdentityState.Ready)?.let { callModel.observeIdentity(it.identity) }
            }
            // Placing the call needs a ready identity, so a link that arrives first waits here
            // rather than being dropped. It only fills the code in: the tap stays the user's.
            LaunchedEffect(invite, identity) {
                val token = invite ?: return@LaunchedEffect
                if (identity !is IdentityState.Ready) return@LaunchedEffect
                opened.value = null
                viewModel.disconnectBackend()
                callModel.prefill(token)
            }
            androidx.compose.runtime.DisposableEffect(call.busy) {
                if (call.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            // The home screen places calls itself, so the debug backend session has to be released
            // before the call model logs in: the server allows one session per device.
            fun release(action: () -> Unit): () -> Unit = { viewModel.disconnectBackend(); action() }
            val actions = CallHomeActions(
                activeCall = call.busy,
                contacts = call.contacts, pendingInvite = call.pendingInvite,
                notice = call.notice, contactsStatus = call.contactsStatus,
                onInvite = release(callModel::createInvite),
                onJoin = { code -> viewModel.disconnectBackend(); callModel.join(code) },
                onCallContact = { peer -> viewModel.disconnectBackend(); callModel.callContact(peer) },
                onRemoveContact = callModel::removeContact,
                onPermissionsDenied = callModel::permissionsDenied,
            )
            ZiseeTheme {
                when {
                    inPip && call.busy -> SystemPipCall(call)
                    call.visible && !minimized -> CallScreen(call, callModel) {
                        if (call.showMe.mode == CameraMode.AR) {
                            callModel.arNotice("请先结束我的 AR 现场，再最小化通话。")
                        } else minimized = true
                    }
                    else -> Box {
                        ZiseeApp(identity, save, viewModel::saveName, viewModel::load,
                            connection, viewModel::connectBackend, viewModel::disconnectBackend, actions)
                        if (minimized && call.busy) InAppMiniCall(call,
                            onRestore = { minimized = false }, onMute = callModel::toggleMute,
                            onEnd = callModel::stop)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        InviteLink.token(intent.dataString)?.let { opened.value = it }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        callModel.setForeground(true)
    }

    override fun onStop() {
        callModel.setForeground(false, isInPictureInPictureMode)
        viewModel.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        callModel.setArResumed(true)
    }

    override fun onPause() {
        callModel.setArResumed(false)
        super.onPause()
    }

    override fun onUserLeaveHint() {
        callPip.onUserLeaveHint()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,
        newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
        // Closing PiP pauses video but the process-scoped call and foreground audio continue.
        callModel.setVideoVisible(isInPictureInPictureMode || lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.STARTED))
    }
}
