package com.lazydoglab.zisee.screen

import com.lazydoglab.zisee.ar.annotation.VideoPoint
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class UiSemanticResolverTest {
    private val full = NormalizedRect(0f, 0f, 1f, 1f)
    private fun node(parent: Int? = null, bounds: NormalizedRect = NormalizedRect(.1f, .1f, .9f, .2f),
        text: String? = null, id: String? = null, clickable: Boolean = false, editable: Boolean = false,
        password: Boolean = false, className: String = "android.view.View") = SemanticNode(
        7, parent, className, id, text, null, bounds, true, clickable, false, false, true,
        password, editable, true, if (clickable) 1 else 0,
    )

    @Test fun hitTestPromotesTextToClickableRowAndIgnoresRootContainer() {
        val nodes = listOf(
            node(bounds = full),
            node(parent = 0, clickable = true, id = "app:id/wifi", className = "android.widget.LinearLayout"),
            node(parent = 1, bounds = NormalizedRect(.2f, .12f, .5f, .18f), text = "Wi-Fi", className = "android.widget.TextView"),
        )
        val result = requireNotNull(UiSemanticResolver.resolve(nodes, VideoPoint(.3f, .15f), 4))
        assertEquals("app:id/wifi", result.locator.viewIdResourceName)
        assertEquals(UiRole.BUTTON, result.target.role)
        assertTrue(result.target.clickable)
    }

    @Test fun editableAndPasswordTextNeverEnterLocator() {
        for (node in listOf(node(text = "123456", editable = true), node(text = "secret", password = true))) {
            val result = requireNotNull(UiSemanticResolver.resolve(listOf(node), VideoPoint(.2f, .15f), 1))
            assertNull(result.locator.textHash)
            assertNull(result.locator.contentDescriptionHash)
        }
    }

    @Test fun relocationPrefersStableViewIdAndNearestBounds() {
        val original = requireNotNull(UiSemanticResolver.resolve(listOf(node(id = "app:id/item", clickable = true)), VideoPoint(.2f, .15f), 1))
        val moved = node(bounds = NormalizedRect(.1f, .5f, .9f, .6f), id = "app:id/item", clickable = true)
        val result = requireNotNull(UiSemanticResolver.relocate(listOf(moved), original.locator, 2))
        assertEquals(moved.bounds, result.target.bounds)
        assertEquals(2, result.target.treeRevision)
    }

    @Test fun semanticPacketsAreBoundedAndRoundTripWithoutText() {
        val target = SemanticTarget("ui-1", 7, UiRole.BUTTON, NormalizedRect(.1f, .2f, .8f, .3f),
            true, true, 1, .94f, 11)
        val packet = GuidancePacket(GuidancePacket.UI_TARGET_RESOLVED, "share", 3, "req-1", semantic = target)
        val decoded = requireNotNull(GuidanceWire.decode(GuidanceWire.encode(packet)))
        assertEquals(GuidancePacket.UI_TARGET_RESOLVED, decoded.kind)
        assertEquals(target, decoded.semantic)
        assertNull(decoded.point)

        val invalid = packet.copy(semantic = target.copy(bounds = NormalizedRect(-1f, 0f, 1f, 1f)))
        assertNull(GuidanceWire.decode(GuidanceWire.encode(invalid)))
    }

    @Test fun p0PacketsKeepTheLegacyWireVersion() {
        val bytes = GuidanceWire.encode(GuidancePacket(GuidancePacket.REQUEST, "share"))
        assertEquals(0x5A534701, ByteBuffer.wrap(bytes).int)
        assertNotNull(GuidanceWire.decode(bytes))
    }
}
