package com.lazydoglab.zisee.screen

import com.lazydoglab.zisee.ar.annotation.VideoPoint
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.hypot

/** Immutable accessibility snapshot. AccessibilityNodeInfo objects never leave the service callback. */
data class SemanticNode(
    val windowId: Int,
    val parentIndex: Int?,
    val className: String?,
    val viewIdResourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: NormalizedRect,
    val visible: Boolean,
    val clickable: Boolean,
    val checkable: Boolean,
    val focusable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val editable: Boolean,
    val important: Boolean,
    val actionMask: Long,
)

data class NodeLocator(
    val windowId: Int,
    val viewIdResourceName: String?,
    val className: String?,
    val textHash: String?,
    val contentDescriptionHash: String?,
    val hierarchyHint: IntArray,
    val lastBounds: NormalizedRect,
)

data class ResolvedSemanticTarget(val target: SemanticTarget, val locator: NodeLocator)

/** Hit testing and relocation are pure so OEM accessibility trees can be covered by JVM tests. */
object UiSemanticResolver {
    fun resolve(nodes: List<SemanticNode>, point: VideoPoint, treeRevision: Long): ResolvedSemanticTarget? {
        val candidates = nodes.indices.filter { index ->
            val node = nodes[index]
            node.visible && node.bounds.valid() && contains(node.bounds, point) && area(node.bounds) < .98f
        }
        if (candidates.isEmpty()) return null
        val initial = candidates.maxByOrNull { score(nodes[it], point) } ?: return null
        val promoted = ancestors(nodes, initial).firstOrNull { actionable(nodes[it]) } ?: initial
        return result(nodes, promoted, treeRevision, confidence(nodes[promoted], point))
    }

    fun relocate(nodes: List<SemanticNode>, locator: NodeLocator, treeRevision: Long): ResolvedSemanticTarget? {
        val inWindow = nodes.indices.filter { nodes[it].windowId == locator.windowId && nodes[it].visible && nodes[it].bounds.valid() }
        val byId = locator.viewIdResourceName?.let { id -> inWindow.filter { nodes[it].viewIdResourceName == id } }.orEmpty()
        val semantic = inWindow.filter { index ->
            val node = nodes[index]
            node.className == locator.className &&
                (locator.textHash == null || safeHash(node.text, node.password || node.editable) == locator.textHash) &&
                (locator.contentDescriptionHash == null || safeHash(node.contentDescription, node.password || node.editable) == locator.contentDescriptionHash)
        }
        val pool = when { byId.isNotEmpty() -> byId; semantic.isNotEmpty() -> semantic; else -> inWindow.filter { nodes[it].className == locator.className } }
        val index = pool.minByOrNull { distance(nodes[it].bounds, locator.lastBounds) } ?: return null
        return result(nodes, index, treeRevision, if (byId.isNotEmpty()) .98f else .82f)
    }

    private fun result(nodes: List<SemanticNode>, index: Int, revision: Long, confidence: Float): ResolvedSemanticTarget {
        val node = nodes[index]
        val sensitive = node.password || node.editable
        return ResolvedSemanticTarget(
            SemanticTarget(UUID.randomUUID().toString(), node.windowId, role(node), node.bounds,
                node.clickable, node.enabled, node.actionMask, confidence.coerceIn(0f, 1f), revision),
            NodeLocator(node.windowId, node.viewIdResourceName, node.className,
                safeHash(node.text, sensitive), safeHash(node.contentDescription, sensitive), hierarchy(nodes, index), node.bounds),
        )
    }

    private fun ancestors(nodes: List<SemanticNode>, start: Int): Sequence<Int> = sequence {
        var index: Int? = start
        val visited = hashSetOf<Int>()
        while (index != null && index in nodes.indices && visited.add(index)) {
            yield(index)
            index = nodes[index].parentIndex
        }
    }

    private fun hierarchy(nodes: List<SemanticNode>, index: Int): IntArray = ancestors(nodes, index).toList().asReversed().toIntArray()
    private fun actionable(node: SemanticNode) = node.clickable || node.checkable || node.focusable
    private fun contains(rect: NormalizedRect, point: VideoPoint) = point.x in rect.left..rect.right && point.y in rect.top..rect.bottom
    private fun area(rect: NormalizedRect) = (rect.right - rect.left) * (rect.bottom - rect.top)
    private fun centerX(rect: NormalizedRect) = (rect.left + rect.right) / 2f
    private fun centerY(rect: NormalizedRect) = (rect.top + rect.bottom) / 2f
    private fun distance(a: NormalizedRect, b: NormalizedRect) = hypot(centerX(a) - centerX(b), centerY(a) - centerY(b))
    private fun score(node: SemanticNode, point: VideoPoint): Float {
        var score = 0f
        if (node.clickable) score += 10f
        if (node.checkable) score += 7f
        if (node.focusable) score += 4f
        if (node.important) score += 2f
        if (!node.viewIdResourceName.isNullOrBlank()) score += 2f
        if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) score += 2f
        if (!node.enabled) score -= 4f
        score += (1f - area(node.bounds)).coerceAtLeast(0f) * 3f
        score -= hypot(centerX(node.bounds) - point.x, centerY(node.bounds) - point.y)
        return score
    }

    private fun confidence(node: SemanticNode, point: VideoPoint): Float =
        (.55f + if (actionable(node)) .22f else 0f + if (!node.viewIdResourceName.isNullOrBlank()) .12f else 0f -
            hypot(centerX(node.bounds) - point.x, centerY(node.bounds) - point.y) * .1f).coerceIn(.35f, .99f)

    private fun role(node: SemanticNode): UiRole = when {
        node.editable -> UiRole.EDIT_TEXT
        node.checkable && node.className?.contains("Switch", true) == true -> UiRole.SWITCH
        node.checkable && node.className?.contains("Radio", true) == true -> UiRole.RADIO_BUTTON
        node.checkable -> UiRole.CHECKBOX
        node.className?.contains("Button", true) == true || node.clickable -> UiRole.BUTTON
        node.className?.contains("Image", true) == true -> UiRole.IMAGE
        !node.text.isNullOrBlank() -> UiRole.TEXT
        else -> UiRole.UNKNOWN
    }

    private fun safeHash(value: String?, sensitive: Boolean): String? {
        if (sensitive || value.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
