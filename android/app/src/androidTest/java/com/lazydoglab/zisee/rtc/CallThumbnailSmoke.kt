package com.lazydoglab.zisee.rtc

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/** Real Compose/AndroidView swaps: every renderer must acquire the new thumbnail outline. */
internal object CallThumbnailSmoke {
    fun run(test: Instrumentation) {
        val automation = test.uiAutomation
        val activity = test.startActivitySync(Intent().setClassName(test.targetContext.packageName,
            "com.lazydoglab.zisee.ui.CallPreviewActivity").putExtra("scene", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun renderers(view: View): List<TextureViewRenderer> = when (view) {
            is TextureViewRenderer -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { renderers(view.getChildAt(it)) }
            else -> emptyList()
        }
        try {
            test.waitForIdleSync()
            android.os.SystemClock.sleep(800)
            var original = emptySet<TextureViewRenderer>()
            val rounded = mutableSetOf<TextureViewRenderer>()
            fun checkOutlines() = test.runOnMainSync {
                val views = renderers(activity.window.decorView).toSet()
                check(views.size == 4) { "Expected all four live renderer instances" }
                if (original.isEmpty()) original = views else check(original == views) { "Swap recreated renderers" }
                check(views.count { it.cornerRadius > 0 } == 3)
                views.filter { it.cornerRadius > 0 }.forEach { view ->
                    val outline = Outline()
                    requireNotNull(view.outlineProvider).getOutline(view, outline)
                    val bounds = Rect()
                    check(view.clipToOutline && outline.getRect(bounds))
                    check(bounds == Rect(0, 0, view.width, view.height)) { "Stale thumbnail bounds: $bounds" }
                    check(outline.radius > 0f)
                    rounded += view
                }
            }
            checkOutlines()
            val before = requireNotNull(automation.takeScreenshot())
            java.io.File(test.targetContext.getExternalFilesDir(null), "rounded-before.png").outputStream().use {
                before.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            before.recycle()
            for ((index, target) in original.withIndex()) {
                if (target.cornerRadius <= 0f) continue
                val location = IntArray(2)
                var x = 0f; var y = 0f
                test.runOnMainSync {
                    target.getLocationOnScreen(location)
                    x = location[0] + target.width / 2f
                    y = location[1] + target.height / 2f
                }
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    android.os.SystemClock.sleep(50)
                }
                test.waitForIdleSync()
                android.os.SystemClock.sleep(400)
                checkOutlines()

                val screenshot = requireNotNull(test.uiAutomation.takeScreenshot())
                try {
                    java.io.File(test.targetContext.getExternalFilesDir(null), "rounded-$index.png").outputStream().use {
                        check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { screenshot.recycle() }
                check(target.cornerRadius == 0f) { "Tap failed to promote renderer at $x,$y" }
            }
            check(rounded == original) { "Every feed must be tested as a rounded thumbnail" }
            val movable = original.first { it.cornerRadius > 0f }
            fun position(): IntArray = IntArray(2).also { out ->
                test.runOnMainSync { movable.getLocationOnScreen(out) }
            }
            val start = position()
            val dragStart = android.os.SystemClock.uptimeMillis()
            val centerX = start[0] + movable.width / 2f
            val centerY = start[1] + movable.height / 2f
            // Move to the left side, retaining an explicitly chosen position across hide/show.
            for (step in 0..12) {
                val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN
                    12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val event = android.view.MotionEvent.obtain(dragStart, android.os.SystemClock.uptimeMillis(),
                    action, centerX + (movable.width / 2f + 60 - centerX) * step / 12,
                    centerY, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                android.os.SystemClock.sleep(40)
            }
            android.os.SystemClock.sleep(400)
            val dragged = position()
            check(dragged[0] < start[0]) { "Drag did not move thumbnail" }
            android.os.SystemClock.sleep(5_500)
            check(position().contentEquals(dragged)) { "Auto-hide reset thumbnail position" }
        } finally { test.runOnMainSync { activity.finish() } }
    }
}
