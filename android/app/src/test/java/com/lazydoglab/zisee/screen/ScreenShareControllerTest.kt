package com.lazydoglab.zisee.screen

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ScreenShareControllerTest {
    private val portrait = ScreenSize(1080, 1920)
    private val landscape = ScreenSize(1920, 1080)
    private val portraitOutput = ScreenCaptureSize.of(portrait.width, portrait.height)
    private val landscapeOutput = ScreenCaptureSize.of(landscape.width, landscape.height)

    private class Backend : ScreenProjection {
        lateinit var events: ScreenProjectionEvents
        var starts = 0
        var closes = 0
        val sizes = mutableListOf<ScreenSize>()
        var failStart = false
        var failResize = false
        var failClose = false
        var stopDuringStart = false
        override fun start(size: ScreenSize, events: ScreenProjectionEvents) {
            this.events = events
            starts++
            sizes += size
            if (failStart) error("simulated private platform error")
            if (stopDuringStart) events.stopped()
        }
        override fun resize(size: ScreenSize) {
            if (failResize) error("resize failure")
            sizes += size
        }
        override fun close() {
            closes++
            if (::events.isInitialized) events.stopped() // Exercise release callback reentrancy.
            if (failClose) error("release failure")
        }
    }

    @Test fun consentAndDisplayCreationDoNotClaimLiveUntilScreenFrameArrives() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        val backend = Backend()
        assertTrue(controller.start(request, portrait) { backend })
        assertEquals(ScreenSharePhase.STARTING, controller.state.value.phase)
        controller.onFrame(request.copy(id = UUID.randomUUID()))
        assertEquals(ScreenSharePhase.STARTING, controller.state.value.phase)
        controller.onFrame(request)
        assertEquals(ScreenSharePhase.ACTIVE, controller.state.value.phase)
        controller.firstFrameTimeout(request)
        assertEquals(ScreenSharePhase.ACTIVE, controller.state.value.phase)
        controller.close()
        assertEquals(1, backend.closes)
    }

    @Test fun deniedAndLateConsentNeverCreateProjection() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        assertFalse(controller.deny(request.copy(callId = "old-call")))
        assertTrue(controller.deny(request))
        assertFalse(controller.start(request, portrait) { error("must not obtain projection") })
        assertEquals(ScreenShareReason.CONSENT_DENIED, controller.state.value.reason)
        assertThrows(IllegalStateException::class.java) { controller.request() }
    }

    @Test fun hangupWhileWaitingRejectsLateAuthorizationAndFrames() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        controller.close()
        assertFalse(controller.start(request, portrait) { error("must not allocate") })
        controller.onFrame(request)
        assertEquals(ScreenSharePhase.CLOSED, controller.state.value.phase)
    }

    @Test fun consentCannotBeUsedTwiceOrForDifferentCall() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        assertFalse(controller.start(request.copy(callId = "other"), portrait) { error("wrong call") })
        val backend = Backend()
        assertTrue(controller.start(request, portrait) { backend })
        assertFalse(controller.start(request, portrait) { error("duplicate consent") })
        controller.stop()
        assertFalse(controller.start(request, portrait) { error("stopped token") })
        assertEquals(1, backend.starts)
    }

    @Test fun partialStartFailureClosesBackendAndDoesNotRetryToken() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        val backend = Backend().apply { failStart = true }
        assertFalse(controller.start(request, portrait) { backend })
        assertEquals(ScreenSharePhase.FAILED, controller.state.value.phase)
        assertEquals(ScreenShareReason.START_FAILED, controller.state.value.reason)
        assertEquals(1, backend.closes)
        controller.close()
        assertEquals(1, backend.closes)
    }

    @Test fun factoryFailureBecomesExplicitTerminalFailure() {
        val controller = ScreenShareController("call")
        assertFalse(controller.start(controller.request(), portrait) { error("allocation failure") })
        assertEquals(ScreenSharePhase.FAILED, controller.state.value.phase)
    }

    @Test fun systemStopDuringStartCannotReturnSuccess() {
        val controller = ScreenShareController("call")
        val backend = Backend().apply { stopDuringStart = true }
        assertFalse(controller.start(controller.request(), portrait) { backend })
        assertEquals(ScreenShareReason.SYSTEM_STOPPED, controller.state.value.reason)
        assertEquals(1, backend.closes)
    }

    @Test fun resizeAndVisibilityDoNotRestartOrStopCapture() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        val backend = Backend()
        controller.start(request, portrait) { backend }
        controller.onFrame(request)
        backend.events.resized(landscape)
        backend.events.resized(landscape)
        backend.events.visibilityChanged(false)
        assertEquals(listOf(portraitOutput, landscapeOutput), backend.sizes)
        assertEquals(landscapeOutput, controller.state.value.size)
        assertEquals(false, controller.state.value.contentVisible)
        assertEquals(ScreenSharePhase.ACTIVE, controller.state.value.phase)
        assertEquals(1, backend.starts)
        controller.close()
    }

    @Test fun everyResizeEntryUsesOneAspectPreservingCeiling() {
        val controller = ScreenShareController("call")
        val backend = Backend()
        controller.start(controller.request(), ScreenSize(1440, 3120)) { backend }
        controller.resize(ScreenSize(2208, 1840))
        assertEquals(ScreenCaptureSize.of(2208, 1840), controller.state.value.size)
        assertEquals(controller.state.value.size, backend.sizes.last())
        assertTrue(backend.sizes.all { maxOf(it.width, it.height) <= ScreenCaptureSize.MAX_LONG_EDGE })
    }

    @Test fun contentSizeIsKeptRawOnStartAndResize() {
        val controller = ScreenShareController("call")
        val backend = Backend()
        controller.start(controller.request(), ScreenSize(1440, 3120)) { backend }
        assertEquals(ScreenSize(1440, 3120), controller.state.value.contentSize)
        assertEquals(ScreenCaptureSize.of(1440, 3120), controller.state.value.size)
        controller.resize(ScreenSize(2208, 1840))
        assertEquals(ScreenSize(2208, 1840), controller.state.value.contentSize)
        assertEquals(ScreenCaptureSize.of(2208, 1840), controller.state.value.size)
    }

    @Test fun lateCallbacksAfterStopDoNotResurrectCapture() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        val backend = Backend()
        controller.start(request, portrait) { backend }
        controller.stop(ScreenShareReason.LOCKED)
        val stopped = controller.state.value
        backend.events.resized(landscape)
        backend.events.visibilityChanged(true)
        backend.events.stopped()
        backend.events.failed()
        controller.onFrame(request)
        controller.firstFrameTimeout(request)
        assertEquals(stopped, controller.state.value)
        assertEquals(1, backend.closes)
    }

    @Test fun missingFirstFrameTerminatesAndReleasesCapture() {
        val controller = ScreenShareController("call")
        val request = controller.request()
        val backend = Backend()
        controller.start(request, portrait) { backend }
        controller.firstFrameTimeout(request.copy(id = UUID.randomUUID()))
        assertEquals(0, backend.closes)
        controller.firstFrameTimeout(request)
        assertEquals(ScreenShareReason.FIRST_FRAME_TIMEOUT, controller.state.value.reason)
        assertEquals(ScreenSharePhase.FAILED, controller.state.value.phase)
        assertEquals(1, backend.closes)
    }

    @Test fun resizeFailureAndCleanupFailureAreObservable() {
        val events = mutableListOf<ScreenShareEvent>()
        val controller = ScreenShareController("call", events::add)
        val backend = Backend().apply { failResize = true; failClose = true }
        controller.start(controller.request(), portrait) { backend }
        controller.resize(landscape)
        assertEquals(ScreenSharePhase.FAILED, controller.state.value.phase)
        assertEquals(ScreenShareReason.CAPTURE_FAILED, controller.state.value.reason)
        assertTrue(events.contains(ScreenShareEvent.CLEANUP_FAILED))
        assertEquals(ScreenShareEvent.FAILED, events.last())
        controller.stop()
        assertEquals(1, backend.closes)
    }

    @Test fun ownerThreadAndDimensionsAreValidated() {
        val controller = ScreenShareController("call")
        val failure = AtomicReference<Throwable>()
        Thread {
            try { controller.request() } catch (error: Throwable) { failure.set(error) }
        }.apply { start(); join() }
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(ScreenSharePhase.IDLE, controller.state.value.phase)
        assertThrows(IllegalArgumentException::class.java) { ScreenSize(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ScreenSize(1, 16_385) }
    }
}
