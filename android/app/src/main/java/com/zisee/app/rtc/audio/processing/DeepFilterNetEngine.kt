package com.zisee.app.rtc.audio.processing

import android.content.Context
import androidx.annotation.Keep
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stable JNI symbols; no asynchronous library-owned model loader or global native model. */
@Keep
internal object DeepFilterNative {
    init { System.loadLibrary("zisee_audio") }
    @JvmStatic external fun create(model: ByteArray): Long
    @JvmStatic external fun process(pointer: Long, buffer: ByteBuffer): Boolean
    @JvmStatic external fun release(pointer: Long)
}

class DeepFilterNetEngine(context: Context) : NoiseSuppressionEngine {
    private val silence = ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder())
    private var pointer = context.assets.open("audio/deepfilter-mobile.model").use {
        DeepFilterNative.create(it.readBytes())
    }.also { check(it != 0L) { "audio_model_unavailable" } }
    override fun process(buffer: ByteBuffer): Boolean {
        check(pointer != 0L)
        return DeepFilterNative.process(pointer, buffer)
    }
    override fun flush(): Boolean {
        repeat(20) {
            for (sample in 0 until 480) silence.putFloat(sample * 4, 0f)
            if (!process(silence)) return false
        }
        return true
    }
    override fun close() {
        if (pointer != 0L) { DeepFilterNative.release(pointer); pointer = 0 }
    }
}
