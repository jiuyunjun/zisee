package com.lazydoglab.zisee.screen

import com.lazydoglab.zisee.ar.annotation.AnnotationAuthor
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import java.io.*

enum class GuidanceTool { POINTER, PEN, CIRCLE, ARROW, NUMBER }
enum class GuidanceOp { PUT, REMOVE, UNDO, CLEAR_OWN, CLEAR_ALL }
enum class UiRole { UNKNOWN, BUTTON, CHECKBOX, SWITCH, RADIO_BUTTON, TEXT, EDIT_TEXT, IMAGE, LIST_ITEM }
enum class UiTargetLostReason { NO_ACCESSIBILITY_SERVICE, NO_ACCESSIBILITY_NODE, GEOMETRY_CHANGED, WINDOW_CHANGED }
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && left < right && top < bottom
}
data class SemanticTarget(val targetId: String, val windowId: Int, val role: UiRole,
    val bounds: NormalizedRect, val clickable: Boolean, val enabled: Boolean, val actionMask: Long,
    val confidence: Float, val treeRevision: Long)
data class GuidanceInput(val session: String, val geometry: Int, val id: String, val tool: GuidanceTool, val points: List<VideoPoint>)
data class GuidanceMark(val id: String, val author: AnnotationAuthor, val tool: GuidanceTool,
    val points: List<VideoPoint>, val number: Int = 1)
data class GuidanceState(val session: String = "", val geometry: Int = 0, val width: Int = 0,
    val height: Int = 0, val paused: Boolean = false, val overlay: Boolean = false,
    val revision: Long = 0, val marks: List<GuidanceMark> = emptyList(), val connected: Boolean = false,
    val notice: String = "", val rotation: Int = 0, val semanticAvailable: Boolean = false,
    val semanticTarget: SemanticTarget? = null)

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
            marks = if (changed) emptyList() else state.marks, rotation = rotation,
            semanticAvailable = state.semanticAvailable && session.isNotEmpty(),
            semanticTarget = if (changed) null else state.semanticTarget)
        if (changed) seen.clear()
        return true
    }
    fun setSemanticAvailability(available: Boolean): Boolean {
        if (state.semanticAvailable == available && (available || state.semanticTarget == null)) return false
        state = state.copy(semanticAvailable = available, semanticTarget = state.semanticTarget.takeIf { available })
        return true
    }
    fun setSemanticTarget(target: SemanticTarget?): Boolean {
        if (state.semanticTarget == target) return false
        state = state.copy(semanticTarget = target)
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

/** P0 packets retain v1 bytes; semantic-only packets use the bounded v2 extension. */
data class GuidancePacket(val kind: Int, val session: String = "", val geometry: Int = 0,
    val id: String = "", val op: GuidanceOp = GuidanceOp.PUT, val target: String = "",
    val marks: List<GuidanceMark> = emptyList(), val state: GuidanceState? = null,
    val author: AnnotationAuthor = AnnotationAuthor.GUIDE, val point: VideoPoint? = null,
    val semantic: SemanticTarget? = null, val lostReason: UiTargetLostReason? = null) {
    companion object {
        const val SYNC = 0; const val COMMAND = 1; const val ACK = 2; const val REQUEST = 3; const val REJECT = 4
        const val UI_TARGET_REQUEST = 5; const val UI_TARGET_RESOLVED = 6; const val UI_TARGET_UPDATE = 7
        const val UI_TARGET_LOST = 8; const val UI_HIGHLIGHT_CLEAR = 9; const val UI_CAPABILITY = 10
    }
}
object GuidanceWire {
    const val MAX_BYTES = 48_000
    fun encode(p: GuidancePacket): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(if (p.kind <= GuidancePacket.REJECT) 0x5A534701 else 0x5A534702)
            out.writeByte(p.kind); out.writeUTF(p.session)
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
            if (p.kind > GuidancePacket.REJECT) {
                out.writeBoolean(state.semanticAvailable)
                writePoint(out, p.point)
                writeSemantic(out, p.semantic ?: state.semanticTarget)
                out.writeByte(p.lostReason?.ordinal ?: -1)
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    fun decode(bytes: ByteArray): GuidancePacket? = try {
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = input.readInt().also { require(it == 0x5A534701 || it == 0x5A534702) }
            val kind = input.readUnsignedByte().also {
                require(if (magic == 0x5A534701) it in 0..4 else it in 5..10)
            }
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
            val semanticAvailable = if (magic == 0x5A534702) input.readBoolean() else false
            val point = if (magic == 0x5A534702) readPoint(input) else null
            val semantic = if (magic == 0x5A534702) readSemantic(input, ::id) else null
            val lostOrdinal = if (magic == 0x5A534702) input.readByte().toInt() else -1
            val lostReason = if (lostOrdinal < 0) null else UiTargetLostReason.entries[lostOrdinal]
            require(input.available() == 0 && marks.map { it.id }.distinct().size == marks.size)
            GuidancePacket(kind, session, geometry, id, op, target, marks,
                GuidanceState(session, geometry, width, height, paused, overlay, revision, marks, rotation = rotation,
                    semanticAvailable = semanticAvailable, semanticTarget = semantic), actor, point, semantic, lostReason)
        }
    } catch (_: Exception) { null }

    private fun writePoint(out: DataOutputStream, point: VideoPoint?) {
        out.writeBoolean(point != null)
        if (point != null) { out.writeFloat(point.x); out.writeFloat(point.y) }
    }

    private fun readPoint(input: DataInputStream): VideoPoint? = if (!input.readBoolean()) null else
        VideoPoint(input.readFloat(), input.readFloat()).also { require(it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f) }

    private fun writeSemantic(out: DataOutputStream, value: SemanticTarget?) {
        out.writeBoolean(value != null)
        if (value == null) return
        out.writeUTF(value.targetId); out.writeInt(value.windowId); out.writeByte(value.role.ordinal)
        out.writeFloat(value.bounds.left); out.writeFloat(value.bounds.top)
        out.writeFloat(value.bounds.right); out.writeFloat(value.bounds.bottom)
        out.writeBoolean(value.clickable); out.writeBoolean(value.enabled); out.writeLong(value.actionMask)
        out.writeFloat(value.confidence); out.writeLong(value.treeRevision)
    }

    private fun readSemantic(input: DataInputStream, id: () -> String): SemanticTarget? {
        if (!input.readBoolean()) return null
        val value = SemanticTarget(id().also { require(it.isNotEmpty()) }, input.readInt(),
            UiRole.entries[input.readUnsignedByte()],
            NormalizedRect(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()),
            input.readBoolean(), input.readBoolean(), input.readLong(), input.readFloat(), input.readLong())
        require(value.windowId >= -1 && value.bounds.valid() && value.confidence.isFinite() && value.confidence in 0f..1f && value.treeRevision >= 0)
        return value
    }
}
