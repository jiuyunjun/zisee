package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.media.MediaTrack

enum class ArTracking { TRACKING, PAUSED, STOPPED }

class HistoricalFrame(
    val frame: VideoFrameReference,
    val pose: WorldPose,
    val intrinsics: CameraIntrinsics,
    val tracking: ArTracking,
    val depth: DepthSnapshot? = null,
    planes: List<PlaneSnapshot> = emptyList(),
) {
    val planes: List<PlaneSnapshot> = java.util.Collections.unmodifiableList(planes.toList())
    init { require(frame.track == MediaTrack.BACK_CAMERA && frame.timestampNs > 0 && planes.size <= 16) }
}

/** Exact source-frame lookup. Never compare a remote wall clock or decoder timestamp to AR time.
 * Thread confined with its owning AR session. Age and count both bound memory (default <= 7 MB depth).
 */
class PoseHistory(private val maxAgeNs: Long = 3_000_000_000L, private val maxFrames: Int = 180) {
    private val frames = LinkedHashMap<Long, HistoricalFrame>()
    init { require(maxAgeNs in 1..5_000_000_000L && maxFrames in 1..300) }
    val size: Int get() = frames.size
    fun record(frame: HistoricalFrame): Boolean {
        val timestamp = frame.frame.timestampNs
        // Duplicate ARCore updates must not replace the metadata of an already published frame.
        if (frames.isNotEmpty() && timestamp <= frames.keys.last()) return false
        frames[timestamp] = frame
        val iterator = frames.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (timestamp - entry.key > maxAgeNs || frames.size > maxFrames) iterator.remove() else break
        }
        return true
    }
    fun find(frame: VideoFrameReference): HistoricalFrame? =
        frames[frame.timestampNs]?.takeIf { it.frame == frame }
    fun clear() = frames.clear()
}
