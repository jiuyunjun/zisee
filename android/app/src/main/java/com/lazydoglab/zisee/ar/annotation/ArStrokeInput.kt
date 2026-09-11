package com.lazydoglab.zisee.ar.annotation

import java.util.UUID

enum class ArStrokePhase { BEGIN, APPEND, END, CANCEL }
data class ArStrokeInput(val sessionId: UUID, val id: UUID, val phase: ArStrokePhase,
    val samples: List<SpatialMarkerRequest> = emptyList()) {
    init {
        require(samples.size <= AnnotationBudget.MAX_BATCH_POINTS)
        require(phase != ArStrokePhase.BEGIN || samples.size == 1)
        require(phase != ArStrokePhase.APPEND || samples.isNotEmpty())
    }
}
