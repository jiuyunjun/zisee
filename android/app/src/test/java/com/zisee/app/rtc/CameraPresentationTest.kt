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
