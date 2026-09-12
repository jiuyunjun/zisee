package com.lazydoglab.zisee.screen

import com.lazydoglab.zisee.ar.annotation.AnnotationAuthor.*
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import org.junit.Assert.*
import org.junit.Test

class GuidanceTest {
    private fun engine() = GuidanceEngine().apply { configure("share", 1080, 2400, false, true) }
    private fun packet(engine: GuidanceEngine, id: String, op: GuidanceOp = GuidanceOp.PUT,
        markId: String = id) = GuidancePacket(GuidancePacket.COMMAND, engine.state.session, engine.state.geometry,
        id, op, marks = if (op == GuidanceOp.PUT) listOf(GuidanceMark(markId, FIELD,
            GuidanceTool.PEN, listOf(VideoPoint(.1f, .2f), VideoPoint(.8f, .9f)))) else emptyList())

    @Test fun actorComesFromEndpointAndUndoDoesNotDeletePeersWork() {
        val e = engine()
        assertTrue(e.apply(packet(e, "a"), GUIDE)); assertTrue(e.apply(packet(e, "b"), FIELD))
        assertEquals(GUIDE, e.state.marks.first().author)
        assertFalse(e.apply(packet(e, "steal", markId = "a"), FIELD))
        assertTrue(e.apply(packet(e, "undo", GuidanceOp.UNDO), FIELD))
        assertEquals(listOf("a"), e.state.marks.map { it.id })
        assertTrue(e.apply(packet(e, "clear", GuidanceOp.CLEAR_OWN), FIELD))
        assertEquals(1, e.state.marks.size)
        assertTrue(e.apply(packet(e, "all", GuidanceOp.CLEAR_ALL), GUIDE)); assertTrue(e.state.marks.isEmpty())
    }
    @Test fun revisionsSessionsAndDuplicateOperationsFailClosed() {
        val e = engine(); val p = packet(e, "a")
        assertTrue(e.apply(p, GUIDE)); assertFalse(e.apply(p, GUIDE))
        assertFalse(e.apply(p.copy(id = "b", session = "old"), GUIDE))
        e.configure("share", 1080, 2400, false, true, 2) // 180-degree turn, same pixel size.
        assertTrue(e.state.marks.isEmpty()); assertFalse(e.apply(p.copy(id = "c"), GUIDE))
        e.configure("share", 1080, 2400, true, true)
        assertFalse(e.apply(packet(e, "paused"), GUIDE))
    }
    @Test fun updatesKeepNumbersAndBudgetIsBounded() {
        val e = engine()
        repeat(32) { assertTrue(e.apply(packet(e, "mark$it"), GUIDE)) }
        assertFalse(e.apply(packet(e, "overflow"), GUIDE))
        assertTrue(e.apply(packet(e, "append", markId = "mark0"), GUIDE))
        assertEquals(1, e.state.marks.first().number); assertEquals(32, e.state.marks.size)
    }
    @Test fun wireRoundTripsSnapshotAndRejectsTruncationTrailingBytesAndOversize() {
        val e = engine(); e.apply(packet(e, "a"), GUIDE)
        val p = GuidancePacket(GuidancePacket.SYNC, e.state.session, e.state.geometry, marks = e.state.marks, state = e.state)
        val bytes = GuidanceWire.encode(p)
        assertEquals(p, GuidanceWire.decode(bytes))
        assertNull(GuidanceWire.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(GuidanceWire.decode(bytes + 0.toByte()))
        assertNull(GuidanceWire.decode(ByteArray(GuidanceWire.MAX_BYTES + 1)))
    }
    @Test fun deltasProduceTheSameReplicaAsTheAuthority() {
        val authority = engine(); val replica = GuidanceEngine().apply { restore(authority.state) }
        for ((i, op) in listOf(GuidanceOp.PUT, GuidanceOp.PUT, GuidanceOp.UNDO, GuidanceOp.CLEAR_ALL).withIndex()) {
            val p = packet(authority, "op$i", op)
            val author = if (i % 2 == 0) GUIDE else FIELD
            assertTrue(authority.apply(p, author))
            val ack = p.copy(kind = GuidancePacket.ACK, author = author, state = authority.state,
                marks = if (op == GuidanceOp.PUT) authority.state.marks.filter { it.id == "op$i" } else emptyList())
            assertTrue(replica.apply(requireNotNull(GuidanceWire.decode(GuidanceWire.encode(ack))), author))
            assertEquals(authority.state, replica.state)
        }
    }
}
