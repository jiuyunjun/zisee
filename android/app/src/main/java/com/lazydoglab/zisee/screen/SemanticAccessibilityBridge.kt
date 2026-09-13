package com.lazydoglab.zisee.screen

import com.lazydoglab.zisee.ar.annotation.VideoPoint

/** Process-local handoff: the call remains owned by NativeRtcSession, not AccessibilityService. */
object SemanticAccessibilityBridge {
    interface Listener {
        fun onAvailabilityChanged(available: Boolean)
        fun onResolved(requestId: String, target: SemanticTarget)
        fun onUpdated(target: SemanticTarget)
        fun onLost(requestId: String?, reason: UiTargetLostReason)
    }

    private var service: ZiseeGuidanceAccessibilityService? = null
    private var listener: Listener? = null

    fun isAvailable(): Boolean = service != null

    fun setListener(value: Listener?) {
        listener = value
        value?.onAvailabilityChanged(service != null)
    }

    fun attach(value: ZiseeGuidanceAccessibilityService) {
        service = value
        listener?.onAvailabilityChanged(true)
    }

    fun detach(value: ZiseeGuidanceAccessibilityService) {
        if (service !== value) return
        service = null
        listener?.onAvailabilityChanged(false)
        listener?.onLost(null, UiTargetLostReason.NO_ACCESSIBILITY_SERVICE)
    }

    fun resolve(requestId: String, point: VideoPoint, showCoordinateFallback: Boolean) {
        val current = service
        if (current == null) listener?.onLost(requestId, UiTargetLostReason.NO_ACCESSIBILITY_SERVICE)
        else current.resolve(requestId, point, showCoordinateFallback)
    }

    fun clear() = service?.clearTarget()
    internal fun resolved(requestId: String, target: SemanticTarget) = listener?.onResolved(requestId, target)
    internal fun updated(target: SemanticTarget) = listener?.onUpdated(target)
    internal fun lost(requestId: String?, reason: UiTargetLostReason) = listener?.onLost(requestId, reason)
}
