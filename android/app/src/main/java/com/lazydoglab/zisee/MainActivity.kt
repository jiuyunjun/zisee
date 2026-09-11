package com.lazydoglab.zisee

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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

    /** An invite carried by a notification tap, held until an identity exists to ring with. */
    private val ringing = MutableStateFlow<com.lazydoglab.zisee.push.CallInvite?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callPip = CallPictureInPicture(this)
        enableEdgeToEdge()
        opened.value = InviteLink.token(intent?.dataString)
        ringing.value = com.lazydoglab.zisee.push.CallNotifications.ringingInvite(intent)
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
            // §4.2: system authorization is a PendingExternalAction, not the user leaving the call.
            // startActivityForResult does not fire onUserLeaveHint, so no PiP entry is triggered
            // here; a cancelled or unavailable dialog leaves the ordinary call untouched.
            val shareConsent = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result -> callModel.onScreenShareConsent(result.resultCode, result.data) }
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            val save by viewModel.save.collectAsStateWithLifecycle()
            val connection by viewModel.connection.collectAsStateWithLifecycle()
            val call by callModel.state.collectAsStateWithLifecycle()
            val inPip by pipMode.collectAsStateWithLifecycle()
            val invite by opened.collectAsStateWithLifecycle()
            var minimized by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(call.busy) { if (!call.busy) minimized = false }
            // Ending the call from the PiP hang-up action only updates call state; the OS keeps
            // the floating PiP frame open until the Activity itself closes, so close it here.
            LaunchedEffect(call.busy, inPip) { if (!call.busy && inPip) finish() }
            // Home may enter PiP even for a local AR field: onPause ends that field while PiP
            // continues to show the peer. The in-app minimize action has a stricter AR guard.
            val sharing = call.shareConsent != null || call.screenShare.phase in setOf(
                com.lazydoglab.zisee.screen.ScreenSharePhase.STARTING,
                com.lazydoglab.zisee.screen.ScreenSharePhase.ACTIVE)
            val pipEligible = call.busy && call.local != null && !sharing
            val pipSource = com.lazydoglab.zisee.ui.CallVideoLayout.compactRemote(
                call.remotePresentation.mode, call.selectedVideoSource, call.remoteShare.sharing)
            val pipFeed = when (pipSource) {
                com.lazydoglab.zisee.ui.CallVideoLayout.PeerScreen -> call.remoteScreen
                com.lazydoglab.zisee.ui.CallVideoLayout.PeerScene ->
                    if (call.remotePresentation.mode in setOf(CameraMode.DUAL, CameraMode.AR)) call.remoteBack else call.remote
                else -> call.remote
            }
            val pipGeometry = pipFeed?.geometry?.collectAsStateWithLifecycle()?.value
            val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
            SideEffect {
                callPip.update(pipEligible, pipGeometry?.displayWidth ?: if (portrait) 9 else 16,
                    pipGeometry?.displayHeight ?: if (portrait) 16 else 9, call.muted)
            }
            LaunchedEffect(identity) {
                (identity as? IdentityState.Ready)?.let { callModel.observeIdentity(it.identity) }
            }
            val ring by ringing.collectAsStateWithLifecycle()
            // Rings immediately from the notification's own payload rather than waiting for the
            // idle poll to rediscover the same call over the network a few seconds later.
            LaunchedEffect(ring, identity) {
                val pending = ring ?: return@LaunchedEffect
                if (identity !is IdentityState.Ready) return@LaunchedEffect
                ringing.value = null
                callModel.ring(pending)
            }
            LaunchedEffect(call.shareConsent) {
                if (call.shareConsent == null) return@LaunchedEffect
                val manager = getSystemService(MediaProjectionManager::class.java)
                val launched = manager != null && runCatching {
                    shareConsent.launch(manager.createScreenCaptureIntent())
                }.isSuccess
                // A device without the projection service, or one that refuses the dialog, must
                // release the pending request rather than leave the share entry stuck.
                if (!launched) callModel.onScreenShareConsent(RESULT_CANCELED, null)
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
                contacts = call.contacts, contactsLoaded = call.contactsLoaded, pendingInvite = call.pendingInvite,
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
        com.lazydoglab.zisee.push.CallNotifications.ringingInvite(intent)?.let { ringing.value = it }
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
