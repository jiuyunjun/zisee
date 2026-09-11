package com.lazydoglab.zisee.rtc.compute

import java.util.concurrent.Executors

/** Normalized, upright image coordinates. Detector adapters must undo their resize/letterbox. */
data class RoiBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    internal fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        left < right && top < bottom
}

/** Generation changes on camera switch, crop/transform change, stop, or AR takeover. */
data class RoiGeometry(val generation: Long, val width: Int, val height: Int, val rotation: Int)

/** An owned, bounded detector input, never a borrowed camera texture. Metadata must be immutable.
 * close releases its pixels and must be safe on either the submitting thread or analyzer worker.
 */
interface RoiInput : AutoCloseable {
    val geometry: RoiGeometry
    val timestampNs: Long
}

/** Synchronous adapter invoked only on the analyzer worker; creation must not require UI work. */
interface RoiDetector : AutoCloseable {
    fun detect(input: RoiInput): List<RoiBox>
}

enum class RoiState { READY, BUSY, FAILED, CLOSED }

/** One instance per source. No pending-frame queue and at most two analyses per second.
 *
 * This is detector infrastructure, not face detection or an encoder QP map. Callers must avoid
 * pixel readback when canSubmit is false; submit rechecks admission and ALWAYS takes ownership.
 * close is nonblocking: in-flight detection releases input and detector on its worker afterwards.
 * A synchronous detector must eventually return; Java cannot safely cancel native inference.
 * The owner must close even after FAILED. onFailure must be thread-safe and must not throw.
 */
class RoiAnalyzer(
    private val detector: RoiDetector,
    private val onFailure: (String) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "ZiseeRoi") }
    private val lock = Any()
    private var geometry: RoiGeometry? = null
    private var lastStartedNs: Long? = null
    private var result: Result? = null
    private var revision = 0L
    private var busy = false
    private var closed = false
    private var failed = false
    private data class Result(val geometry: RoiGeometry, val timestampNs: Long,
        val startedNs: Long, val boxes: List<RoiBox>)

    val state: RoiState get() = synchronized(lock) {
        when { closed -> RoiState.CLOSED; failed -> RoiState.FAILED; busy -> RoiState.BUSY; else -> RoiState.READY }
    }

    /** null disables analysis for C0, screen/AR sources, or unavailable geometry. */
    fun configure(value: RoiGeometry?) = synchronized(lock) {
        require(value == null || (value.width > 0 && value.height > 0 && value.rotation in listOf(0, 90, 180, 270)))
        // Switching geometry invalidates results, but must not bypass the global sampling budget.
        if (geometry != value) { geometry = value; revision++; result = null }
    }

    fun canSubmit(): Boolean = synchronized(lock) { admissible(clockNs()) }

    private fun admissible(now: Long) = !closed && !failed && !busy && geometry != null &&
        (lastStartedNs?.let { now - it >= INTERVAL_NS } ?: true)

    /** Ownership transfers even on rejection. Do not reuse or close input after this call. */
    fun submit(input: RoiInput): Boolean {
        val accepted = synchronized(lock) {
            val now = clockNs()
            if (!admissible(now) || input.geometry != geometry) false
            else {
                busy = true
                lastStartedNs = now
                val submittedRevision = revision
                worker.execute { analyze(input, now, submittedRevision) }
                true
            }
        }
        if (!accepted) releaseInput(input)
        return accepted
    }

    /** Null means unavailable; empty means a fresh successful detection found no regions.
     * Both wall age and frame age are bounded. Never project a future result onto an older frame.
     */
    fun regions(frameGeometry: RoiGeometry, timestampNs: Long): List<RoiBox>? = synchronized(lock) {
        val value = result ?: return@synchronized null
        if (closed || failed || frameGeometry != geometry || value.geometry != frameGeometry ||
            clockNs() - value.startedNs !in 0..MAX_AGE_NS ||
            timestampNs - value.timestampNs !in 0..MAX_AGE_NS) null else value.boxes.toList()
    }

    private fun analyze(input: RoiInput, startedNs: Long, submittedRevision: Long) {
        try {
            val boxes = detector.detect(input)
            // Reject malformed/oversized output rather than accidentally treating a face as background.
            require(boxes.size <= MAX_REGIONS && boxes.all { it.valid() })
            val snapshot = Result(input.geometry, input.timestampNs, startedNs, boxes.toList())
            synchronized(lock) {
                if (!closed && !failed && revision == submittedRevision &&
                    clockNs() - startedNs in 0..MAX_AGE_NS) result = snapshot
            }
        } catch (_: Exception) {
            fail("detect")
        } catch (_: LinkageError) {
            // Optional detector native libraries may be unavailable on a particular ABI/device.
            fail("detector_linkage")
        } finally {
            releaseInput(input)
            synchronized(lock) { busy = false }
        }
    }

    private fun releaseInput(input: RoiInput) {
        try { input.close() } catch (_: Exception) { fail("input_release") }
    }

    private fun fail(stage: String) {
        synchronized(lock) { failed = true; result = null }
        // Fixed stage only: detector exceptions can contain image/model paths or private data.
        onFailure(stage)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; revision++; result = null; geometry = null
            worker.execute {
                try { detector.close() } catch (_: Exception) { fail("detector_release") }
            }
            worker.shutdown()
        }
    }

    private companion object {
        const val INTERVAL_NS = 500_000_000L
        const val MAX_AGE_NS = 500_000_000L
        const val MAX_REGIONS = 8
    }
}
