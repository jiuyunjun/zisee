package com.zisee.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.zisee.app.ui.ZiseeApp
import com.zisee.app.ui.ZiseeViewModel
import com.zisee.app.ui.theme.ZiseeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ZiseeViewModel by viewModels {
        ZiseeViewModel.factory((application as ZiseeApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            val save by viewModel.save.collectAsStateWithLifecycle()
            ZiseeTheme {
                ZiseeApp(identity, save, viewModel::saveName, viewModel::load)
            }
        }
    }
}
