package com.lazydoglab.zisee.ui

import com.lazydoglab.zisee.rtc.CameraMode
import com.lazydoglab.zisee.rtc.ViewRequest
import com.lazydoglab.zisee.rtc.ViewSize

/** Stable source identities, independent of device/window direction and which camera is primary. */
internal object CallVideoLayout {
    const val MeFace = "me.face"
    const val MeScene = "me.scene"
    const val PeerFace = "peer.face"
    const val PeerScene = "peer.scene"
    const val PeerScreen = "peer.screen"

    /**
     * There is deliberately no local screen source. §5.1: a device sharing its whole screen must not
     * play its own share back, or the capture re-enters the picture it is capturing. This end shows
     * that it is sharing in words instead.
     *
     * A share is the one thing this device sends, so while [localSharing] every local camera is
     * paused and drops out of the layout entirely: an empty placeholder where a picture used to be
     * reads as a fault rather than as the deliberate pause it is.
     */
    fun sources(local: CameraMode, remote: CameraMode, remoteSharing: Boolean = false,
        localSharing: Boolean = false): List<String> = buildList {
        fun addCamera(mode: CameraMode, face: String, scene: String) {
            when (mode) {
                CameraMode.DUAL -> { add(scene); add(face) }
                CameraMode.BACK_ONLY, CameraMode.AR -> add(scene)
                else -> add(face)
            }
        }
        if (remoteSharing) add(PeerScreen)
        if (!localSharing) addCamera(local, MeFace, MeScene)
        addCamera(remote, PeerFace, PeerScene)
    }

    /** §3.2: a share the peer has just started becomes the main view once, but never over a
     * selection the viewer made for themselves. */
    fun main(sources: List<String>, chosen: String?): String = chosen?.takeIf { it in sources }
        ?: sources.firstOrNull { it == PeerScreen }
        ?: sources.firstOrNull { it == MeScene }
        ?: sources.firstOrNull { it == PeerScene }
        ?: PeerFace

    /** Compact windows show exactly one remote source. A remote user selection wins; otherwise a
     * share the peer is sending outranks the cameras, then Show Me and rear-only calls prefer the
     * scene, and an ordinary call prefers the face.
     */
    fun compactRemote(remote: CameraMode, chosen: String?, remoteSharing: Boolean = false): String {
        val available = when (remote) {
            CameraMode.DUAL -> setOf(PeerScene, PeerFace)
            CameraMode.BACK_ONLY, CameraMode.AR -> setOf(PeerScene)
            else -> setOf(PeerFace)
        } + if (remoteSharing) setOf(PeerScreen) else emptySet()
        return chosen?.takeIf { it in available }
            ?: if (PeerScreen in available) PeerScreen
            else if (PeerScene in available) PeerScene else PeerFace
    }

    fun remoteView(main: String, remote: CameraMode): ViewRequest {
        // BACK_ONLY uses the original single-camera/front track, not the concurrent back track.
        val frontLarge = main == PeerFace || (main == PeerScene && remote == CameraMode.BACK_ONLY)
        return ViewRequest(if (frontLarge) ViewSize.LARGE else ViewSize.SMALL,
            if (main == PeerScene && remote in setOf(CameraMode.DUAL, CameraMode.AR)) ViewSize.LARGE else ViewSize.SMALL)
    }

    /** Stacking order for one tile, as a Compose z index.
     *
     * Compose draws siblings in composition order unless a z index overrides it, and each renderer
     * keeps a fixed call site so its node identity survives a swap. Composition order is therefore
     * the source order, not the stacking order: the main view fills the window and buries every
     * thumbnail declared before it, leaving only the frame and label that are drawn afterwards.
     * The main view is the backdrop; every thumbnail sits above it, and thumbnails never contend
     * with each other because [thumbnails] gives them disjoint slots.
     */
    fun stack(tile: String, main: String): Float = if (tile == main) -1f else 0f

    data class Tile(val x: Float, val y: Float, val width: Float, val height: Float)

    /** Dimensions are safe-area dp. FIT each source inside its slot, never crop the scene. */
    fun thumbnails(width: Float, height: Float, aspects: List<Float>, bottomInset: Float): List<Tile> {
        require(width > 0 && height > 0 && aspects.all { it.isFinite() && it > 0 })
        if (aspects.isEmpty()) return emptyList()
        val landscape = width > height
        val edge = minOf(16f, width / 10, height / 10)
        val gap = minOf(8f, width / (aspects.size * 4), height / (aspects.size * 4))
        val top = minOf(72f, height * 0.2f)
        val bottom = bottomInset.coerceIn(edge, (height - top - 48f).coerceAtLeast(edge))
        val usableWidth = (width - edge * 2).coerceAtLeast(1f)
        val usableHeight = (height - bottom - top).coerceAtLeast(1f)
        val slotWidth = if (landscape) minOf(144f, (usableWidth - gap * (aspects.size - 1)) / aspects.size)
            else minOf(104f, usableWidth * 0.32f)
        val slotHeight = if (landscape) minOf(100f, usableHeight)
            else minOf(148f, (usableHeight - gap * (aspects.size - 1)) / aspects.size)
        return aspects.mapIndexed { index, aspect ->
            val tileWidth = minOf(slotWidth, slotHeight * aspect)
            val tileHeight = tileWidth / aspect
            Tile(width - edge - (if (landscape) index * (slotWidth + gap) else 0f) - tileWidth,
                height - bottom - (if (landscape) 0f else index * (slotHeight + gap)) - tileHeight,
                tileWidth, tileHeight)
        }
    }
}
