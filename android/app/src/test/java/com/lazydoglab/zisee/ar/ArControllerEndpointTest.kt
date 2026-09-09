package com.lazydoglab.zisee.ar

import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.ar.collaboration.ArControllerEndpoint
import com.lazydoglab.zisee.ar.session.*
import com.lazydoglab.zisee.ar.spatial.*
import com.lazydoglab.zisee.media.MediaTrack
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors

class ArControllerEndpointTest {
    @Test fun `commands and final cleanup execute on the AR owner and preserve frame identity`() = runBlocking {
        val gl = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val epoch = UUID.randomUUID()
        var detached = 0
        var closed = 0
        try {
            val endpoint = withContext(gl) {
                val owner = Thread.currentThread()
                val backend = object : ArBackend {
                    override val depthSupported = true
                    override fun resume() { assertSame(owner, Thread.currentThread()) }
                    override fun pause() { assertSame(owner, Thread.currentThread()) }
                    override fun capture() = HistoricalFrame(
                        VideoFrameReference(MediaTrack.BACK_CAMERA, 9_001), WorldPose(Vec3(0f, 0f, 0f)),
                        CameraIntrinsics(640, 480, 320f, 320f, 320f, 240f), ArTracking.TRACKING,
                        DepthSnapshot(1, 1, shortArrayOf(1000)))
                    override fun createAnchor(pose: WorldPose): LocalAnchor {
                        assertSame(owner, Thread.currentThread())
                        return object : LocalAnchor {
                            override val pose = pose
                            override val tracking = ArTracking.TRACKING
                            override fun detach() { assertSame(owner, Thread.currentThread()); detached++ }
                        }
                    }
                    override fun close() { assertSame(owner, Thread.currentThread()); closed++ }
                }
                val controller = ArSessionController(epoch, { backend })
                check(controller.start()); controller.capture()
                ArControllerEndpoint(controller, backend.depthSupported, gl)
            }
            val id = UUID.randomUUID()
            fun create(timestamp: Long) = ArMessage.Create(epoch, id, MarkerKind.CIRCLE,
                SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, timestamp), VideoPoint(.5f, .5f)))
            assertEquals(SpatialRejection.FRAME_MISSING, endpoint.execute(create(9_000))?.rejection)
            assertNull(endpoint.execute(create(9_001))?.rejection)
            endpoint.execute(ArMessage.Clear(epoch))
            assertEquals(1, detached)
            endpoint.close(); endpoint.close()
            assertEquals(1, closed)
        } finally { gl.close() }
    }
}
