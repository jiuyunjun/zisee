package com.lazydoglab.zisee.rtc.compute

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/** Opt-in Debug only. See COMPUTE_QUALITY_HANDOFF for SDK telemetry and rollout limits. */
object RoiDetectorProvider {
    fun create(): RoiDetector = BundledFaceDetector()
}

private class BundledFaceDetector : RoiDetector {
    private var client: FaceDetector? = null
    private var closed = false

    override fun detect(input: RoiInput): List<RoiBox> {
        check(!closed)
        require(input is RgbaRoiInput)
        // Lazy init on the analyzer worker, never in the capture/GL callback.
        val detector = client ?: FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()).also { client = it }
        val pixels = input.uprightArgb()
        val bitmap = try { Bitmap.createBitmap(pixels, input.uprightWidth, input.uprightHeight, Bitmap.Config.ARGB_8888) }
            finally { pixels.fill(0) }
        try {
            // Wait for actual completion before recycling input. A timeout alone does not cancel
            // SDK work and would allow it to read freed pixels. RoiAnalyzer bounds age and admission.
            val task = detector.process(InputImage.fromBitmap(bitmap, 0))
            var interrupted = false
            val faces = try {
                while (true) {
                    try { Tasks.await(task); break }
                    catch (_: InterruptedException) { interrupted = true }
                }
                task.result
            } finally { if (interrupted) Thread.currentThread().interrupt() }
            return faces.map { face ->
                val bounds = face.boundingBox
                RoiBox((bounds.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                    (bounds.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                    (bounds.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                    (bounds.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f))
            }
        } finally { bitmap.recycle() }
    }

    override fun close() {
        if (closed) return
        closed = true
        client?.close(); client = null
    }
}
