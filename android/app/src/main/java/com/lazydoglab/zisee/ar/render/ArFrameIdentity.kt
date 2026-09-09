package com.lazydoglab.zisee.ar.render

import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.media.MediaTrack
import java.nio.ByteBuffer
import java.util.UUID

data class ArFrameIdentity(val sessionId: UUID, val reference: VideoFrameReference) {
    init { require(reference.track == MediaTrack.BACK_CAMERA && reference.timestampNs > 0) }
}

/** Annex-B H264 user_data_unregistered SEI. No coordinates or images travel in metadata. */
object ArFrameSei {
    private val magic = UUID.fromString("871c8aef-43dd-4617-a4a8-702d22b135b1")
    private const val PAYLOAD_SIZE = 41 // application UUID, version byte, session UUID, source ns
    private const val MAX_ACCESS_UNIT = 4 * 1024 * 1024

    fun prepend(frame: ByteBuffer, identity: ArFrameIdentity): ByteBuffer? {
        val input = frame.slice()
        if (input.remaining() !in 4..MAX_ACCESS_UNIT || startCode(input, 0) == 0) return null
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE + 3).put(5).put(PAYLOAD_SIZE.toByte())
            .putLong(magic.mostSignificantBits).putLong(magic.leastSignificantBits).put(1)
            .putLong(identity.sessionId.mostSignificantBits).putLong(identity.sessionId.leastSignificantBits)
            .putLong(identity.reference.timestampNs).put(0x80.toByte()).array()
        val escaped = ArrayList<Byte>()
        var zeros = 0
        for (byte in payload) {
            val value = byte.toInt() and 255
            if (zeros >= 2 && value <= 3) { escaped.add(3); zeros = 0 }
            escaped.add(byte)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return ByteBuffer.allocateDirect(5 + escaped.size + input.remaining()).apply {
            putInt(1); put(6); escaped.forEach { put(it) }; put(input); flip()
        }
    }

    /** Rejects duplicate application payloads and malformed SEI; never consults a latest identity. */
    fun read(frame: ByteBuffer): ArFrameIdentity? {
        val input = frame.slice()
        if (input.remaining() !in 4..MAX_ACCESS_UNIT || startCode(input, 0) == 0) return null
        var result: ArFrameIdentity? = null
        var offset = 0
        while (offset < input.limit()) {
            val prefix = startCode(input, offset)
            if (prefix == 0 || offset + prefix >= input.limit()) return null
            val header = offset + prefix
            var end = header + 1
            while (end < input.limit() && startCode(input, end) == 0) end++
            if ((input.get(header).toInt() and 31) == 6) {
                if (end - header > 4096) return null
                val bytes = ArrayList<Byte>()
                var zeros = 0
                var i = header + 1
                while (i < end) {
                    val value = input.get(i).toInt() and 255
                    if (zeros >= 2 && value == 3) {
                        if (i + 1 >= end || (input.get(i + 1).toInt() and 255) > 3) return null
                        zeros = 0; i++; continue
                    }
                    bytes.add(value.toByte()); zeros = if (value == 0) zeros + 1 else 0; i++
                }
                val data = ByteBuffer.wrap(bytes.toByteArray())
                while (data.hasRemaining()) {
                    if ((data.get(data.position()).toInt() and 255) == 128 && data.remaining() == 1) break
                    fun field(): Int? {
                        var value = 0
                        while (data.hasRemaining()) {
                            val part = data.get().toInt() and 255
                            value += part
                            if (value > 4096) return null
                            if (part != 255) return value
                        }
                        return null
                    }
                    val type = field() ?: return null
                    val size = field() ?: return null
                    if (size > data.remaining()) return null
                    val next = data.position() + size
                    if (type == 5 && size >= 16) {
                        val id = UUID(data.long, data.long)
                        if (id == magic) {
                            if (size != PAYLOAD_SIZE || result != null || data.get().toInt() != 1) return null
                            val session = UUID(data.long, data.long)
                            val timestamp = data.long
                            if (timestamp <= 0) return null
                            result = ArFrameIdentity(session, VideoFrameReference(MediaTrack.BACK_CAMERA, timestamp))
                        }
                    }
                    data.position(next)
                }
            }
            offset = end
        }
        return result
    }

    private fun startCode(data: ByteBuffer, i: Int): Int {
        if (i + 2 >= data.limit() || data.get(i).toInt() != 0 || data.get(i + 1).toInt() != 0) return 0
        if (data.get(i + 2).toInt() == 1) return 3
        return if (i + 3 < data.limit() && data.get(i + 2).toInt() == 0 && data.get(i + 3).toInt() == 1) 4 else 0
    }
}

/** Exact bounded correlation. Duplicate keys become unresolvable until eviction. */
class FrameIdentityIndex<T>(private val capacity: Int = 180) {
    private val entries = LinkedHashMap<Long, T?>()
    init { require(capacity in 1..300) }
    @Synchronized fun put(timestamp: Long, value: T?) {
        entries[timestamp] = if (entries.containsKey(timestamp)) null else value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun get(timestamp: Long): T? = entries[timestamp]
    @Synchronized fun take(timestamp: Long): T? = entries.remove(timestamp)
    @Synchronized fun clear() = entries.clear()
}
