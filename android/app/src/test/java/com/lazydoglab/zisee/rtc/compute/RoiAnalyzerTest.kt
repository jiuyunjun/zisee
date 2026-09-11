package com.lazydoglab.zisee.rtc.compute

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class RoiAnalyzerTest {
    private val geometry = RoiGeometry(1, 640, 360, 90)
    private val box = RoiBox(0.1f, 0.2f, 0.6f, 0.8f)
    private class Input(override val geometry: RoiGeometry, override val timestampNs: Long = 100L) : RoiInput {
        val releases = AtomicInteger()
        override fun close() { releases.incrementAndGet() }
    }
    private class Detector(val action: (RoiInput) -> List<RoiBox>) : RoiDetector {
        val closed = CountDownLatch(1)
        val releases = AtomicInteger()
        override fun detect(input: RoiInput) = action(input)
        override fun close() { releases.incrementAndGet(); closed.countDown() }
    }
    private fun awaitIdle(analyzer: RoiAnalyzer) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (analyzer.state == RoiState.BUSY && System.nanoTime() < deadline) Thread.yield()
        assertNotEquals(RoiState.BUSY, analyzer.state)
    }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS)) }

    @Test fun boundsWallAndFrameAgeAndDoesNotReuseFutureDetection() {
        val clock = AtomicLong(1_000)
        val detector = Detector { listOf(box) }
        RoiAnalyzer(detector, { fail(it) }, clock::get).use { analyzer ->
            analyzer.configure(geometry)
            val input = Input(geometry)
            assertTrue(analyzer.submit(input)); awaitIdle(analyzer)
            assertEquals(1, input.releases.get())
            assertEquals(listOf(box), analyzer.regions(geometry, 100))
            assertNull(analyzer.regions(geometry, 99))
            assertNull(analyzer.regions(geometry, 500_000_101))
            assertNull(analyzer.regions(geometry.copy(rotation = 180), 100))
            clock.set(999); assertNull(analyzer.regions(geometry, 100))
            clock.set(500_001_001); assertNull(analyzer.regions(geometry, 100))
        }
        await(detector.closed)
    }

    @Test fun singleInFlightNoQueueAndCloseDefersNativeRelease() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val detector = Detector { entered.countDown(); await(unblock); listOf(box) }
        val analyzer = RoiAnalyzer(detector, { fail(it) })
        val first = Input(geometry); val rejected = Input(geometry)
        try {
            analyzer.configure(geometry)
            assertTrue(analyzer.submit(first)); await(entered)
            assertFalse(analyzer.canSubmit())
            assertFalse(analyzer.submit(rejected)); assertEquals(1, rejected.releases.get())
            analyzer.close(); analyzer.close()
            assertEquals(RoiState.CLOSED, analyzer.state)
            assertEquals(0, first.releases.get()); assertEquals(0, detector.releases.get())
        } finally { unblock.countDown(); analyzer.close() }
        await(detector.closed)
        assertEquals(1, first.releases.get()); assertEquals(1, detector.releases.get())
        assertNull(analyzer.regions(geometry, 100))
        val afterClose = Input(geometry)
        assertFalse(analyzer.submit(afterClose)); assertEquals(1, afterClose.releases.get())
    }

    @Test fun resetDiscardsInFlightEvenWhenGeometryReturnsToSameValue() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val detector = Detector { entered.countDown(); await(unblock); listOf(box) }
        RoiAnalyzer(detector, { fail(it) }).use { analyzer ->
            try {
                analyzer.configure(geometry); analyzer.submit(Input(geometry)); await(entered)
                analyzer.configure(null); analyzer.configure(geometry)
            } finally { unblock.countDown() }
            awaitIdle(analyzer)
            assertNull(analyzer.regions(geometry, 100))
        }
        await(detector.closed)
    }

    @Test fun rateLimitAndDisabledAdmissionReleaseRejectedInput() {
        val clock = AtomicLong(0)
        val detector = Detector { emptyList() }
        RoiAnalyzer(detector, { fail(it) }, clock::get).use { analyzer ->
            val disabled = Input(geometry)
            assertFalse(analyzer.submit(disabled)); assertEquals(1, disabled.releases.get())
            analyzer.configure(geometry)
            val mismatch = Input(geometry.copy(generation = 2))
            assertFalse(analyzer.submit(mismatch)); assertEquals(1, mismatch.releases.get())
            assertTrue(analyzer.submit(Input(geometry))); awaitIdle(analyzer)
            assertEquals(emptyList<RoiBox>(), analyzer.regions(geometry, 100))
            clock.set(499_999_999); assertFalse(analyzer.canSubmit())
            clock.set(500_000_000); assertTrue(analyzer.canSubmit())
            analyzer.configure(null); assertNull(analyzer.regions(geometry, 100))
        }
        await(detector.closed)
    }

    @Test fun slowDetectorCannotRefreshOldResultAtCompletion() {
        val clock = AtomicLong(0)
        val detector = Detector { clock.set(500_000_001); listOf(box) }
        RoiAnalyzer(detector, { fail(it) }, clock::get).use { analyzer ->
            analyzer.configure(geometry); analyzer.submit(Input(geometry)); awaitIdle(analyzer)
            assertNull(analyzer.regions(geometry, 100))
        }
        await(detector.closed)
    }

    @Test fun invalidOrExcessiveRegionsFailClosedAndReportOnlyStage() {
        for (boxes in listOf(listOf(box.copy(left = Float.NaN)), listOf(box.copy(right = 0f)),
            listOf(box.copy(bottom = 2f)), List(9) { box })) {
            val failure = CountDownLatch(1)
            val detector = Detector { boxes }
            RoiAnalyzer(detector, { assertEquals("detect", it); failure.countDown() }).use { analyzer ->
                analyzer.configure(geometry); analyzer.submit(Input(geometry)); await(failure)
                assertEquals(RoiState.FAILED, analyzer.state)
                assertNull(analyzer.regions(geometry, 100)); assertFalse(analyzer.canSubmit())
            }
            await(detector.closed)
        }
    }

    @Test fun detectorExceptionStillReleasesInputAndDetector() {
        val failure = CountDownLatch(1)
        val detector = Detector { throw IllegalStateException("private detector detail") }
        val input = Input(geometry)
        RoiAnalyzer(detector, { assertEquals("detect", it); failure.countDown() }).use { analyzer ->
            analyzer.configure(geometry); analyzer.submit(input); await(failure)
            assertEquals(RoiState.FAILED, analyzer.state)
        }
        await(detector.closed)
        assertEquals(1, input.releases.get())
    }
}
