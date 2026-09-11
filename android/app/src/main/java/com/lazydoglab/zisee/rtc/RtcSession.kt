package com.lazydoglab.zisee.rtc

import com.lazydoglab.zisee.media.MediaTrack
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class IceState { NEW, CHECKING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** One instance per call. Suspend methods must not block the Android main thread.
 * Implementations own PeerConnection and release tracks/renderers before release returns.
 * Default ICE policy gathers direct and relay candidates; never default to relay-only.
 */
interface RtcSession {
    val iceState: Flow<IceState>
    /** True only while this endpoint owns a live local AR capture; remote Stroke is negotiated separately. */
    val localArStrokeSupported: Flow<Boolean>
    suspend fun setTrackEnabled(track: MediaTrack, enabled: Boolean)
    suspend fun restartIce()
    suspend fun beginArStroke(identity: com.lazydoglab.zisee.ar.render.ArFrameIdentity, id: UUID,
        request: com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest): Boolean
    suspend fun appendArStroke(identity: com.lazydoglab.zisee.ar.render.ArFrameIdentity, id: UUID,
        requests: List<com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest>): Boolean
    suspend fun endArStroke(identity: com.lazydoglab.zisee.ar.render.ArFrameIdentity, id: UUID,
        cancel: Boolean): Boolean
    suspend fun release()
}
