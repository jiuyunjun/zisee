package com.lazydoglab.zisee.rtc.compute

import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderTelemetryTest {
    private class Recorder : AppLogger {
        val lines = mutableListOf<String>()
        override fun error(event: AppEvent, reason: String?) = Unit
        override fun info(event: AppEvent, detail: String?) { if (event == AppEvent.RTC_COMPUTE_ENCODE_SLOW) lines += detail!! }
    }

    @Test fun onlySlowEncodeCallsAreLoggedWithMonotonicStart() {
        val log = Recorder()
        val telemetry = EncoderTelemetry(log)
        telemetry.encodeCall("H264", 5_000_000_000, 7_999_000)
        telemetry.encodeCall("H264", 5_100_000_000, 15_000_000)
        assertEquals(listOf("H264:us=15000:at=5100"), log.lines)
        assertEquals("2/15000", telemetry.encodeCallCost.summary())
    }

    @Test fun slowLogIsRateLimitedPerTenSeconds() {
        val log = Recorder()
        val telemetry = EncoderTelemetry(log)
        repeat(25) { telemetry.encodeCall("H264", 1_000_000_000L + it * 1_000_000L, 9_000_000) }
        assertEquals(20, log.lines.size)
        telemetry.encodeCall("H264", 12_000_000_000, 9_000_000)
        assertEquals(21, log.lines.size)
    }
}
