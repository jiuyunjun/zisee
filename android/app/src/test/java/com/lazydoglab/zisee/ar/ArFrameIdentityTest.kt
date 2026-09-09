package com.lazydoglab.zisee.ar

import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.ar.render.*
import com.lazydoglab.zisee.media.MediaTrack
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ArFrameIdentityTest {
    private fun identity(timestamp: Long) = ArFrameIdentity(UUID(0, 1), VideoFrameReference(MediaTrack.BACK_CAMERA, timestamp))
    private fun picture() = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0x34))
    @Test fun `source identity survives Annex B and emulation prevention without modifying input`() {
        for (ns in listOf(1L, 256L, 65536L, 0x100000001L, Long.MAX_VALUE)) {
            val input = picture()
            val encoded = requireNotNull(ArFrameSei.prepend(input, identity(ns)))
            assertEquals(0, input.position())
            assertEquals(identity(ns), ArFrameSei.read(encoded))
            assertEquals(0, encoded.position())
        }
    }
    @Test fun `missing truncated duplicate and unknown version metadata fail closed`() {
        assertNull(ArFrameSei.read(picture()))
        val encoded = requireNotNull(ArFrameSei.prepend(picture(), identity(99)))
        assertNull(ArFrameSei.read(requireNotNull(ArFrameSei.prepend(encoded, identity(100)))))
        for (limit in 0..20) assertNull(ArFrameSei.read(encoded.duplicate().apply { limit(limit) }))
        val unknown = encoded.duplicate()
        // UUID bytes contain no escape bytes; the version is immediately after the 16-byte UUID.
        unknown.put(23, 2)
        assertNull(ArFrameSei.read(unknown))
    }
    @Test fun `exact bounded lookup handles reordered frames drops duplicates and reset`() {
        val index = FrameIdentityIndex<String>(3)
        index.put(1, "a"); index.put(3, "c"); index.put(2, "b")
        assertEquals("b", index.take(2))
        assertNull(index.get(4))
        index.put(3, "wrong")
        assertNull(index.get(3))
        index.put(4, "d"); index.put(5, "e")
        assertNull(index.get(1))
        index.clear(); assertNull(index.get(5))
    }
}
