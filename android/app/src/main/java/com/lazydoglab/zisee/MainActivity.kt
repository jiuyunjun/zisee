package com.lazydoglab.zisee

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.lazydoglab.zisee.ui.CallHomeActions
import com.lazydoglab.zisee.ui.ZiseeApp
import com.lazydoglab.zisee.ui.ZiseeViewModel
import com.lazydoglab.zisee.ui.theme.ZiseeTheme
import com.lazydoglab.zisee.call.CallViewModel
import com.lazydoglab.zisee.ui.CallScreen
import com.lazydoglab.zisee.ui.IdentityState
import com.lazydoglab.zisee.invite.InviteLink
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val callModel: CallViewModel by viewModels {
        CallViewModel.factory(applicationContext, (application as ZiseeApplication).container)
    }
    private val viewModel: ZiseeViewModel by viewModels {
        ZiseeViewModel.factory((application as ZiseeApplication).container)
    }

    /** An invite from a link, held until an identity exists to place the call with. */
    private val opened = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val invite by opened.collectAsStateWithLifecycle()
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
                contacts = call.contacts, pendingInvite = call.pendingInvite,
                notice = call.notice, contactsStatus = call.contactsStatus,
                onInvite = release(callModel::createInvite),
                onJoin = { code -> viewModel.disconnectBackend(); callModel.join(code) },
                onCallContact = { peer -> viewModel.disconnectBackend(); callModel.callContact(peer) },
                onRemoveContact = callModel::removeContact,
                onPermissionsDenied = callModel::permissionsDenied,
            )
            ZiseeTheme {
                if (call.visible) CallScreen(call, callModel)
                else ZiseeApp(identity, save, viewModel::saveName, viewModel::load,
                    connection, viewModel::connectBackend, viewModel::disconnectBackend, actions)
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
        callModel.setForeground(false)
        viewModel.setForeground(false)
        super.onStop()
    }
}
