package com.lazydoglab.zisee.ar.session

/** Main-thread intent epoch. A paused/replaced call cannot consume a delayed preparation result. */
class ArActivationGate {
    private var resumed = false
    private var epoch = 0L
    private var pending: Long? = null
    fun setResumed(value: Boolean) {
        resumed = value
        if (!value) invalidate()
    }
    fun begin(): Long? = if (resumed) (++epoch).also { pending = it } else null
    fun consume(request: Long): Boolean {
        if (!resumed || pending != request) return false
        pending = null
        return true
    }
    fun invalidate() { epoch++; pending = null }
}
