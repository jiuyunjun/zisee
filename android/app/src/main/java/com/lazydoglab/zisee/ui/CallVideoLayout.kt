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

    fun sources(local: CameraMode, remote: CameraMode): List<String> = buildList {
        fun addCamera(mode: CameraMode, face: String, scene: String) {
            when (mode) {
                CameraMode.DUAL -> { add(scene); add(face) }
                CameraMode.BACK_ONLY, CameraMode.AR -> add(scene)
                else -> add(face)
            }
        }
        addCamera(local, MeFace, MeScene)
        addCamera(remote, PeerFace, PeerScene)
    }

    fun main(sources: List<String>, chosen: String?): String = chosen?.takeIf { it in sources }
        ?: sources.firstOrNull { it == MeScene }
        ?: sources.firstOrNull { it == PeerScene }
        ?: PeerFace

    fun remoteView(main: String, remote: CameraMode): ViewRequest {
        // BACK_ONLY uses the original single-camera/front track, not the concurrent back track.
        val frontLarge = main == PeerFace || (main == PeerScene && remote == CameraMode.BACK_ONLY)
        return ViewRequest(if (frontLarge) ViewSize.LARGE else ViewSize.SMALL,
            if (main == PeerScene && remote in setOf(CameraMode.DUAL, CameraMode.AR)) ViewSize.LARGE else ViewSize.SMALL)
    }

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
