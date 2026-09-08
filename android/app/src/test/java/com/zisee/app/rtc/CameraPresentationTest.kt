package com.zisee.app.rtc

import org.junit.Assert.*
import org.junit.Test

class CameraPresentationTest {
    @Test fun `presentation round trips stable modes and mute state`() {
        for (mode in listOf(CameraMode.FACE, CameraMode.BACK_ONLY, CameraMode.DUAL)) {
            for (enabled in listOf(true, false)) {
                val value = CameraPresentation(mode, enabled)
                assertEquals(value, CameraPresentation.decode(value.encode()))
            }
        }
    }
    @Test fun `peer cannot request activation through presentation messages`() {
        val rejected = listOf("1|STARTING|1", "2|DUAL|1", "1|DUAL|yes", "1|DUAL|1|extra", "open-camera", "x".repeat(100))
        for (text in rejected) assertNull(CameraPresentation.decode(text))
    }
}

class ViewRequestTest {
    @Test fun `view request round trips every layout`() {
        for (front in ViewSize.entries) {
            for (back in ViewSize.entries) {
                val value = ViewRequest(front, back)
                assertEquals(value, ViewRequest.decode(value.encode()))
            }
        }
    }
    @Test fun `the two control messages never decode as each other`() {
        assertNull(CameraPresentation.decode(ViewRequest(ViewSize.SMALL, ViewSize.LARGE).encode()))
        assertNull(ViewRequest.decode(CameraPresentation(CameraMode.DUAL, true).encode()))
    }
    @Test fun `malformed view requests are refused`() {
        val rejected = listOf("V1|LARGE", "V2|LARGE|SMALL", "V1|BIG|SMALL", "V1|LARGE|SMALL|x", "V1|" + "x".repeat(60))
        for (text in rejected) assertNull(ViewRequest.decode(text))
    }
}
