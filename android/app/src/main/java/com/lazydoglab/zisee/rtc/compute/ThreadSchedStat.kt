package com.lazydoglab.zisee.rtc.compute

import java.io.RandomAccessFile

/**
 * The owning thread's scheduler counters from /proc/thread-self/schedstat, resolved at open, so
 * open it on the thread being measured: on-CPU ns and runqueue-wait ns. Diagnostic only; any
 * failure makes [read] return false.
 */
internal class ThreadSchedStat private constructor(private val file: RandomAccessFile) : AutoCloseable {
    private val buffer = ByteArray(96)

    /** Fills [out] with (cpuNs, runqueueWaitNs). */
    fun read(out: LongArray): Boolean = try {
        file.seek(0)
        val count = file.read(buffer)
        count > 0 && parse(String(buffer, 0, count, Charsets.US_ASCII), out)
    } catch (_: Exception) { false }

    override fun close() { try { file.close() } catch (_: Exception) {} }

    companion object {
        fun openOrNull(): ThreadSchedStat? =
            try { ThreadSchedStat(RandomAccessFile("/proc/thread-self/schedstat", "r")) } catch (_: Exception) { null }

        /** Format: "<cpu ns> <runqueue wait ns> <timeslices>". */
        internal fun parse(text: String, out: LongArray): Boolean {
            val parts = text.trim().split(' ')
            if (parts.size < 2) return false
            val cpu = parts[0].toLongOrNull() ?: return false
            val wait = parts[1].toLongOrNull() ?: return false
            out[0] = cpu; out[1] = wait
            return true
        }
    }
}
