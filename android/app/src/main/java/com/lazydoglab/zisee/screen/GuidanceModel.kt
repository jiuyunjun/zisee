package com.lazydoglab.zisee.screen

import com.lazydoglab.zisee.ar.annotation.AnnotationAuthor
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import java.io.*

enum class GuidanceTool { POINTER, PEN, CIRCLE, ARROW, NUMBER }
enum class GuidanceOp { PUT, REMOVE, UNDO, CLEAR_OWN, CLEAR_ALL }
data class GuidanceInput(val session: String, val geometry: Int, val id: String, val tool: GuidanceTool, val points: List<VideoPoint>)
data class GuidanceMark(val id: String, val author: AnnotationAuthor, val tool: GuidanceTool,
    val points: List<VideoPoint>, val number: Int = 1)
data class GuidanceState(val session: String = "", val geometry: Int = 0, val width: Int = 0,
    val height: Int = 0, val paused: Boolean = false, val overlay: Boolean = false,
    val revision: Long = 0, val marks: List<GuidanceMark> = emptyList(), val connected: Boolean = false,
    val notice: String = "", val rotation: Int = 0)

/** FIELD is the sole authority. Actor identity comes from the authenticated channel endpoint. */
class GuidanceEngine {
    var state = GuidanceState(); private set
    private val seen = linkedSetOf<String>()
    fun restore(value: GuidanceState) { state = value; seen.clear() }
    fun configure(session: String, width: Int, height: Int, paused: Boolean, overlay: Boolean, rotation: Int = state.rotation): Boolean {
        val changed = state.session != session || state.width != width || state.height != height || state.rotation != rotation
        if (!changed && paused == state.paused && overlay == state.overlay) return false
        state = state.copy(session = session, width = width, height = height,
            geometry = if (changed) state.geometry + 1 else state.geometry,
            revision = state.revision + 1, paused = paused, overlay = overlay,
            marks = if (changed) emptyList() else state.marks, rotation = rotation)
        if (changed) seen.clear()
        return true
    }
    fun apply(packet: GuidancePacket, author: AnnotationAuthor): Boolean {
        if (state.session.isEmpty() || (state.paused && packet.op != GuidanceOp.REMOVE) || packet.session != state.session ||
            packet.geometry != state.geometry || !seen.add(packet.id)) return false
        while (seen.size > 256) seen.remove(seen.first())
        val marks = state.marks.toMutableList()
        when (packet.op) {
            GuidanceOp.PUT -> {
                val mark = packet.marks.singleOrNull() ?: return false
                if (mark.id.isEmpty() || mark.id.length > 64 || mark.points.size !in 1..128) return false
                val index = marks.indexOfFirst { it.id == mark.id }
                if (index >= 0 && marks[index].author != author) return false
                if (index < 0 && marks.size >= 32) return false
                val own = mark.copy(author = author, number = if (index >= 0) marks[index].number
                    else (marks.maxOfOrNull { it.number } ?: 0) + 1)
                if (index >= 0) marks[index] = own else marks.add(own)
            }
            GuidanceOp.REMOVE -> marks.removeAll { it.id == packet.target && it.author == author }
            GuidanceOp.UNDO -> marks.lastOrNull { it.author == author }?.let { marks.remove(it) }
            GuidanceOp.CLEAR_OWN -> marks.removeAll { it.author == author }
            GuidanceOp.CLEAR_ALL -> marks.clear()
        }
        state = state.copy(revision = state.revision + 1, marks = marks)
        return true
    }
}

/** Versioned, bounded binary wire format; no peer supplied class names or pixel coordinates. */
data class GuidancePacket(val kind: Int, val session: String = "", val geometry: Int = 0,
    val id: String = "", val op: GuidanceOp = GuidanceOp.PUT, val target: String = "",
    val marks: List<GuidanceMark> = emptyList(), val state: GuidanceState? = null,
    val author: AnnotationAuthor = AnnotationAuthor.GUIDE) {
    companion object { const val SYNC = 0; const val COMMAND = 1; const val ACK = 2; const val REQUEST = 3; const val REJECT = 4 }
}
object GuidanceWire {
    const val MAX_BYTES = 48_000
    fun encode(p: GuidancePacket): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x5A534701); out.writeByte(p.kind); out.writeUTF(p.session)
            out.writeInt(p.geometry); out.writeUTF(p.id); out.writeByte(p.op.ordinal); out.writeUTF(p.target); out.writeByte(p.author.ordinal)
            val state = p.state ?: GuidanceState()
            out.writeInt(state.width); out.writeInt(state.height); out.writeLong(state.revision)
            out.writeBoolean(state.paused); out.writeBoolean(state.overlay); out.writeInt(state.rotation)
            out.writeInt(p.marks.size)
            for (mark in p.marks) {
                out.writeUTF(mark.id); out.writeByte(mark.author.ordinal); out.writeByte(mark.tool.ordinal)
                out.writeInt(mark.number); out.writeInt(mark.points.size)
                mark.points.forEach { out.writeFloat(it.x); out.writeFloat(it.y) }
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    fun decode(bytes: ByteArray): GuidancePacket? = try {
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x5A534701)
            val kind = input.readUnsignedByte().also { require(it in 0..4) }
            fun id() = input.readUTF().also { require(it.length <= 64) }
            val session = id(); val geometry = input.readInt().also { require(it >= 0) }; val id = id()
            val op = GuidanceOp.entries[input.readUnsignedByte()]; val target = id()
            val actor = AnnotationAuthor.entries[input.readUnsignedByte()]
            val width = input.readInt(); val height = input.readInt(); val revision = input.readLong()
            require(width in 0..16_384 && height in 0..16_384 && revision >= 0)
            val paused = input.readBoolean(); val overlay = input.readBoolean()
            val rotation = input.readInt().also { require(it in 0..3) }
            val marks = List(input.readInt().also { require(it in 0..32) }) {
                val markId = id().also { require(it.isNotEmpty()) }
                val author = AnnotationAuthor.entries[input.readUnsignedByte()]
                val tool = GuidanceTool.entries[input.readUnsignedByte()]
                val number = input.readInt().also { require(it in 1..1_000_000) }
                val points = List(input.readInt().also { require(it in 1..128) }) { VideoPoint(input.readFloat(), input.readFloat()) }
                GuidanceMark(markId, author, tool, points, number)
            }
            require(input.available() == 0 && marks.map { it.id }.distinct().size == marks.size)
            GuidancePacket(kind, session, geometry, id, op, target, marks,
                GuidanceState(session, geometry, width, height, paused, overlay, revision, marks, rotation = rotation), actor)
        }
    } catch (_: Exception) { null }
}
