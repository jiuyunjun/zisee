package com.lazydoglab.zisee.ui

import com.lazydoglab.zisee.rtc.CameraMode
import com.lazydoglab.zisee.rtc.VideoGeometry
import com.lazydoglab.zisee.rtc.ViewSize
import org.junit.Assert.*
import org.junit.Test

class CallVideoLayoutTest {
    @Test fun `compact window always resolves one remote source`() {
        assertEquals(CallVideoLayout.PeerScene,
            CallVideoLayout.compactRemote(CameraMode.DUAL, CallVideoLayout.MeFace))
        assertEquals(CallVideoLayout.PeerFace,
            CallVideoLayout.compactRemote(CameraMode.DUAL, CallVideoLayout.PeerFace))
        assertEquals(CallVideoLayout.PeerScene,
            CallVideoLayout.compactRemote(CameraMode.BACK_ONLY, CallVideoLayout.PeerFace))
        assertEquals(CallVideoLayout.PeerFace,
            CallVideoLayout.compactRemote(CameraMode.FACE, CallVideoLayout.PeerScene))
    }

    @Test fun `all local and remote show me combinations resolve available primary and thumbnail tracks`() {
        for (local in CameraMode.entries) for (remote in CameraMode.entries) {
            val sources = CallVideoLayout.sources(local, remote)
            assertEquals(sources.size, sources.toSet().size)
            assertTrue(sources.size in 2..4)
            val expected = when {
                local in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY, CameraMode.AR) -> CallVideoLayout.MeScene
                remote in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY, CameraMode.AR) -> CallVideoLayout.PeerScene
                else -> CallVideoLayout.PeerFace
            }
            assertEquals(expected, CallVideoLayout.main(sources, null))
            assertEquals(expected, CallVideoLayout.main(sources, "removed"))
            for (selected in sources) assertEquals(selected, CallVideoLayout.main(sources, selected))
        }
    }

    @Test fun `default remote primary retains high quality including single back camera fallback`() {
        for (remote in listOf(CameraMode.FACE, CameraMode.DUAL, CameraMode.BACK_ONLY)) {
            val main = CallVideoLayout.main(CallVideoLayout.sources(CameraMode.FACE, remote), null)
            val request = CallVideoLayout.remoteView(main, remote)
            assertEquals(if (remote == CameraMode.DUAL) ViewSize.SMALL else ViewSize.LARGE, request.front)
            assertEquals(if (remote == CameraMode.DUAL) ViewSize.LARGE else ViewSize.SMALL, request.back)
        }
        val face = CallVideoLayout.remoteView(CallVideoLayout.PeerFace, CameraMode.DUAL)
        assertEquals(ViewSize.LARGE, face.front)
        assertEquals(ViewSize.SMALL, face.back)
        val local = CallVideoLayout.remoteView(CallVideoLayout.MeScene, CameraMode.DUAL)
        assertEquals(ViewSize.SMALL, local.front)
        assertEquals(ViewSize.SMALL, local.back)
    }

    @Test fun `sender receiver direction matrix preserves ratios and keeps up to three thumbnails in bounds`() {
        for ((width, height) in listOf(360f to 760f, 760f to 360f, 320f to 240f, 240f to 320f, 800f to 800f)) {
            for (localRotation in listOf(0, 90, 180, 270)) for (remoteRotation in listOf(0, 90, 180, 270)) {
                val local = VideoGeometry(1280, 720, localRotation).aspectRatio
                val remote = VideoGeometry(1920, 1080, remoteRotation).aspectRatio
                for (count in 1..3) {
                    val aspects = listOf(local, remote, remote).take(count)
                    val tiles = CallVideoLayout.thumbnails(width, height, aspects, 160f)
                    tiles.forEachIndexed { index, tile ->
                        assertTrue(tile.width > 0 && tile.height > 0)
                        assertTrue(tile.x >= 0 && tile.y >= 0)
                        assertTrue(tile.x + tile.width <= width + 0.01f)
                        assertTrue(tile.y + tile.height <= height + 0.01f)
                        assertEquals(aspects[index], tile.width / tile.height, 0.0001f)
                    }
                    for (a in tiles.indices) for (b in 0 until a) {
                        val x = tiles[a]; val y = tiles[b]
                        assertTrue(x.x + x.width <= y.x || y.x + y.width <= x.x ||
                            x.y + x.height <= y.y || y.y + y.height <= x.y)
                    }
                }
            }
        }
    }

    @Test fun `portrait stacks vertically and landscape arranges horizontally`() {
        val portrait = CallVideoLayout.thumbnails(360f, 760f, List(3) { 9f / 16 }, 120f)
        assertEquals(portrait[0].x, portrait[1].x, 0.001f)
        assertTrue(portrait[0].y > portrait[1].y)
        val landscape = CallVideoLayout.thumbnails(760f, 360f, List(3) { 16f / 9 }, 120f)
        assertEquals(landscape[0].y, landscape[1].y, 0.001f)
        assertTrue(landscape[0].x > landscape[1].x)
    }
}
