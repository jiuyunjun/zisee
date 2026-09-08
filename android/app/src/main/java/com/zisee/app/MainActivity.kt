package com.zisee.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.zisee.app.ui.ZiseeApp
import com.zisee.app.ui.ZiseeViewModel
import com.zisee.app.ui.theme.ZiseeTheme
import com.zisee.app.call.CallViewModel
import com.zisee.app.ui.CallScreen
import com.zisee.app.ui.IdentityState
import com.zisee.app.invite.InviteLink
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
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            val save by viewModel.save.collectAsStateWithLifecycle()
            val connection by viewModel.connection.collectAsStateWithLifecycle()
            val call by callModel.state.collectAsStateWithLifecycle()
            val invite by opened.collectAsStateWithLifecycle()
            // The call screen needs a ready identity, so a link that arrives first waits here
            // rather than being dropped.
            LaunchedEffect(invite, identity) {
                val token = invite ?: return@LaunchedEffect
                val ready = (identity as? IdentityState.Ready) ?: return@LaunchedEffect
                opened.value = null
                viewModel.disconnectBackend()
                callModel.open(ready.identity)
                callModel.prefill(token)
            }
            androidx.compose.runtime.DisposableEffect(call.busy) {
                if (call.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            ZiseeTheme {
                if (call.visible) CallScreen(call, callModel) else {
                    ZiseeApp(identity, save, viewModel::saveName, viewModel::load,
                        connection, viewModel::connectBackend, viewModel::disconnectBackend) {
                        (identity as? IdentityState.Ready)?.let {
                            viewModel.disconnectBackend()
                            callModel.open(it.identity)
                        }
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
        callModel.setForeground(false)
        viewModel.setForeground(false)
        super.onStop()
    }
}
