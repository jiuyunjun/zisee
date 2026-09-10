package com.lazydoglab.zisee.ar.collaboration

import com.lazydoglab.zisee.ar.annotation.ArDecodeResult
import com.lazydoglab.zisee.ar.annotation.ArMessage
import com.lazydoglab.zisee.ar.annotation.ArProtocol
import com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest
import com.lazydoglab.zisee.ar.session.MarkerKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.UUID

/** Owns only the AR channel and explicitly attached field controller, never the PeerConnection. */
class ArDataChannel(
    private val channel: DataChannel,
    private val dispatcher: CoroutineDispatcher,
    localWinsFieldConflict: Boolean,
    private val onFailure: () -> Unit,
) {
    private sealed interface Input {
        data object State : Input
        data class Message(val bytes: ByteArray) : Input
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = Channel<Input>(32)
    private val mutex = Mutex()
    private val budget = ArReceiveBudget()
    @Volatile private var stopping = false
    @Volatile private var failed = false
    private val collaboration = ArCollaboration(localWinsFieldConflict, ::send)
    val state = collaboration.state

    private fun send(message: ArMessage): Boolean {
        if (stopping || channel.state() != DataChannel.State.OPEN) return false
        val bytes = ArProtocol.encode(message)
        if (channel.bufferedAmount() + bytes.size > 65_536) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun offer(input: Input) {
        if (!stopping && !inbox.trySend(input).isSuccess) abort()
    }
    private fun abort() {
        failed = true; stopping = true
        inbox.close()
    }
    private val observer = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = offer(Input.State)
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (stopping) return
            if (buffer.binary || buffer.data.remaining() > ArProtocol.MAX_BYTES) { abort(); return }
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.duplicate().get(bytes)
            offer(Input.Message(bytes))
        }
    }

    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            for (input in inbox) {
                if (stopping) break
                mutex.withLock {
                    when (input) {
                        Input.State -> when (channel.state()) {
                            DataChannel.State.OPEN -> collaboration.connected()
                            DataChannel.State.CLOSING, DataChannel.State.CLOSED -> stopping = true
                            else -> Unit
                        }
                        is Input.Message -> {
                            check(budget.accept(System.nanoTime() / 1_000_000))
                            val decoded = ArProtocol.decode(input.bytes)
                            check(decoded is ArDecodeResult.Message)
                            collaboration.receive(decoded.value)
                        }
                    }
                }
                if (stopping) break
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally {
            stopping = true
            inbox.cancel()
            withContext(NonCancellable) {
                mutex.withLock {
                    try { collaboration.close() } catch (_: Exception) { failed = true }
                    try { channel.unregisterObserver() } catch (_: Exception) { failed = true }
                    try { channel.close() } catch (_: Exception) { failed = true }
                    try { channel.dispose() } catch (_: Exception) { failed = true }
                }
                if (failed) onFailure()
            }
        }
    }

    init {
        channel.registerObserver(observer)
        offer(Input.State)
        worker.start()
    }

    suspend fun attach(endpoint: ArFieldEndpoint): Boolean = access { collaboration.attach(endpoint) }
    suspend fun detach() = withContext(dispatcher) { mutex.withLock { collaboration.detach() } }
    suspend fun join(sessionId: UUID): Boolean = access { collaboration.join(sessionId) }
    suspend fun leave() = withContext(dispatcher) { mutex.withLock { collaboration.leave() } }
    suspend fun create(id: UUID, kind: MarkerKind, request: SpatialMarkerRequest): Boolean =
        access { collaboration.create(id, kind, request) }
    suspend fun remove(id: UUID): Boolean = access { collaboration.remove(id) }
    suspend fun clear(): Boolean = access { collaboration.clear() }
    suspend fun announceFieldClear(): Boolean = access { collaboration.announceFieldClear() }
    suspend fun revokeGuide(): Boolean = access { collaboration.revokeGuide() }
    private suspend fun access(block: () -> Boolean): Boolean = withContext(dispatcher) {
        mutex.withLock { !stopping && block() }
    }

    suspend fun close() = withContext(NonCancellable + dispatcher) {
        stopping = true
        worker.cancelAndJoin()
        scope.cancel()
    }
}
