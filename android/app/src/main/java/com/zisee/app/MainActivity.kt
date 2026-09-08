package com.zisee.app

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

class MainActivity : ComponentActivity() {
    private val callModel: CallViewModel by viewModels {
        CallViewModel.factory(applicationContext, (application as ZiseeApplication).container)
    }
    private val viewModel: ZiseeViewModel by viewModels {
        ZiseeViewModel.factory((application as ZiseeApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            val save by viewModel.save.collectAsStateWithLifecycle()
            val connection by viewModel.connection.collectAsStateWithLifecycle()
            val call by callModel.state.collectAsStateWithLifecycle()
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
