package com.lazydoglab.zisee.rtc

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * A local AR field that is tracking and shown as the main view must hand a real tap on the picture
 * to the marker layer, not to the call surface's show/hide-controls toggle.
 */
internal object CallArTapSmoke {
    /** "ar-late-tracking" is the device order: picture first, TRACKING (and the marker tool) later. */
    fun run(test: Instrumentation): String =
        listOf("ar-notice", "ar-late-tracking").joinToString("\n") { "[$it]\n" + run(test, it) }

    private fun run(test: Instrumentation, scenario: String): String {
        val automation = test.uiAutomation
        fun shell(command: String): String =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        shell("logcat -c")
        val activity = test.startActivitySync(Intent().setClassName(test.targetContext.packageName,
            "com.lazydoglab.zisee.ui.CallPreviewActivity").putExtra("callUiScenario", scenario)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            test.waitForIdleSync()
            SystemClock.sleep(2_500)
            requireNotNull(automation.takeScreenshot()).let { shot ->
                try {
                    java.io.File(test.targetContext.getExternalFilesDir(null), "ar-tap-$scenario.png").outputStream()
                        .use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { shot.recycle() }
            }
            val location = IntArray(2)
            var x = 0f; var y = 0f
            test.runOnMainSync {
                val root = activity.window.decorView
                root.getLocationOnScreen(location)
                x = location[0] + root.width / 2f
                y = location[1] + root.height / 2f
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(60)
            }
            test.waitForIdleSync()
            SystemClock.sleep(800)
            val lines = shell("logcat -d -s Zisee:I").lines().filter { "AR_TAP" in it }
            check(lines.any { "AR_TAP shown" in it }) { "[$scenario] Marker layer never composed:\n${lines.joinToString("\n")}" }
            check(lines.any { "AR_TAP frame=" in it || "AR_TAP outside" in it || "AR_TAP placed" in it }) {
                "Tap at $x,$y never reached the marker layer:\n${lines.joinToString("\n")}"
            }
            return lines.joinToString("\n") { it.substringAfter("Zisee").trim() }
        } finally { test.runOnMainSync { activity.finish() } }
    }
}
