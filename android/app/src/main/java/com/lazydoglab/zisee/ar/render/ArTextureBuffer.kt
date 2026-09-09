package com.lazydoglab.zisee.ar.render

import android.graphics.Matrix
import org.webrtc.VideoFrame

/** Owns the delegated reference. A crop invalidates raw-image identity; full-frame scaling keeps it. */
class ArTextureBuffer(private val texture: VideoFrame.TextureBuffer, val identity: ArFrameIdentity) :
    VideoFrame.TextureBuffer by texture {
    override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, outWidth: Int, outHeight: Int): VideoFrame.Buffer {
        val result = texture.cropAndScale(x, y, width, height, outWidth, outHeight)
        return if (x == 0 && y == 0 && width == texture.width && height == texture.height &&
            result is VideoFrame.TextureBuffer) ArTextureBuffer(result, identity) else result
    }
    override fun applyTransformMatrix(matrix: Matrix, width: Int, height: Int): VideoFrame.TextureBuffer =
        texture.applyTransformMatrix(matrix, width, height) // Unknown transform: explicitly discard identity.
}
