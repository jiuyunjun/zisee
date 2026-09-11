package com.lazydoglab.zisee.rtc.compute

import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.webrtc.RoiQpMapProvider

data class RoiQpMapStats(
    val supportedCodecs: Set<String> = emptySet(),
    val unsupportedCodecs: Set<String> = emptySet(),
    val activeFrames: Long = 0,
    val neutralFrames: Long = 0,
    val failures: Long = 0,
    val lastFailure: String? = null,
)

/** Bounded timestamp bridge. Duplicate timestamps from different cameras become neutral. */
class RoiQpMapRegistry(private val logger: AppLogger, private val clockNs: () -> Long = System::nanoTime) :
    RoiQpMapProvider {
    private data class Entry(val source: String?, val map: RoiQpMap?, val observedNs: Long)
    private val entries = linkedMapOf<Long, Entry>()
    private val configured = mutableMapOf<String, Boolean>()
    private var state = RoiQpMapStats()

    val stats: RoiQpMapStats @Synchronized get() = state.copy(
        supportedCodecs = state.supportedCodecs.toSet(), unsupportedCodecs = state.unsupportedCodecs.toSet())

    @Synchronized fun record(timestampNs: Long, source: String, map: RoiQpMap?) {
        val now = clockNs()
        prune(now)
        val key = timestampNs / 1_000
        val old = entries[key]
        entries[key] = if (old == null || old.source == source)
            Entry(source, map, maxOf(old?.observedNs ?: now, now))
        else Entry(null, null, maxOf(old.observedNs, now))
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized override fun take(timestampNs: Long, width: Int, height: Int): ByteArray? {
        val now = clockNs()
        prune(now)
        val entry = entries.remove(timestampNs / 1_000) ?: return null
        if (entry.source == null || now < entry.observedNs) return null
        val map = entry.map ?: return null
        return map.bytes().takeIf {
            map.blocksWide == (width + 15) / 16 && map.blocksHigh == (height + 15) / 16
        }
    }

    @Synchronized override fun configured(codecName: String, supported: Boolean) {
        if (configured.put(codecName, supported) == supported) return
        state = state.copy(
            supportedCodecs = if (supported) state.supportedCodecs + codecName else state.supportedCodecs - codecName,
            unsupportedCodecs = if (supported) state.unsupportedCodecs - codecName else state.unsupportedCodecs + codecName)
        logger.info(AppEvent.RTC_COMPUTE_QP_MAP, "codec=${codecName.take(100)}:supported=$supported")
    }

    @Synchronized override fun submitted(codecName: String, active: Boolean) {
        state = if (active) state.copy(activeFrames = state.activeFrames + 1)
        else state.copy(neutralFrames = state.neutralFrames + 1)
    }

    @Synchronized override fun failed(codecName: String, stage: String) {
        val safeStage = stage.takeIf { it in setOf("provider", "size", "set_parameters") } ?: "unknown"
        state = state.copy(failures = state.failures + 1, lastFailure = safeStage)
        logger.error(AppEvent.RTC_COMPUTE_QP_MAP, "codec=${codecName.take(100)}:failed=$safeStage")
    }

    @Synchronized fun clear() { entries.clear() }

    private fun prune(nowNs: Long) {
        entries.entries.removeAll { nowNs - it.value.observedNs > MAX_AGE_NS }
    }

    private companion object {
        const val MAX_ENTRIES = 256
        const val MAX_AGE_NS = 2_000_000_000L
    }
}
