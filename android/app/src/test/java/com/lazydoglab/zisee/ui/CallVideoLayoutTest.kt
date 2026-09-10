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

    /** §5.1: a device sharing its whole screen must never render its own share back into it. */
    @Test fun `a share adds only the peer source and never a local one`() {
        for (local in CameraMode.entries) for (remote in CameraMode.entries) {
            val sources = CallVideoLayout.sources(local, remote, remoteSharing = true)
            assertEquals(sources.size, sources.toSet().size)
            assertTrue(CallVideoLayout.PeerScreen in sources)
            assertEquals(listOf(CallVideoLayout.PeerScreen), sources)
            assertTrue(sources.none { it.startsWith("me.") && it.endsWith("screen") })
            assertTrue(CallVideoLayout.PeerScreen !in CallVideoLayout.sources(local, remote))
        }
    }

    /** §5.1: a share is the one thing this device sends, so no local camera survives it. */
    @Test fun `sharing this screen removes every local camera from the layout`() {
        for (local in CameraMode.entries) for (remote in CameraMode.entries) for (peer in listOf(false, true)) {
            val sources = CallVideoLayout.sources(local, remote, peer, localSharing = true)
            assertTrue("kept a local source for $local", sources.none { it.startsWith("me.") })
            assertTrue(sources.isNotEmpty())
            assertEquals(sources.size, sources.toSet().size)
            // Nothing else changes: the peer's sources are exactly what they would otherwise be.
            assertEquals(CallVideoLayout.sources(local, remote, peer).filter { !it.startsWith("me.") }, sources)
            // A main view is still resolvable, and it is always the peer's.
            assertTrue(CallVideoLayout.main(sources, null).startsWith("peer."))
            assertTrue(CallVideoLayout.main(sources, CallVideoLayout.MeScene).startsWith("peer."))
        }
    }

    @Test fun `a new share becomes the main view but never overrides the viewer's own choice`() {
        for (local in CameraMode.entries) for (remote in CameraMode.entries) {
            val sources = CallVideoLayout.sources(local, remote, remoteSharing = true)
            assertEquals(CallVideoLayout.PeerScreen, CallVideoLayout.main(sources, null))
            for (chosen in sources) assertEquals(chosen, CallVideoLayout.main(sources, chosen))
            // A share that ended takes the main view with it rather than stranding the selection.
            val ended = CallVideoLayout.sources(local, remote)
            assertNotEquals(CallVideoLayout.PeerScreen, CallVideoLayout.main(ended, CallVideoLayout.PeerScreen))
        }
    }

    @Test fun `compact windows follow the peer's share over its cameras`() {
        for (remote in CameraMode.entries) {
            assertEquals(CallVideoLayout.PeerScreen,
                CallVideoLayout.compactRemote(remote, null, remoteSharing = true))
            // An explicit choice still wins, and a source that is not on offer is ignored.
            assertEquals(CallVideoLayout.PeerScreen,
                CallVideoLayout.compactRemote(remote, CallVideoLayout.MeFace, remoteSharing = true))
            assertNotEquals(CallVideoLayout.PeerScreen, CallVideoLayout.compactRemote(remote, null))
            assertNotEquals(CallVideoLayout.PeerScreen,
                CallVideoLayout.compactRemote(remote, CallVideoLayout.PeerScreen))
        }
        assertEquals(CallVideoLayout.PeerScreen,
            CallVideoLayout.compactRemote(CameraMode.DUAL, CallVideoLayout.PeerFace, remoteSharing = true))
    }

    @Test fun `main view stacks below every thumbnail whichever source is promoted`() {
        for (local in CameraMode.entries) for (remote in CameraMode.entries) for (sharing in listOf(false, true)) {
            val sources = CallVideoLayout.sources(local, remote, sharing)
            for (main in sources) {
                val thumbs = sources.filter { it != main }
                for (thumb in thumbs) {
                    assertTrue("$thumb must draw above the full-screen $main",
                        CallVideoLayout.stack(thumb, main) > CallVideoLayout.stack(main, main))
                }
                // Thumbnails hold disjoint slots, so none of them may outrank another.
                for (thumb in thumbs) assertEquals(CallVideoLayout.stack(thumbs.first(), main),
                    CallVideoLayout.stack(thumb, main), 0f)
            }
        }
    }

    /**
     * Renderers keep fixed call sites so a swap preserves node identity, which makes composition
     * order the source order rather than the stacking order. An explicit z index is then the only
     * thing keeping thumbnail pictures visible, and dropping it is invisible to every other test
     * here: the layout stays correct while the full-screen main view hides the thumbnails behind
     * it, leaving the frames and labels that are drawn afterwards. This has regressed repeatedly,
     * so assert the call surface still routes its tiles through the contract above.
     */
    @Test fun `call surface stacks its tiles through the layout contract`() {
        val source = java.io.File("src/main/java/com/lazydoglab/zisee/ui/ActiveCall.kt").readText()
        val slot = source.substringAfter("fun slot(tile: String").substringBefore("\n            fun ")
        assertEquals("Every branch of ActiveCall.slot must stack through CallVideoLayout.stack",
            3, Regex(Regex.escape(".zIndex(CallVideoLayout.stack(tile, main))")).findAll(slot).count())
        assertFalse("A tile modifier must not carry a hand-written z index",
            Regex("""\.zIndex\((?!CallVideoLayout\.stack)""").containsMatchIn(slot))
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
