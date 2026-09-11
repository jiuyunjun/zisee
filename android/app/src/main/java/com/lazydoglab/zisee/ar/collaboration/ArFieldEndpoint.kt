package com.lazydoglab.zisee.ar.collaboration

import com.lazydoglab.zisee.ar.annotation.ArMessage
import com.lazydoglab.zisee.ar.annotation.AnnotationAuthor
import com.lazydoglab.zisee.ar.session.ArSessionController
import com.lazydoglab.zisee.ar.session.ArSessionState
import com.lazydoglab.zisee.ar.session.MarkerResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID

/** Ownership transfers to collaboration on successful attach; close must release the AR camera. */
interface ArFieldEndpoint {
    val sessionId: UUID
    val depthSupported: Boolean
    suspend fun execute(message: ArMessage): ArMessage.Result?
    suspend fun close()
}

/** Construct on the controller's GL owner after starting it; never runs ARCore on an RTC callback. */
class ArControllerEndpoint(
    private val controller: ArSessionController,
    override val depthSupported: Boolean,
    private val glDispatcher: CoroutineDispatcher,
) : ArFieldEndpoint {
    override val sessionId = controller.sessionId
    init {
        require(controller.state.value in setOf(ArSessionState.SCANNING, ArSessionState.TRACKING,
            ArSessionState.TRACKING_LOST))
    }
    override suspend fun execute(message: ArMessage): ArMessage.Result? = withContext(glDispatcher) {
        require(message.sessionId == sessionId)
        when (message) {
            is ArMessage.Create -> {
                val result = controller.createMarker(sessionId, message.id, message.kind, message.request, AnnotationAuthor.GUIDE)
                ArMessage.Result(sessionId, message.id, (result as? MarkerResult.Rejected)?.reason)
            }
            is ArMessage.Remove -> { controller.removeMarker(sessionId, message.id, AnnotationAuthor.GUIDE); null }
            is ArMessage.Clear -> { controller.clearMarkers(sessionId, AnnotationAuthor.GUIDE); null }
            else -> null
        }
    }
    override suspend fun close() = withContext(glDispatcher) { controller.close() }
}
