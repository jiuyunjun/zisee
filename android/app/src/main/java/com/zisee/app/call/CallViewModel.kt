package com.zisee.app.call

import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zisee.app.auth.LocalIdentity
import com.zisee.app.auth.remote.AccessSession
import com.zisee.app.call.state.CallEvent
import com.zisee.app.call.state.CallPhase
import com.zisee.app.call.state.CallReducer
import com.zisee.app.call.state.CallSession
import com.zisee.app.call.state.CallState
import com.zisee.app.core.AppContainer
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.FailureReason
import com.zisee.app.invite.InviteLink
import com.zisee.app.rtc.IceState
import com.zisee.app.rtc.MediaStats
import com.zisee.app.rtc.NativeRtcSession
import com.zisee.app.rtc.VideoFeed
import com.zisee.app.signaling.MediaSignaling
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class CallUiState(
    val visible: Boolean = false, val busy: Boolean = false, val invite: String = "",
    val status: String = "邀请朋友开始视频通话，或输入对方的邀请码。",
    val machine: CallState = CallState(), val local: VideoFeed? = null, val remote: VideoFeed? = null,
    val cameraEnabled: Boolean = true, val muted: Boolean = false, val stats: MediaStats = MediaStats(),
    val pendingInvite: String = "", val contacts: List<Contact> = emptyList(), val contactsStatus: String = "",
    val peerName: String = "对方",
    val localBack: VideoFeed? = null, val remoteBack: VideoFeed? = null,
    val showMe: com.zisee.app.rtc.ShowMeState = com.zisee.app.rtc.ShowMeState(),
    val speakerOn: Boolean = true, val showMeHint: Boolean = false,
    val remotePresentation: com.zisee.app.rtc.CameraPresentation = com.zisee.app.rtc.CameraPresentation(com.zisee.app.rtc.CameraMode.FACE, true),
)

/** Serial foreground call owner. Backgrounding cancels capture and ends the remote call. */
class CallViewModel(application: Application, private val container: AppContainer) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(CallUiState())
    val state = mutable.asStateFlow()
    private var identity: LocalIdentity? = null
    private var job: Job? = null
    private var rtc: NativeRtcSession? = null
    private val commands = Channel<String>(4)
    private var foreground = false
    private var idleJob: Job? = null
    private var cameraJob: Job? = null
    private val removals = Channel<String>(4)
    private var showMeHintSeen = true

    init {
        viewModelScope.launch { container.callPreferences.showMeHintSeen.collect { showMeHintSeen = it } }
    }

    fun observeIdentity(value: LocalIdentity) {
        if (identity?.identityId != value.identityId) {
            idleJob?.cancel(); idleJob = null
            mutable.update { it.copy(contacts = emptyList()) }
        }
        identity = value
        startIdle()
    }
    fun callContact(peer: String) = begin(null, contact = peer)
    fun removeContact(peer: String) { removals.trySend(peer) }

    private fun startIdle() {
        val api = container.backendApi ?: return
        val owner = identity ?: return
        if (!foreground || job != null || idleJob?.isActive == true) return
        idleJob = viewModelScope.launch {
            while (isActive && foreground && job == null) {
                var token: AccessSession? = null
                var transport: MediaSignaling? = null
                try {
                    token = api.login(owner, container.deviceSigner(owner.identityId))
                    val calls = CallApi(api)
                    transport = MediaSignaling(api, container.httpClient, token)
                    transport.ready()
                    var refreshAt = 0L
                    while (isActive && foreground && job == null && Instant.now().isBefore(token.expiresAt.minusSeconds(30))) {
                        removals.tryReceive().getOrNull()?.let { calls.removeContact(token, it); refreshAt = 0 }
                        if (System.nanoTime() >= refreshAt) {
                            val contacts = calls.contacts(token)
                            mutable.update { it.copy(contacts = contacts, contactsStatus = "") }
                            refreshAt = System.nanoTime() + 15_000_000_000L
                        }
                        val snapshot = transport.exchange("call.sync")
                        if (!snapshot.isNull("call")) {
                            val incoming = RemoteCall.parse(snapshot.getJSONObject("call"))
                            if (incoming.state == "ringing" && incoming.callee == owner.identityId) {
                                begin(null, incoming = incoming)
                                break
                            }
                        }
                        kotlinx.coroutines.delay(1_500)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    container.logger.error(AppEvent.CALL_FAILED, FailureReason.of(error))
                    mutable.update { it.copy(contactsStatus = "联系人连接暂不可用，正在重试…") }
                } finally {
                    transport?.close()
                    withContext(NonCancellable) {
                        withTimeoutOrNull(1_000) {
                            try { token?.let { api.logout(it) } }
                            catch (error: IOException) { container.logger.error(AppEvent.SESSION_LOGOUT_FAILED) }
                        }
                    }
                }
                kotlinx.coroutines.delay(3_000)
            }
        }
    }

    fun open(identity: LocalIdentity) {
        // Returning here leaves this.identity unset, which would later make begin() refuse the
        // call with nothing on screen to explain it.
        if (job != null) return notStarted("open_during_call")
        this.identity = identity
        mutable.value = CallUiState(visible = true, contacts = mutable.value.contacts, status = if (container.backendApi == null)
            "尚未配置通话服务器。请安装已配置服务器的测试版本。" else "邀请朋友开始视频通话，或输入对方的邀请码。")
    }
    fun setForeground(value: Boolean) { foreground = value; if (!value) { idleJob?.cancel(); stop() } else startIdle() }
    fun close() { stop(); mutable.update { it.copy(visible = false) } }
    fun stop() { job?.cancel() }
    fun accept() { commands.trySend("accept") }
    fun reject() { commands.trySend("reject") }
    fun permissionsDenied() { mutable.update { it.copy(status = "视频通话需要摄像头和麦克风权限，请允许后重试。") } }
    fun createInvite() = begin(null)
    fun join(invite: String) {
        val token = InviteLink.token(invite)
        if (token == null) {
            mutable.update { it.copy(status = "请输入完整的邀请码或邀请链接。") }; return
        }
        begin(token)
    }

    /**
     * Fills in a code that arrived from a scanned or opened link. Placing the call stays an
     * explicit tap: a link must never be able to switch on the camera by itself.
     */
    fun prefill(invite: String) {
        val token = InviteLink.token(invite) ?: return
        mutable.update { it.copy(pendingInvite = token, status = "已填入邀请码，点\u201C呼叫对方\u201D开始通话。") }
    }
    fun toggleShowMe(preferDual: Boolean = true) {
        val media = rtc ?: return
        if (cameraJob?.isActive == true) return
        cameraJob = viewModelScope.launch { media.toggleShowMe(preferDual) }
    }
    fun toggleSpeaker() {
        val current = rtc ?: return
        val target = !mutable.value.speakerOn
        viewModelScope.launch {
            val applied = current.setSpeaker(target)
            if (rtc === current) mutable.update { it.copy(speakerOn = applied) }
        }
    }

    /** The Show Me hint teaches the swap gesture once per install, never on every call. */
    fun dismissShowMeHint() {
        showMeHintSeen = true
        mutable.update { it.copy(showMeHint = false) }
        viewModelScope.launch { container.callPreferences.markShowMeHintSeen() }
    }

    /** Mirrors this screen's layout back to the sender so a thumbnail is not encoded full size. */
    fun reportViewLayout(frontLarge: Boolean, backLarge: Boolean) {
        val current = rtc ?: return
        viewModelScope.launch {
            current.reportViewLayout(
                if (frontLarge) com.zisee.app.rtc.ViewSize.LARGE else com.zisee.app.rtc.ViewSize.SMALL,
                if (backLarge) com.zisee.app.rtc.ViewSize.LARGE else com.zisee.app.rtc.ViewSize.SMALL)
        }
    }

    fun toggleMute() {
        val current = rtc ?: return
        val enabled = mutable.value.muted
        viewModelScope.launch {
            try {
                current.setTrackEnabled(com.zisee.app.media.MediaTrack.MICROPHONE, enabled)
                if (rtc === current) mutable.update { it.copy(muted = !enabled) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { container.logger.error(AppEvent.RTC_MEDIA_FAILED); stop() }
        }
    }

    fun toggleCamera() {
        val current = rtc ?: return
        val enabled = !mutable.value.cameraEnabled
        viewModelScope.launch {
            try {
                current.setTrackEnabled(com.zisee.app.media.MediaTrack.FRONT_CAMERA, enabled)
                if (rtc === current) mutable.update { it.copy(cameraEnabled = enabled) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { container.logger.error(AppEvent.RTC_MEDIA_FAILED); stop() }
        }
    }

    /**
     * Every refusal below used to be a bare return, so a tap that started nothing looked identical
     * to a tap that was never delivered. Naming them costs one line and one log each.
     */
    private fun notStarted(reason: String) = container.logger.info(AppEvent.CALL_NOT_STARTED, reason)

    private fun begin(invite: String?, contact: String? = null, incoming: RemoteCall? = null) {
        val api = container.backendApi ?: return notStarted("no_backend")
        val identity = identity ?: return notStarted("no_identity")
        if (!foreground) return notStarted("background")
        if (job != null) return notStarted("call_in_progress")
        while (commands.tryReceive().isSuccess) { /* Clear previous call actions. */ }
        mutable.value = CallUiState(visible = true, busy = true, contacts = mutable.value.contacts, status = "正在连接…")
        job = viewModelScope.launch {
            idleJob?.cancelAndJoin(); idleJob = null
            var session: AccessSession? = null
            var socket: MediaSignaling? = null
            var remote: RemoteCall? = incoming
            val calls = CallApi(api)
            var ended = false
            var mediaObservation: Job? = null
            var cameraObservation: Job? = null
            var networkWatcher: com.zisee.app.rtc.DefaultNetworkWatcher? = null
            var networkObservation: Job? = null
            var machine = CallState()
            fun event(event: CallEvent) {
                machine.session?.let { machine = CallReducer.reduce(machine, it.callId, event) }
                mutable.update { it.copy(machine = machine) }
            }
            try {
                session = api.login(identity, container.deviceSigner(identity.identityId))
                var inviteExpiry: Instant? = null
                if (contact != null) remote = calls.callContact(session, contact)
                else if (incoming == null && invite == null) {
                    val created = calls.invite(session)
                    inviteExpiry = created.second
                    mutable.update { it.copy(invite = created.first, status = "将邀请码发给对方，等待来电。") }
                } else if (invite != null) remote = calls.redeem(session, invite)
                var negotiator: com.zisee.app.signaling.MediaNegotiator? = null
                val recovery = com.zisee.app.rtc.IceRecoveryPolicy(System.nanoTime() / 1_000_000)
                val network = com.zisee.app.rtc.DefaultNetworkWatcher(getApplication<Application>(), container.logger)
                network.start()
                networkWatcher = network
                val signalingRetry = com.zisee.app.signaling.SignalingRetryPolicy()
                val routeWake = Channel<Unit>(Channel.CONFLATED)
                var socketNetworkVersion = network.version.value
                networkObservation = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    network.version.drop(1).collectLatest {
                        rtc?.networkChanged()
                        kotlinx.coroutines.delay(com.zisee.app.rtc.WebRtcRecoveryConfig().networkDebounceMs)
                        socket?.networkChanged()
                        routeWake.trySend(Unit)
                    }
                }
                while (isActive) {
                    if (session == null || !Instant.now().isBefore(session.expiresAt.minusSeconds(30))) {
                        socket?.close(); socket = null
                        session = api.login(identity, container.deviceSigner(identity.identityId))
                    }
                    try {
                        if (socket == null) {
                            socketNetworkVersion = network.version.value
                            socket = MediaSignaling(api, container.httpClient, session)
                            socket.ready()
                        }
                        var current = remote
                        if (current == null) {
                            if (inviteExpiry != null && !Instant.now().isBefore(inviteExpiry)) throw IOException("invite_expired")
                            val snapshot = socket.exchange("call.sync")
                            if (!snapshot.isNull("call")) {
                                current = RemoteCall.parse(snapshot.getJSONObject("call")); remote = current
                            }
                        } else {
                            current = RemoteCall.parse(socket.exchange("call.sync", JSONObject().put("callId", current.id)).getJSONObject("call"))
                            remote = current
                        }
                        if (current != null) {
                            if (machine.phase == CallPhase.IDLE) {
                                val peer = if (current.caller == identity.identityId) current.callee else current.caller
                                machine = CallReducer.start(machine, CallSession(current.id, peer), current.callee == identity.identityId)
                                mutable.update { it.copy(machine = machine, invite = "", peerName = it.contacts.firstOrNull { c -> c.identityId == peer }?.displayName ?: "对方") }
                            }
                            if (current.state in setOf("ended", "rejected", "expired")) {
                                ended = true
                                mutable.update { it.copy(status = when (current.state) { "rejected" -> "对方已拒绝通话。"; "expired" -> "通话已超时。"; else -> "通话已结束。" }) }
                                break
                            }
                            if (current.state == "ringing") {
                                mutable.update { it.copy(status = if (current.callee == identity.identityId) "收到视频来电。接听后开启摄像头和麦克风。" else "正在呼叫，等待对方接听…") }
                                when (commands.tryReceive().getOrNull()) {
                                    "accept" -> { remote = calls.action(session, current.id, "accept") }
                                    "reject" -> { remote = calls.action(session, current.id, "reject") }
                                }
                            } else if (current.state == "accepted") {
                                if (rtc == null) {
                                    event(CallEvent.ACCEPT)
                                    mutable.update { it.copy(status = "正在建立音视频连接…") }
                                    // Relay credentials are short lived, so fetch them per call rather
                                    // than at login. Losing TURN degrades to direct-only, never fatal here.
                                    val ice = calls.iceServers(session)
                                    val media = NativeRtcSession(getApplication<Application>(), container.logger)
                                    rtc = media // Assign before start so partial initialization is always released.
                                    media.start(ice)
                                    recovery.initialNegotiationStarted(System.nanoTime() / 1_000_000, network.version.value)
                                    mediaObservation = launch {
                                        combine(media.mediaStats, media.iceState) { stats, iceState -> stats to iceState }
                                            .collect { (stats, iceState) ->
                                                when (iceState) {
                                                    IceState.CONNECTED -> if (negotiator?.complete == true) event(CallEvent.MEDIA_CONNECTED)
                                                    IceState.DISCONNECTED -> event(CallEvent.CONNECTION_LOST)
                                                    else -> Unit
                                                }
                                                mutable.update { it.copy(stats = stats) }
                                            }
                                    }
                                    cameraObservation = launch {
                                        combine(media.showMe, media.remotePresentation) { local, remote -> local to remote }.collect { (local, remote) ->
                                            // The hint explains swapping the main view, so it waits for a
                                            // second remote view to actually exist.
                                            val hint = !showMeHintSeen && remote.mode == com.zisee.app.rtc.CameraMode.DUAL
                                            mutable.update { it.copy(showMe = local, remotePresentation = remote, showMeHint = it.showMeHint || hint) }
                                        }
                                    }
                                    mutable.update { it.copy(local = media.localFeed, remote = media.remoteFeed, localBack = media.localBackFeed, remoteBack = media.remoteBackFeed) }
                                    negotiator = com.zisee.app.signaling.MediaNegotiator(media, current.caller == identity.identityId) {
                                        calls.iceServers(requireNotNull(session))
                                    }
                                }
                                val negotiation = requireNotNull(negotiator)
                                val wasCheckingRoute = recovery.checkingRoute
                                val action = recovery.evaluate(requireNotNull(rtc).iceState.value, network.version.value,
                                    negotiation.complete, System.nanoTime() / 1_000_000, requireNotNull(rtc).mediaStats.value)
                                if (wasCheckingRoute && !recovery.checkingRoute && recovery.lastNaturalRecoveryMs != null) {
                                    container.logger.info(AppEvent.RTC_ROUTE_RECOVERED, "durationMs=${recovery.lastNaturalRecoveryMs}")
                                }
                                if (action == com.zisee.app.rtc.IceRecoveryPolicy.Action.FAIL) throw IOException("ice_timeout")
                                if (action == com.zisee.app.rtc.IceRecoveryPolicy.Action.RESTART) {
                                    event(CallEvent.CONNECTION_LOST)
                                    mutable.update { it.copy(status = "网络已变化，正在恢复通话…") }
                                }
                                if (negotiation.exchange(socket, current.id, action == com.zisee.app.rtc.IceRecoveryPolicy.Action.RESTART)) {
                                    recovery.generationStarted(System.nanoTime() / 1_000_000, network.version.value)
                                    event(CallEvent.CONNECTION_LOST)
                                }
                                if (negotiation.complete && requireNotNull(rtc).iceState.value == IceState.CONNECTED) event(CallEvent.MEDIA_CONNECTED)
                                val stats = requireNotNull(rtc).mediaStats.value
                                mutable.update { it.copy(stats = stats, status = if (stats.videoFrames > 0 && stats.audioReceived > 0 && machine.phase == CallPhase.CONNECTED) "音视频已连接" else it.status) }
                            }
                        }
                        signalingRetry.recovered()
                        withTimeoutOrNull(if (recovery.checkingRoute || machine.phase in setOf(CallPhase.CONNECTING, CallPhase.RECONNECTING)) 100L else 1_000L) {
                            routeWake.receive()
                        }
                    } catch (error: IOException) {
                        // Retry transport failures using the same SDP message ID and receive cursor.
                        // Protocol, media and authorization failures are terminal in this first version.
                        val transportFailure = (error is com.zisee.app.auth.remote.AuthFailure && error.reason == com.zisee.app.auth.remote.AuthFailure.Reason.NETWORK) || error.message?.startsWith("signaling_") == true
                        if (!transportFailure) throw error
                        socket?.close(); socket = null
                        val retryDelay = signalingRetry.nextDelayMs(System.nanoTime() / 1_000_000,
                            machine.phase in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)) ?: throw error
                        mutable.update { it.copy(status = "信令中断，正在重连…") }
                        if (socketNetworkVersion == network.version.value) {
                            withTimeoutOrNull(retryDelay) { routeWake.receive() }
                        }
                    }
                }
                event(CallEvent.HANG_UP); event(CallEvent.RELEASED)
            } catch (error: TimeoutCancellationException) {
                event(CallEvent.FAIL); mutable.update { it.copy(status = "连接超时，请确认两台手机网络后重试。") }
                container.logger.error(AppEvent.CALL_FAILED, FailureReason.of(error))
            } catch (error: CancellationException) {
                event(CallEvent.HANG_UP); event(CallEvent.RELEASED)
                mutable.update { it.copy(status = "通话已结束。") }
                throw error
            } catch (error: Exception) {
                event(CallEvent.FAIL); mutable.update { it.copy(status = "通话未能建立，请确认邀请码、权限和网络后重试。") }
                container.logger.error(AppEvent.CALL_FAILED, FailureReason.of(error))
            } finally {
                socket?.close()
                withContext(NonCancellable) {
                    cameraJob?.cancelAndJoin(); cameraJob = null
                    cameraObservation?.cancelAndJoin()
                    networkObservation?.cancelAndJoin()
                    networkWatcher?.close()
                    mediaObservation?.cancelAndJoin()
                    val media = rtc; rtc = null
                    mutable.update { it.copy(local = null, remote = null, localBack = null, remoteBack = null, invite = "") }
                    try { media?.release() } catch (error: Exception) { container.logger.error(AppEvent.RTC_RELEASE_FAILED) }
                    val token = session
                    if (token != null) {
                        withTimeoutOrNull(3_000) {
                            try { if (!ended) remote?.let { calls.action(token, it.id, "end") } }
                            catch (error: IOException) { container.logger.error(AppEvent.CALL_CLEANUP_FAILED) }
                        }
                        withTimeoutOrNull(3_000) {
                            try { api.logout(token) } catch (error: IOException) { container.logger.error(AppEvent.SESSION_LOGOUT_FAILED) }
                        }
                    }
                }
                mutable.update { it.copy(busy = false) }
                job = null
                startIdle()
            }
        }
    }

    companion object {
        fun factory(context: Context, container: AppContainer) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CallViewModel::class.java))
                @Suppress("UNCHECKED_CAST") return CallViewModel(context.applicationContext as Application, container) as T
            }
        }
    }
}
