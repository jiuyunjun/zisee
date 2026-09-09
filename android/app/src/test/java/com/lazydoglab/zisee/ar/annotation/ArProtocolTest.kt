package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.session.MarkerKind
import com.lazydoglab.zisee.ar.spatial.SpatialRejection
import com.lazydoglab.zisee.media.MediaTrack
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ArProtocolTest {
    private val epoch = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private fun create() = ArMessage.Create(epoch, id, MarkerKind.PIN,
        SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, Long.MAX_VALUE), VideoPoint(0.25f, 1f)))
    private fun decodeJson(transform: (JSONObject) -> Unit): ArDecodeResult {
        val json = JSONObject(String(ArProtocol.encode(create()), Charsets.UTF_8))
        transform(json)
        return ArProtocol.decode(json.toString().toByteArray(Charsets.UTF_8))
    }

    @Test fun roundTripsAllMessagesWithoutLosing64BitTimestamp() {
        val messages = listOf(ArMessage.Ready(epoch, true), ArMessage.Ready(epoch, false), create(),
            create().copy(kind = MarkerKind.ARROW), create().copy(kind = MarkerKind.CIRCLE),
            ArMessage.Remove(epoch, id), ArMessage.Clear(epoch), ArMessage.Result(epoch, id, null)) +
            SpatialRejection.entries.map { ArMessage.Result(epoch, id, it) }
        messages.forEach { assertEquals(ArDecodeResult.Message(it), ArProtocol.decode(ArProtocol.encode(it))) }
    }

    @Test fun rejectsUntrustedTypesTracksIdsAndTimestampCoercion() {
        listOf<Pair<String, Any>>("v" to "1", "track" to "video_front", "timestampNs" to 12,
            "timestampNs" to "-1", "timestampNs" to "01", "timestampNs" to "9223372036854775808",
            "x" to "0.5", "x" to 1.00000001, "y" to -0.01, "id" to "1-1-1-1-1",
            "kind" to "MEASURE", "sessionId" to "", "extra" to true).forEach { (key, value) ->
            assertEquals("$key=$value", ArDecodeResult.Invalid, decodeJson { it.put(key, value) })
        }
        assertEquals(ArDecodeResult.Invalid, decodeJson { it.remove("timestampNs") })
        assertEquals(ArDecodeResult.UnsupportedVersion, decodeJson { it.put("v", 2) })
    }

    @Test fun rejectsMalformedOversizeAndTrailingPayloads() {
        assertEquals(ArDecodeResult.TooLarge, ArProtocol.decode(ByteArray(ArProtocol.MAX_BYTES + 1)))
        assertEquals(ArDecodeResult.Invalid, ArProtocol.decode(byteArrayOf(0xc3.toByte(), 0x28)))
        assertEquals(ArDecodeResult.Invalid, ArProtocol.decode("[]".toByteArray(Charsets.UTF_8)))
        assertEquals(ArDecodeResult.Invalid, ArProtocol.decode(ArProtocol.encode(create()) + " {}".toByteArray(Charsets.UTF_8)))
        assertEquals(ArDecodeResult.Invalid, ArProtocol.decode(byteArrayOf()))
        assertEquals(ArDecodeResult.Invalid, ArProtocol.decode(("{\"x\":".repeat(400) + "0" + "}".repeat(400)).toByteArray(Charsets.UTF_8)))
    }
}
