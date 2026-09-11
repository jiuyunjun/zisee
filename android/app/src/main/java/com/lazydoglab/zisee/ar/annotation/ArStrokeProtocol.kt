package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.spatial.SpatialRejection
import com.lazydoglab.zisee.media.MediaTrack
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Optional v2 channel. Keeping strokes off v1 lets older peers continue point annotation safely. */
sealed interface ArStrokeMessage {
    val sessionId: UUID
    val id: UUID
    data object Hello : ArStrokeMessage {
        override val sessionId: UUID = UUID(0, 0)
        override val id: UUID = UUID(0, 0)
    }
    data class Begin(override val sessionId: UUID, override val id: UUID,
        val request: SpatialMarkerRequest) : ArStrokeMessage
    data class Append(override val sessionId: UUID, override val id: UUID, val sequence: Int,
        val requests: List<SpatialMarkerRequest>) : ArStrokeMessage
    data class End(override val sessionId: UUID, override val id: UUID, val sequence: Int) : ArStrokeMessage
    data class Cancel(override val sessionId: UUID, override val id: UUID) : ArStrokeMessage
    data class Result(override val sessionId: UUID, override val id: UUID,
        val rejection: SpatialRejection?) : ArStrokeMessage
}

object ArStrokeProtocol {
    const val CHANNEL_LABEL = "zisee-ar-v2-stroke"
    const val MAX_BYTES = AnnotationBudget.MAX_PACKET_BYTES

    fun encode(message: ArStrokeMessage): ByteArray {
        val json = JSONObject().put("v", 2)
        if (message !is ArStrokeMessage.Hello) json.put("sessionId", message.sessionId.toString())
            .put("id", message.id.toString())
        when (message) {
            ArStrokeMessage.Hello -> json.put("type", "hello")
            is ArStrokeMessage.Begin -> json.put("type", "begin").putRequest(message.request)
            is ArStrokeMessage.Append -> json.put("type", "append").put("seq", message.sequence)
                .put("points", encodeRequests(message.requests))
            is ArStrokeMessage.End -> json.put("type", "end").put("seq", message.sequence)
            is ArStrokeMessage.Cancel -> json.put("type", "cancel")
            is ArStrokeMessage.Result -> json.put("type", "result")
                .put("rejection", message.rejection?.name ?: JSONObject.NULL)
        }
        return json.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): ArStrokeMessage? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val tokenizer = org.json.JSONTokener(text)
            val json = tokenizer.nextValue() as? JSONObject ?: return null
            if (tokenizer.nextClean() != '\u0000' || json.get("v") != 2) return null
            fun keys(vararg fields: String) = require(json.keys().asSequence().toSet() ==
                setOf("v", "type", *fields))
            if (json.get("type") == "hello") { keys(); return ArStrokeMessage.Hello }
            val session = json.strictUuid("sessionId"); val id = json.strictUuid("id")
            fun messageKeys(vararg fields: String) = keys("sessionId", "id", *fields)
            when (json.get("type")) {
                "begin" -> { messageKeys("track", "timestampNs", "x", "y"); ArStrokeMessage.Begin(session, id, json.request()) }
                "append" -> {
                    messageKeys("seq", "points")
                    val sequence = json.strictSequence()
                    ArStrokeMessage.Append(session, id, sequence, decodeRequests(json.get("points") as String))
                }
                "end" -> { messageKeys("seq"); ArStrokeMessage.End(session, id, json.strictSequence()) }
                "cancel" -> { messageKeys(); ArStrokeMessage.Cancel(session, id) }
                "result" -> {
                    messageKeys("rejection"); val value = json.get("rejection")
                    ArStrokeMessage.Result(session, id,
                        if (value == JSONObject.NULL) null else SpatialRejection.valueOf(value as String))
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun JSONObject.putRequest(request: SpatialMarkerRequest): JSONObject {
        require(request.frame.track == MediaTrack.BACK_CAMERA && request.frame.timestampNs > 0)
        return put("track", request.frame.track.wireId).put("timestampNs", request.frame.timestampNs.toString())
            .put("x", request.point.x).put("y", request.point.y)
    }

    private fun JSONObject.request(): SpatialMarkerRequest {
        require(get("track") == MediaTrack.BACK_CAMERA.wireId)
        val timestamp = get("timestampNs") as String
        val ns = timestamp.toLong(); require(ns > 0 && ns.toString() == timestamp)
        val x = (get("x") as Number).toDouble(); val y = (get("y") as Number).toDouble()
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
        return SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, ns), VideoPoint(x.toFloat(), y.toFloat()))
    }

    /** Compact string keeps the JSON flat and bounded, avoiding recursive parsing of peer input. */
    private fun encodeRequests(requests: List<SpatialMarkerRequest>): String {
        require(requests.isNotEmpty() && requests.size <= AnnotationBudget.MAX_BATCH_POINTS)
        return requests.joinToString(";") { request ->
            require(request.frame.track == MediaTrack.BACK_CAMERA && request.frame.timestampNs > 0)
            "${request.frame.timestampNs},${request.point.x},${request.point.y}"
        }
    }

    private fun decodeRequests(value: String): List<SpatialMarkerRequest> {
        require(value.isNotEmpty())
        val rows = value.split(';'); require(rows.size <= AnnotationBudget.MAX_BATCH_POINTS)
        return rows.map { row ->
            val fields = row.split(','); require(fields.size == 3)
            val timestamp = fields[0].toLong(); require(timestamp > 0 && timestamp.toString() == fields[0])
            val x = fields[1].toFloat(); val y = fields[2].toFloat()
            require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
            SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, timestamp), VideoPoint(x, y))
        }
    }

    private fun JSONObject.strictUuid(key: String): UUID {
        val text = get(key) as String
        return UUID.fromString(text).also { require(it.toString() == text) }
    }
    private fun JSONObject.strictSequence(): Int = (get("seq") as Int).also {
        require(it in 0..AnnotationBudget.MAX_STROKE_POINTS)
    }
}
