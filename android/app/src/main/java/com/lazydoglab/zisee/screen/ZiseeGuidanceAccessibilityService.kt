package com.lazydoglab.zisee.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lazydoglab.zisee.ar.annotation.VideoPoint

/** Resolves only the current accessible window and never performs actions or exports tree text. */
class ZiseeGuidanceAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var revision = 0L
    private var active: ResolvedSemanticTarget? = null
    private var pendingRequest: String? = null
    private var highlight: HighlightView? = null
    private val track = Runnable { trackActive() }

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        SemanticAccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (active == null) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handler.removeCallbacks(track)
                handler.postDelayed(track, 60)
            }
        }
    }

    override fun onInterrupt() = Unit

    fun resolve(requestId: String, point: VideoPoint, showCoordinateFallback: Boolean) {
        pendingRequest = requestId
        val result = snapshot()?.let { UiSemanticResolver.resolve(it, point, ++revision) }
        if (result == null) {
            clearTarget()
            if (showCoordinateFallback) showFallback(point)
            SemanticAccessibilityBridge.lost(requestId, UiTargetLostReason.NO_ACCESSIBILITY_NODE)
            return
        }
        active = result
        show(result.target)
        SemanticAccessibilityBridge.resolved(requestId, result.target)
    }

    private fun showFallback(point: VideoPoint) {
        val radius = .025f
        val bounds = NormalizedRect((point.x - radius).coerceAtLeast(0f), (point.y - radius).coerceAtLeast(0f),
            (point.x + radius).coerceAtMost(1f), (point.y + radius).coerceAtMost(1f))
        if (bounds.valid()) show(SemanticTarget("fallback", 0, UiRole.UNKNOWN, bounds,
            false, true, 0, 0f, revision))
        handler.postDelayed({ if (active == null) removeHighlight() }, 1_500)
    }

    fun clearTarget() {
        handler.removeCallbacks(track)
        active = null
        pendingRequest = null
        removeHighlight()
    }

    private fun trackActive() {
        val previous = active ?: return
        val relocated = snapshot()?.let { UiSemanticResolver.relocate(it, previous.locator, ++revision) }
        if (relocated == null) {
            clearTarget()
            SemanticAccessibilityBridge.lost(null, UiTargetLostReason.WINDOW_CHANGED)
            return
        }
        val stable = relocated.copy(target = relocated.target.copy(targetId = previous.target.targetId))
        active = stable
        if (stable.target.bounds != previous.target.bounds || stable.target.enabled != previous.target.enabled) {
            show(stable.target)
            SemanticAccessibilityBridge.updated(stable.target)
        }
    }

    @Suppress("DEPRECATION") // recycle() is still required below API 33, which this app supports.
    private fun snapshot(): List<SemanticNode>? {
        val root = rootInActiveWindow ?: return null
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1).toFloat()
        val screenHeight = metrics.heightPixels.coerceAtLeast(1).toFloat()
        val result = ArrayList<SemanticNode>()
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            if (depth > 64 || result.size >= 2_048) return
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val index = result.size
            val sensitive = node.isPassword || node.isEditable
            result += SemanticNode(node.windowId, parent, node.className?.toString(), node.viewIdResourceName,
                node.text?.toString().takeUnless { sensitive }, node.contentDescription?.toString().takeUnless { sensitive },
                NormalizedRect((bounds.left / screenWidth).coerceIn(0f, 1f), (bounds.top / screenHeight).coerceIn(0f, 1f),
                    (bounds.right / screenWidth).coerceIn(0f, 1f), (bounds.bottom / screenHeight).coerceIn(0f, 1f)),
                node.isVisibleToUser, node.isClickable, node.isCheckable, node.isFocusable, node.isEnabled,
                node.isPassword, node.isEditable, node.isImportantForAccessibility, actionMask(node))
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                try { visit(child, index, depth + 1) } finally { child.recycle() }
            }
        }
        try { visit(root, null, 0) } finally { root.recycle() }
        return result
    }

    private fun actionMask(node: AccessibilityNodeInfo): Long {
        var mask = 0L
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) mask = mask or 1
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) mask = mask or 2
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) mask = mask or 4
        if (!node.isPassword && !node.isEditable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) mask = mask or 8
        return mask
    }

    private fun show(target: SemanticTarget) {
        val metrics = resources.displayMetrics
        val left = (target.bounds.left * metrics.widthPixels).toInt()
        val top = (target.bounds.top * metrics.heightPixels).toInt()
        val width = ((target.bounds.right - target.bounds.left) * metrics.widthPixels).toInt().coerceAtLeast(2)
        val height = ((target.bounds.bottom - target.bounds.top) * metrics.heightPixels).toInt().coerceAtLeast(2)
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; x = left; y = top
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        val wm = getSystemService(WindowManager::class.java)
        val view = highlight ?: HighlightView().also {
            highlight = it
            try { wm.addView(it, params) } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to add accessibility highlight", error); highlight = null; return
            }
        }
        try { wm.updateViewLayout(view, params) } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to update accessibility highlight", error); removeHighlight()
        }
    }

    private fun removeHighlight() {
        val view = highlight ?: return
        highlight = null
        view.stop()
        try { if (view.isAttachedToWindow) getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        catch (error: RuntimeException) { Log.w(TAG, "Unable to remove accessibility highlight", error) }
    }

    private inner class HighlightView : View(this@ZiseeGuidanceAccessibilityService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 212, 214); style = Paint.Style.STROKE }
        private val animator = ValueAnimator.ofFloat(.55f, 1f).apply {
            duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
        override fun onDraw(canvas: Canvas) {
            paint.strokeWidth = resources.displayMetrics.density * 3
            val inset = paint.strokeWidth / 2
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, 12f, 12f, paint)
        }
        fun stop() = animator.cancel()
    }

    override fun onDestroy() {
        clearTarget()
        SemanticAccessibilityBridge.detach(this)
        super.onDestroy()
    }

    private companion object { const val TAG = "ZiseeSemantic" }
}
