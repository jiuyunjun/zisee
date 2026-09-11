package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.spatial.SpatialRejection
import com.lazydoglab.zisee.media.MediaTrack
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ArStrokeProtocolTest {
    private val session = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private fun request(timestamp: Long = 123) = SpatialMarkerRequest(
        VideoFrameReference(MediaTrack.BACK_CAMERA, timestamp), VideoPoint(.25f, .75f))

    @Test fun roundTripsEveryStrokeOperationAndBatch() {
        val messages = listOf(
            ArStrokeMessage.Hello,
            ArStrokeMessage.Begin(session, id, request()),
            ArStrokeMessage.Append(session, id, 0, listOf(request(124), request(125))),
            ArStrokeMessage.End(session, id, 1), ArStrokeMessage.Cancel(session, id),
            ArStrokeMessage.Result(session, id, null),
        ) + SpatialRejection.entries.map { ArStrokeMessage.Result(session, id, it) }
        messages.forEach { assertEquals(it, ArStrokeProtocol.decode(ArStrokeProtocol.encode(it))) }
    }

    @Test fun rejectsMalformedUnboundedAndNonCanonicalPeerInput() {
        val begin = JSONObject(String(ArStrokeProtocol.encode(ArStrokeMessage.Begin(session, id, request())), Charsets.UTF_8))
        fun decode(change: (JSONObject) -> Unit): ArStrokeMessage? = JSONObject(begin.toString()).also(change).let {
            ArStrokeProtocol.decode(it.toString().toByteArray(Charsets.UTF_8))
        }
        assertNull(decode { it.put("timestampNs", "0123") })
        assertNull(decode { it.put("track", "video_front") })
        assertNull(decode { it.put("x", 1.1) })
        assertNull(decode { it.put("extra", true) })
        assertNull(ArStrokeProtocol.decode(ByteArray(ArStrokeProtocol.MAX_BYTES + 1)))
        val append = JSONObject(String(ArStrokeProtocol.encode(
            ArStrokeMessage.Append(session, id, 0, listOf(request()))), Charsets.UTF_8))
        append.put("points", List(AnnotationBudget.MAX_BATCH_POINTS + 1) { "123,.2,.7" }.joinToString(";"))
        assertNull(ArStrokeProtocol.decode(append.toString().toByteArray(Charsets.UTF_8)))
        append.put("points", "123,NaN,.7")
        assertNull(ArStrokeProtocol.decode(append.toString().toByteArray(Charsets.UTF_8)))
    }
}
