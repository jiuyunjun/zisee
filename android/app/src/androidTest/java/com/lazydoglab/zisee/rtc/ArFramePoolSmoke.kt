package com.lazydoglab.zisee.rtc

import android.content.Context
import android.opengl.GLES20
import com.lazydoglab.zisee.ar.render.ArFramePool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper

internal object ArFramePoolSmoke {
    fun run(context: Context) = runBlocking {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val helper = requireNotNull(SurfaceTextureHelper.create("ArPoolTest", null))
        val dispatcher = helper.handler.asCoroutineDispatcher()
        val drained = CompletableDeferred<Unit>()
        withContext(dispatcher) {
            val pool = ArFramePool(helper.handler) { helper.dispose(); drained.complete(Unit) }
            fun capture(red: Float) = requireNotNull(pool.capture(32, 32) {
                GLES20.glClearColor(red, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                true
            })
            val first = capture(1f)
            val second = capture(0f)
            val third = capture(0f)
            check(pool.capture(32, 32) { error("Exhausted pool must not draw") } == null)
            val yuv = requireNotNull(first.toI420())
            try { check((yuv.dataY.get(16 * yuv.strideY + 16).toInt() and 255) > 60) }
            finally { yuv.release() }
            first.retain()
            pool.close()
            check(!drained.isCompleted)
            first.release(); second.release(); third.release()
            check(!drained.isCompleted)
            first.release()
        }
        withTimeout(5_000) { drained.await() }
    }
}
