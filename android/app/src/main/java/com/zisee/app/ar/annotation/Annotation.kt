package com.zisee.app.ar.annotation

import com.zisee.app.media.MediaTrack

data class VideoPoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) }
}

/** Source frame timestamp in nanoseconds, echoed unchanged by the viewer. Not wall-clock time.
 * Rendering adapters must invert rotation, crop and mirroring before producing VideoPoint.
 */
data class VideoFrameReference(val track: MediaTrack, val timestampNs: Long) {
    init {
        require(track != MediaTrack.MICROPHONE)
        require(timestampNs >= 0)
    }
}

data class Annotation2D(val frame: VideoFrameReference, val point: VideoPoint)

/** A request, not a world anchor. Resolve against historical pose/intrinsics/depth or reject.
 * Never substitute the current ARCore pose when the historical frame is missing.
 */
data class SpatialMarkerRequest(val frame: VideoFrameReference, val point: VideoPoint)
