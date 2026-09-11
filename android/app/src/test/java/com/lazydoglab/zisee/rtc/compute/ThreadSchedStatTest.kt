package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadSchedStatTest {
    @Test fun parsesCpuAndRunqueueWait() {
        val out = LongArray(2)
        assertTrue(ThreadSchedStat.parse("12590783 280417 8\n", out))
        assertEquals(listOf(12_590_783L, 280_417L), out.toList())
    }

    @Test fun rejectsMalformedText() {
        val out = longArrayOf(7, 7)
        assertFalse(ThreadSchedStat.parse("", out))
        assertFalse(ThreadSchedStat.parse("12 abc 3", out))
        assertFalse(ThreadSchedStat.parse("12", out))
        assertEquals(listOf(7L, 7L), out.toList())
    }
}
