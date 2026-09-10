package com.lazydoglab.zisee.ar

import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.ar.collaboration.*
import com.lazydoglab.zisee.ar.session.MarkerKind
import com.lazydoglab.zisee.ar.spatial.SpatialRejection
import com.lazydoglab.zisee.media.MediaTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ArCollaborationTest {
    private val epoch = UUID.randomUUID()
    private fun request() = SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, 123), VideoPoint(.2f, .7f))
    private class Field(override val sessionId: UUID) : ArFieldEndpoint {
        override val depthSupported = true
        val commands = mutableListOf<ArMessage>()
        var closes = 0
        override suspend fun execute(message: ArMessage): ArMessage.Result? {
            commands.add(message)
            return (message as? ArMessage.Create)?.let { ArMessage.Result(sessionId, it.id, SpatialRejection.FRAME_MISSING) }
        }
        override suspend fun close() { closes++ }
    }

    @Test fun `ready does not permit commands until explicit join is acknowledged`() = runBlocking {
        val sent = mutableListOf<ArMessage>()
        val guide = ArCollaboration { sent.add(it) }
        guide.connected()
        guide.receive(ArMessage.Ready(epoch, true))
        assertFalse(guide.create(UUID.randomUUID(), MarkerKind.PIN, request()))
        assertFalse(guide.join(UUID.randomUUID()))
        assertTrue(guide.join(epoch))
        assertFalse(guide.clear())
        guide.receive(ArMessage.Joined(UUID.randomUUID()))
        assertFalse(guide.state.value.joined)
        guide.receive(ArMessage.Joined(epoch))
        assertTrue(guide.create(UUID.randomUUID(), MarkerKind.PIN, request()))
        assertEquals(1, guide.state.value.pendingMarkers)
        guide.close()
        assertFalse(guide.state.value.connected)
        assertFalse(guide.clear())
    }

    @Test fun `field ignores unsolicited mutations and stale sessions`() = runBlocking {
        val sent = mutableListOf<ArMessage>()
        val field = Field(epoch)
        val local = ArCollaboration { sent.add(it) }
        assertTrue(local.attach(field))
        assertTrue(sent.isEmpty())
        local.connected()
        assertEquals(ArMessage.Ready(epoch, true), sent.single())
        val create = ArMessage.Create(epoch, UUID.randomUUID(), MarkerKind.PIN, request())
        local.receive(create)
        local.receive(ArMessage.Join(UUID.randomUUID()))
        local.receive(create)
        assertTrue(field.commands.isEmpty())
        local.receive(ArMessage.Join(epoch))
        local.receive(create.copy(sessionId = UUID.randomUUID()))
        local.receive(create)
        assertEquals(listOf(create), field.commands)
        assertEquals(ArMessage.Result(epoch, create.id, SpatialRejection.FRAME_MISSING), sent.last())
        local.receive(ArMessage.Leave(epoch))
        local.receive(ArMessage.Clear(epoch))
        assertEquals(1, field.commands.size)
        local.detach()
        assertEquals(1, field.closes)
        assertEquals(ArMessage.Ended(epoch), sent.last())
        assertFalse(local.attach(Field(epoch)))
        local.close()
        assertEquals(1, field.closes)
    }

    @Test fun `new epoch and ended invalidate joins outstanding results and replayed offers`() = runBlocking {
        val guide = ArCollaboration { true }
        guide.connected()
        guide.receive(ArMessage.Ready(epoch, true))
        guide.join(epoch); guide.receive(ArMessage.Joined(epoch))
        val id = UUID.randomUUID()
        guide.create(id, MarkerKind.ARROW, request())
        guide.receive(ArMessage.Result(UUID.randomUUID(), id, null))
        guide.receive(ArMessage.Result(epoch, UUID.randomUUID(), null))
        assertEquals(1, guide.state.value.pendingMarkers)
        val next = UUID.randomUUID()
        guide.receive(ArMessage.Ready(next, false))
        guide.receive(ArMessage.Joined(epoch))
        guide.receive(ArMessage.Result(epoch, id, null))
        guide.receive(ArMessage.Ready(epoch, true))
        assertEquals(next, guide.state.value.remote?.sessionId)
        assertFalse(guide.state.value.joined)
        assertEquals(0, guide.state.value.pendingMarkers)
        assertNull(guide.state.value.lastResult)
        guide.receive(ArMessage.Ended(next))
        guide.receive(ArMessage.Ready(next, false))
        assertNull(guide.state.value.remote)
    }

    @Test fun `backpressure does not grant joins transfer ownership or queue phantom markers`() = runBlocking {
        var writable = false
        val local = ArCollaboration { writable }
        local.connected()
        val field = Field(epoch)
        assertFalse(local.attach(field))
        assertEquals(0, field.closes)
        local.receive(ArMessage.Ready(epoch, true))
        assertFalse(local.join(epoch))
        local.receive(ArMessage.Joined(epoch))
        assertFalse(local.state.value.joined)
        writable = true
        local.join(epoch); local.receive(ArMessage.Joined(epoch))
        writable = false
        assertFalse(local.create(UUID.randomUUID(), MarkerKind.PIN, request()))
        assertEquals(0, local.state.value.pendingMarkers)
    }

    @Test fun `outstanding requests are bounded and results are correlated once`() = runBlocking {
        val guide = ArCollaboration { true }
        guide.connected(); guide.receive(ArMessage.Ready(epoch, true))
        guide.join(epoch); guide.receive(ArMessage.Joined(epoch))
        val ids = List(16) { UUID.randomUUID() }
        ids.forEach { assertTrue(guide.create(it, MarkerKind.PIN, request())) }
        assertFalse(guide.create(ids.first(), MarkerKind.PIN, request()))
        assertFalse(guide.create(UUID.randomUUID(), MarkerKind.PIN, request()))
        guide.receive(ArMessage.Result(epoch, ids.first(), null))
        assertEquals(15, guide.state.value.pendingMarkers)
        guide.receive(ArMessage.Result(epoch, ids.first(), SpatialRejection.INACTIVE))
        assertNull(guide.state.value.lastResult?.rejection)
        guide.leave()
        guide.receive(ArMessage.Joined(epoch))
        assertFalse(guide.state.value.joined)
        assertEquals(0, guide.state.value.pendingMarkers)
    }

    @Test fun `hangup closes attached field once without requiring open transport`() = runBlocking {
        val field = Field(epoch)
        val local = ArCollaboration { false }
        assertTrue(local.attach(field))
        local.close(); local.close()
        assertEquals(1, field.closes)
        assertFalse(local.attach(Field(UUID.randomUUID())))
    }

    @Test fun `simultaneous fields keep the call coordinators scene`() = runBlocking {
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val high = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val lowSide = ArCollaboration(true) { true }
        val highSide = ArCollaboration(false) { true }
        lowSide.connected(); highSide.connected()
        assertTrue(lowSide.attach(Field(low)))
        assertTrue(highSide.attach(Field(high)))
        lowSide.receive(ArMessage.Ready(high, true))
        highSide.receive(ArMessage.Ready(low, true))
        assertNull(lowSide.state.value.remote)
        assertFalse(lowSide.state.value.ownershipLost)
        assertEquals(low, highSide.state.value.remote?.sessionId)
        assertTrue(highSide.state.value.ownershipLost)
        lowSide.close(); highSide.close()
    }

    @Test fun `non coordinator cannot attach over a remote scene`() = runBlocking {
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val high = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val collaboration = ArCollaboration(false) { true }
        collaboration.connected()
        collaboration.receive(ArMessage.Ready(low, true))
        assertFalse(collaboration.attach(Field(high)))
        assertEquals(low, collaboration.state.value.remote?.sessionId)
        collaboration.close()
    }

    @Test fun `session lifecycle messages round trip and reject extra fields`() {
        listOf(ArMessage.Join(epoch), ArMessage.Joined(epoch), ArMessage.Leave(epoch), ArMessage.Ended(epoch)).forEach {
            assertEquals(ArDecodeResult.Message(it), ArProtocol.decode(ArProtocol.encode(it)))
            val extra = String(ArProtocol.encode(it), Charsets.UTF_8).dropLast(1) + ",\"unexpected\":true}"
            assertEquals(ArDecodeResult.Invalid, ArProtocol.decode(extra.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test fun `receive work is bounded across bursts and backwards clock samples`() {
        val budget = ArReceiveBudget()
        repeat(30) { assertTrue(budget.accept(1_000)) }
        assertFalse(budget.accept(1_000))
        assertFalse(budget.accept(900))
        assertTrue(budget.accept(1_034))
        assertFalse(budget.accept(1_034))
        repeat(30) { assertTrue(budget.accept(3_000)) }
        assertFalse(budget.accept(3_000))
    }
}
