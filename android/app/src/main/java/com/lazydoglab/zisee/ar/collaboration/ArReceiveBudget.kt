package com.lazydoglab.zisee.ar.collaboration

/** Token bucket: permits a short UI burst but bounds sustained peer work on the GL thread. */
internal class ArReceiveBudget(private val capacity: Int = 30) {
    private var tokens = capacity.toDouble()
    private var lastMs: Long? = null
    fun accept(nowMs: Long): Boolean {
        val elapsed = lastMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0
        tokens = minOf(capacity.toDouble(), tokens + elapsed * capacity / 1_000.0)
        lastMs = maxOf(lastMs ?: nowMs, nowMs)
        if (tokens < 1) return false
        tokens--
        return true
    }
}
