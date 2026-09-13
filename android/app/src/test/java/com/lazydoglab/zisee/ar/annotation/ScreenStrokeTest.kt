package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test

class ScreenStrokeTest {
    @Test fun dashesHaveGapsAndMaintainPhaseAcrossInputSamples() {
        val dashes = screenStrokeDashes(listOf(VideoPoint(0f, .5f), VideoPoint(.1f, .5f), VideoPoint(1f, .5f)),
            100, 100, dashPixels = 12f).toList()
        // The first dash spans the input boundary (0..10 + 10..12), then an actual gap (12..24).
        assertEquals(.12f, dashes[1].second.x, .0001f)
        assertEquals(.24f, dashes[2].first.x, .0001f)
        assertTrue(dashes.all { it.first.y == .5f && it.second.y == .5f })
    }

    @Test fun degenerateSamplesAndLongPathsStayWithinRenderingBudget() {
        assertTrue(screenStrokeDashes(List(10) { VideoPoint(.5f, .5f) }, 100, 100).none())
        val zigzag = List(512) { VideoPoint((it % 2).toFloat(), .5f) }
        assertEquals(32, screenStrokeDashes(zigzag, 16000, 16000, maxDashes = 32).count())
    }

    @Test fun traceRejectsReorderedFramesAndBoundsMemory() {
        val first = SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, 2), VideoPoint(.5f, .5f))
        val trace = StrokeTrace(first, true)
        assertFalse(trace.append(first.copy(frame = first.frame.copy(timestampNs = 1))))
        repeat(AnnotationBudget.MAX_STROKE_POINTS - 1) { assertTrue(trace.append(first)) }
        assertFalse(trace.append(first))
        trace.finish()
        assertFalse(trace.append(first))
    }
}
