package com.lazydoglab.zisee.ui

import android.app.Activity
import android.os.Bundle
import com.lazydoglab.zisee.rtc.TextureViewRenderer

/** Explicit non-exported instrumentation surface; no camera, network or account access. */
class ArVideoTestActivity : Activity() {
    lateinit var renderer: TextureViewRenderer
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderer = TextureViewRenderer(this)
        setContentView(renderer)
    }
}
