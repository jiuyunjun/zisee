package com.lazydoglab.zisee.signaling

/** Existing servers accept candidates only within two minutes of the generation's SDP. */
object CandidateExchangePolicy {
    fun needsFreshGeneration(sentMs: Long?, nowMs: Long, sentCount: Int, localCount: Int, overflow: Boolean): Boolean =
        overflow || (sentMs != null && localCount > sentCount && nowMs - sentMs >= 90_000)
}
