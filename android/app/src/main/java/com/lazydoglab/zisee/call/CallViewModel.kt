package com.lazydoglab.zisee.call

import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lazydoglab.zisee.auth.LocalIdentity
import com.lazydoglab.zisee.auth.remote.AccessSession
import com.lazydoglab.zisee.call.state.CallEvent
import com.lazydoglab.zisee.call.state.CallPhase
import com.lazydoglab.zisee.call.state.CallReducer
import com.lazydoglab.zisee.call.state.CallSession
import com.lazydoglab.zisee.call.state.CallState
import com.lazydoglab.zisee.core.AppContainer
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.FailureReason
import com.lazydoglab.zisee.invite.InviteLink
import com.lazydoglab.zisee.rtc.IceState
import com.lazydoglab.zisee.rtc.MediaStats
import com.lazydoglab.zisee.rtc.NativeRtcSession
import com.lazydoglab.zisee.rtc.VideoFeed
import com.lazydoglab.zisee.signaling.MediaSignaling
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
    /** This end is publishing an invitation and waiting to be joined, not placing a call. */
    val inviting: Boolean = false,
    /** A message for the home screen (permissions, a malformed code). Call status lives in [status]. */
    val notice: String = "",
    val status: String = "邀请朋友开始视频通话，或输入对方的邀请码。",
    val machine: CallState = CallState(), val local: VideoFeed? = null, val remote: VideoFeed? = null,
    val cameraEnabled: Boolean = true, val muted: Boolean = false, val stats: MediaStats = MediaStats(),
    val pendingInvite: String = "", val contacts: List<Contact> = emptyList(), val contactsStatus: String = "",
    val peerName: String = "对方",
    val localBack: VideoFeed? = null, val remoteBack: VideoFeed? = null,
    val showMe: com.lazydoglab.zisee.rtc.ShowMeState = com.lazydoglab.zisee.rtc.ShowMeState(),
    val speakerOn: Boolean = true, val showMeHint: Boolean = false,
    val arState: com.lazydoglab.zisee.ar.session.ArSessionState = com.lazydoglab.zisee.ar.session.ArSessionState.IDLE,
    val arNotice: String = "",
    val arCollaboration: com.lazydoglab.zisee.ar.collaboration.ArCollaborationState =
        com.lazydoglab.zisee.ar.collaboration.ArCollaborationState(),
    val arOwnMarkerCount: Int = 0,
    val remotePresentation: com.lazydoglab.zisee.rtc.CameraPresentation = com.lazydoglab.zisee.rtc.CameraPresentation(com.lazydoglab.zisee.rtc.CameraMode.FACE, true),
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
    private val arActivation = com.lazydoglab.zisee.ar.session.ArActivationGate()
    private val arOwnMarkers = ArrayDeque<Pair<java.util.UUID, java.util.UUID>>()
    private var lastArResult: Pair<java.util.UUID, java.util.UUID>? = null
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
                    val signer = container.deviceSigner(owner.identityId)
                    token = api.login(owner, signer)
                    val calls = CallApi(api)
                    // Keep the backend Device Registry current so a push can reach
                    // this device after the process is gone (CALL_DELIVERY.md §24).
                    runCatching { container.pushTokenManager.sync(calls, token, signer.prepare().deviceId) }
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

    fun setForeground(value: Boolean) { foreground = value; if (!value) { idleJob?.cancel(); stop() } else startIdle() }
    fun close() { stop(); mutable.update { it.copy(visible = false) } }
    fun stop() { arActivation.invalidate(); job?.cancel() }
    fun accept() { commands.trySend("accept") }
    fun reject() { commands.trySend("reject") }
    fun permissionsDenied() { mutable.update { it.copy(notice = "视频通话需要摄像头和麦克风权限，请允许后重试。") } }
    fun createInvite() = begin(null)
    fun join(invite: String) {
        val token = InviteLink.token(invite)
        if (token == null) {
            mutable.update { it.copy(notice = "请输入完整的邀请码或邀请链接。") }; return
        }
        begin(token)
    }

    /**
     * Fills in a code that arrived from a scanned or opened link. Placing the call stays an
     * explicit tap: a link must never be able to switch on the camera by itself.
     */
    fun prefill(invite: String) {
        val token = InviteLink.token(invite) ?: return
        mutable.update { it.copy(pendingInvite = token, notice = "") }
    }
    fun toggleShowMe(preferDual: Boolean = true) {
        val media = rtc ?: return
        if (cameraJob?.isActive == true) return
        cameraJob = viewModelScope.launch { media.toggleShowMe(preferDual) }
    }

    fun setArResumed(resumed: Boolean) {
        arActivation.setResumed(resumed)
        if (!resumed) stopAr()
    }

    fun beginArRequest(): Long? {
        if (!foreground || rtc == null || !state.value.cameraEnabled || cameraJob?.isActive == true ||
            state.value.arCollaboration.remote != null) return null
        return arActivation.begin()
    }

    fun arNotice(message: String) { mutable.update { it.copy(arNotice = message) } }

    fun startAr(request: Long, rotation: Int, width: Int, height: Int) {
        val media = rtc ?: return
        if (!arActivation.consume(request) || !foreground || cameraJob?.isActive == true) return
        arNotice("")
        cameraJob = viewModelScope.launch {
            val started = media.startAr(com.lazydoglab.zisee.ar.session.ArPreparation.READY, rotation, width, height)
            if (!started && rtc === media) arNotice("AR 未能启动，正在恢复普通摄像头。可稍后重试。")
        }
    }

    fun stopAr() {
        arActivation.invalidate()
        val media = rtc ?: return
        viewModelScope.launch {
            try { media.stopAr() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                container.logger.error(AppEvent.AR_CHANNEL_FAILED)
                if (rtc === media) arNotice("AR 退出失败，请结束通话后重试。")
            }
        }
    }

    fun updateArGeometry(rotation: Int, width: Int, height: Int) {
        val media = rtc ?: return
        viewModelScope.launch {
            try { media.updateArGeometry(rotation, width, height) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                container.logger.error(AppEvent.AR_CHANNEL_FAILED)
                if (rtc === media) { arNotice("AR 显示方向更新失败，已退出 AR。"); stopAr() }
            }
        }
    }

    fun joinRemoteAr() {
        val media = rtc ?: return
        val session = state.value.arCollaboration.remote?.sessionId ?: return
        viewModelScope.launch {
            if (!media.joinRemoteAr(session) && rtc === media) arNotice("暂时无法加入对方现场，请重试。")
        }
    }

    fun leaveRemoteAr() {
        val media = rtc ?: return
        viewModelScope.launch { media.leaveRemoteAr() }
    }

    fun createArMarker(frame: com.lazydoglab.zisee.rtc.TextureViewRenderer.DisplayedArFrame,
        point: com.lazydoglab.zisee.ar.annotation.VideoPoint,
        kind: com.lazydoglab.zisee.ar.session.MarkerKind = com.lazydoglab.zisee.ar.session.MarkerKind.PIN) {
        val media = rtc ?: return
        val id = java.util.UUID.randomUUID()
        val remote = state.value.arCollaboration.remote?.sessionId == frame.identity.sessionId
        viewModelScope.launch {
            if (media.createArMarker(frame.identity, id, kind, point) && rtc === media) {
                arOwnMarkers.addLast(frame.identity.sessionId to id)
                mutable.update { it.copy(arOwnMarkerCount = arOwnMarkers.size,
                    arNotice = if (remote) "正在确认标记…" else "标记已确认。") }
            } else if (rtc === media) arNotice("暂时无法固定在这里，请让画面对准表面后重试。")
        }
    }

    fun undoArMarker() {
        val media = rtc ?: return
        val marker = arOwnMarkers.removeLastOrNull() ?: return
        mutable.update { it.copy(arOwnMarkerCount = arOwnMarkers.size) }
        viewModelScope.launch {
            if (!media.removeArMarker(marker.first, marker.second) && rtc === media)
                arNotice("这个标记已不存在或现场已经变化。")
        }
    }

    fun clearOwnArMarkers() {
        val current = rtc ?: return
        val markers = arOwnMarkers.toList()
        if (markers.isEmpty()) return
        arOwnMarkers.clear()
        mutable.update { it.copy(arOwnMarkerCount = 0) }
        viewModelScope.launch {
            var complete = true
            markers.forEach { (session, id) -> if (!current.removeArMarker(session, id)) complete = false }
            if (!complete && rtc === current) arNotice("部分标记已不在当前现场，其余标记已清除。")
        }
    }
    fun toggleSpeaker() {
        val current = rtc ?: return
        val target = !mutable.value.speakerOn
        viewModelScope.launch {
            val applied = current.setSpeaker(target)
            if (rtc === current) mutable.update { it.copy(speakerOn = applied) }
        }
    }

    fun setNoiseSuppression(mode: com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode) {
        val current = rtc ?: return
        viewModelScope.launch { current.setNoiseSuppression(mode) }
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
                if (frontLarge) com.lazydoglab.zisee.rtc.ViewSize.LARGE else com.lazydoglab.zisee.rtc.ViewSize.SMALL,
                if (backLarge) com.lazydoglab.zisee.rtc.ViewSize.LARGE else com.lazydoglab.zisee.rtc.ViewSize.SMALL)
        }
    }

    fun toggleMute() {
        val current = rtc ?: return
        val enabled = mutable.value.muted
        viewModelScope.launch {
            try {
                current.setTrackEnabled(com.lazydoglab.zisee.media.MediaTrack.MICROPHONE, enabled)
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
                if (!enabled && mutable.value.showMe.mode == com.lazydoglab.zisee.rtc.CameraMode.AR) current.stopAr()
                current.setTrackEnabled(com.lazydoglab.zisee.media.MediaTrack.FRONT_CAMERA, enabled)
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
        // Publishing an invitation is not placing a call: say so from the first frame, so the
        // invite page opens directly instead of flashing a "calling 对方" screen at nobody.
        val publishing = invite == null && contact == null && incoming == null
        mutable.value = CallUiState(visible = true, busy = true, inviting = publishing,
            contacts = mutable.value.contacts,
            status = if (publishing) "正在生成邀请…" else "正在连接…")
        job = viewModelScope.launch {
            idleJob?.cancelAndJoin(); idleJob = null
            var session: AccessSession? = null
            var socket: MediaSignaling? = null
            var remote: RemoteCall? = incoming
            val calls = CallApi(api)
            // Relay credentials are issued for an hour, but they were re-fetched for every ICE
            // restart, over the network that had just died. A restart is exactly when that request
            // cannot succeed and exactly when losing the relay hurts most, so it is fetched once
            // and reused, and a failed refresh falls back to what is still valid.
            var iceCache: Pair<List<com.lazydoglab.zisee.call.IceServerConfig>, Long>? = null
            suspend fun iceServers(current: AccessSession): List<com.lazydoglab.zisee.call.IceServerConfig> {
                val nowMs = System.nanoTime() / 1_000_000
                iceCache?.let { (servers, atMs) -> if (nowMs - atMs < ICE_REUSE_MS) return servers }
                return try {
                    calls.iceServers(current).also { iceCache = it to nowMs }
                } catch (error: IOException) {
                    iceCache?.first ?: throw error
                }
            }
            var ended = false
            var mediaObservation: Job? = null
            var cameraObservation: Job? = null
            var candidateObservation: Job? = null
            var networkWatcher: com.lazydoglab.zisee.rtc.DefaultNetworkWatcher? = null
            var networkObservation: Job? = null
            var losingObservation: Job? = null
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
                var negotiator: com.lazydoglab.zisee.signaling.MediaNegotiator? = null
                val recovery = com.lazydoglab.zisee.rtc.IceRecoveryPolicy(System.nanoTime() / 1_000_000)
                val network = com.lazydoglab.zisee.rtc.DefaultNetworkWatcher(getApplication<Application>(), container.logger)
                network.start()
                networkWatcher = network
                val signalingRetry = com.lazydoglab.zisee.signaling.SignalingRetryPolicy()
                val routeWake = Channel<Unit>(Channel.CONFLATED)
                var socketNetworkVersion = network.version.value
                // Preparing for the break is worth nothing once it has happened, so this does not
                // share the route collector: that one debounces and performs signaling IO. It
                // collects forever, so like every other observer here it is cancelled explicitly.
                losingObservation = launch { network.losing.drop(1).collect { rtc?.routeLosing() } }
                networkObservation = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    network.version.drop(1).collectLatest {
                        recovery.networkChanged(it, System.nanoTime() / 1_000_000)
                        routeWake.trySend(Unit)
                        rtc?.networkChanged()
                        kotlinx.coroutines.delay(com.lazydoglab.zisee.rtc.WebRtcRecoveryConfig().networkDebounceMs)
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
                                // The invitation has been taken up: this is a real call now, not a
                                // page of QR code waiting to be scanned.
                                mutable.update { it.copy(machine = machine, invite = "", inviting = false,
                                    peerName = it.contacts.firstOrNull { c -> c.identityId == peer }?.displayName ?: "对方") }
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
                                    val ice = iceServers(session)
                                    val media = NativeRtcSession(getApplication<Application>(), container.logger,
                                        current.caller == identity.identityId)
                                    rtc = media // Assign before start so partial initialization is always released.
                                    media.start(ice)
                                    candidateObservation = launch {
                                        media.localCandidateRevision.collect { routeWake.trySend(Unit) }
                                    }
                                    recovery.initialNegotiationStarted(System.nanoTime() / 1_000_000, network.version.value)
                                    mediaObservation = launch {
                                        combine(media.mediaStats, media.iceState, media.audioDeviceState) { stats, iceState, device -> Triple(stats, iceState, device) }
                                            .collect { (stats, iceState, device) ->
                                                // Crediting the new route here, where samples land,
                                                // keeps signaling IO in the recovery loop from
                                                // outlasting a switch that already worked.
                                                if (recovery.observeMedia(iceState, stats, System.nanoTime() / 1_000_000)) {
                                                    container.logger.info(AppEvent.RTC_ROUTE_RECOVERED,
                                                        "durationMs=${recovery.lastNaturalRecoveryMs}")
                                                    routeWake.trySend(Unit)
                                                }
                                                when (iceState) {
                                                    IceState.CONNECTED -> if (negotiator?.complete == true) event(CallEvent.MEDIA_CONNECTED)
                                                    IceState.DISCONNECTED -> event(CallEvent.CONNECTION_LOST)
                                                    else -> Unit
                                                }
                                                mutable.update { it.copy(stats = stats, speakerOn = device.output == com.lazydoglab.zisee.rtc.audio.AudioRoute.SPEAKER) }
                                            }
                                    }
                                    cameraObservation = launch {
                                        combine(media.showMe, media.remotePresentation, media.arState,
                                            media.arCollaborationState) { local, remote, ar, collaboration ->
                                            arrayOf(local, remote, ar, collaboration)
                                        }.collect { values ->
                                            val local = values[0] as com.lazydoglab.zisee.rtc.ShowMeState
                                            val remote = values[1] as com.lazydoglab.zisee.rtc.CameraPresentation
                                            val ar = values[2] as com.lazydoglab.zisee.ar.session.ArSessionState
                                            val collaboration = values[3] as com.lazydoglab.zisee.ar.collaboration.ArCollaborationState
                                            // The hint explains swapping the main view, so it waits for a
                                            // second remote view to actually exist.
                                            val hint = !showMeHintSeen && remote.mode == com.lazydoglab.zisee.rtc.CameraMode.DUAL
                                            val validSessions = setOfNotNull(collaboration.localSession, collaboration.remote?.sessionId)
                                            if (arOwnMarkers.any { it.first !in validSessions }) {
                                                arOwnMarkers.removeAll { it.first !in validSessions }
                                            }
                                            collaboration.lastResult?.let { result ->
                                                val key = result.sessionId to result.id
                                                if (lastArResult != key) {
                                                    lastArResult = key
                                                    if (result.rejection != null) arOwnMarkers.remove(key)
                                                    arNotice(if (result.rejection == null) "标记已确认。" else when (result.rejection) {
                                                        com.lazydoglab.zisee.ar.spatial.SpatialRejection.TRACKING_UNAVAILABLE ->
                                                            "对方正在重新识别环境，请稍后再试。"
                                                        com.lazydoglab.zisee.ar.spatial.SpatialRejection.FRAME_MISSING ->
                                                            "画面已变化，请重新点选。"
                                                        com.lazydoglab.zisee.ar.spatial.SpatialRejection.LIMIT_REACHED ->
                                                            "标记已满，请先清除一些。"
                                                        else -> "暂时无法固定在这里，请换个位置后重试。"
                                                    })
                                                }
                                            }
                                            mutable.update { it.copy(showMe = local, remotePresentation = remote,
                                                showMeHint = it.showMeHint || hint, arState = ar,
                                                arCollaboration = collaboration, arOwnMarkerCount = arOwnMarkers.size) }
                                        }
                                    }
                                    mutable.update { it.copy(local = media.localFeed, remote = media.remoteFeed, localBack = media.localBackFeed, remoteBack = media.remoteBackFeed) }
                                    negotiator = com.lazydoglab.zisee.signaling.MediaNegotiator(media, current.caller == identity.identityId) {
                                        iceServers(requireNotNull(session))
                                    }
                                }
                                val negotiation = requireNotNull(negotiator)
                                val wasCheckingRoute = recovery.checkingRoute
                                val action = recovery.evaluate(requireNotNull(rtc).iceState.value, network.version.value,
                                    negotiation.complete, System.nanoTime() / 1_000_000, requireNotNull(rtc).mediaStats.value)
                                if (wasCheckingRoute && !recovery.checkingRoute && recovery.lastNaturalRecoveryMs != null) {
                                    container.logger.info(AppEvent.RTC_ROUTE_RECOVERED, "durationMs=${recovery.lastNaturalRecoveryMs}")
                                }
                                if (action == com.lazydoglab.zisee.rtc.IceRecoveryPolicy.Action.FAIL) throw IOException("ice_timeout")
                                if (action == com.lazydoglab.zisee.rtc.IceRecoveryPolicy.Action.RESTART) {
                                    // A restart costs an outage of its own, so the reason it was
                                    // asked for has to be readable after the fact.
                                    container.logger.info(AppEvent.RTC_ICE_RESTART,
                                        "decided=${recovery.lastReason.name} negotiated=${negotiation.complete} " +
                                            "state=${requireNotNull(rtc).iceState.value.name}")
                                    event(CallEvent.CONNECTION_LOST)
                                    mutable.update { it.copy(status = "网络已变化，正在恢复通话…") }
                                }
                                if (negotiation.exchange(socket, current.id, action == com.lazydoglab.zisee.rtc.IceRecoveryPolicy.Action.RESTART)) {
                                    recovery.generationStarted(System.nanoTime() / 1_000_000, network.version.value)
                                    event(CallEvent.CONNECTION_LOST)
                                }
                                if (negotiation.complete && requireNotNull(rtc).iceState.value == IceState.CONNECTED) event(CallEvent.MEDIA_CONNECTED)
                                val stats = requireNotNull(rtc).mediaStats.value
                                mutable.update { it.copy(stats = stats, status = if (stats.videoFrames > 0 && stats.audioReceived > 0 && machine.phase == CallPhase.CONNECTED) "音视频已连接" else it.status) }
                            }
                        }
                        signalingRetry.recovered()
                        withTimeoutOrNull(if (recovery.checkingRoute || negotiator?.complete == false ||
                            machine.phase in setOf(CallPhase.CONNECTING, CallPhase.RECONNECTING)) 100L else 1_000L) {
                            routeWake.receive()
                        }
                    } catch (error: IOException) {
                        // Retry transport failures using the same SDP message ID and receive cursor.
                        // Protocol, media and authorization failures are terminal in this first version.
                        val transportFailure = (error is com.lazydoglab.zisee.auth.remote.AuthFailure && error.reason == com.lazydoglab.zisee.auth.remote.AuthFailure.Reason.NETWORK) || error.message?.startsWith("signaling_") == true
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
                    candidateObservation?.cancelAndJoin()
                    networkObservation?.cancelAndJoin()
                    losingObservation?.cancelAndJoin()
                    networkWatcher?.close()
                    mediaObservation?.cancelAndJoin()
                    arActivation.invalidate()
                    val media = rtc; rtc = null
                    arOwnMarkers.clear()
                    lastArResult = null
                    mutable.update { it.copy(local = null, remote = null, localBack = null, remoteBack = null, invite = "",
                        arState = com.lazydoglab.zisee.ar.session.ArSessionState.IDLE, arNotice = "",
                        arCollaboration = com.lazydoglab.zisee.ar.collaboration.ArCollaborationState(),
                        arOwnMarkerCount = 0) }
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
                // The call surface has nothing left to show, so hand the screen back to the home
                // it was opened from and let the outcome be read there.
                mutable.update { it.copy(busy = false, visible = false, inviting = false, notice = it.status) }
                job = null
                startIdle()
            }
        }
    }

    companion object {
        // The server issues these for an hour; half of that leaves room for a slow call to renew.
        private const val ICE_REUSE_MS = 30 * 60 * 1000L
        fun factory(context: Context, container: AppContainer) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CallViewModel::class.java))
                @Suppress("UNCHECKED_CAST") return CallViewModel(context.applicationContext as Application, container) as T
            }
        }
    }
}
