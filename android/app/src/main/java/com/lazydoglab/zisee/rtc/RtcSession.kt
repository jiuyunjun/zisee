package com.lazydoglab.zisee.rtc

import com.lazydoglab.zisee.media.MediaTrack
import kotlinx.coroutines.flow.Flow

enum class IceState { NEW, CHECKING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** One instance per call. Suspend methods must not block the Android main thread.
 * Implementations own PeerConnection and release tracks/renderers before release returns.
 * Default ICE policy gathers direct and relay candidates; never default to relay-only.
 */
interface RtcSession {
    val iceState: Flow<IceState>
    suspend fun setTrackEnabled(track: MediaTrack, enabled: Boolean)
    suspend fun restartIce()
    suspend fun release()
}
