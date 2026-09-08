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
import com.zisee.app.rtc.IceState
import com.zisee.app.rtc.MediaStats
import com.zisee.app.rtc.NativeRtcSession
import com.zisee.app.rtc.VideoFeed
import com.zisee.app.signaling.MediaSignaling
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    val muted: Boolean = false, val stats: MediaStats = MediaStats(),
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

    fun open(identity: LocalIdentity) {
        if (job != null) return
        this.identity = identity
        mutable.value = CallUiState(visible = true, status = if (container.backendApi == null)
            "尚未配置通话服务器。请安装已配置服务器的测试版本。" else "邀请朋友开始视频通话，或输入对方的邀请码。")
    }
    fun setForeground(value: Boolean) { foreground = value; if (!value) stop() }
    fun close() { stop(); mutable.update { it.copy(visible = false) } }
    fun stop() { job?.cancel() }
    fun accept() { commands.trySend("accept") }
    fun reject() { commands.trySend("reject") }
    fun permissionsDenied() { mutable.update { it.copy(status = "视频通话需要摄像头和麦克风权限，请允许后重试。") } }
    fun createInvite() = begin(null)
    fun join(invite: String) {
        if (!invite.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            mutable.update { it.copy(status = "请输入完整的邀请码。") }; return
        }
        begin(invite)
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

    private fun begin(invite: String?) {
        val api = container.backendApi ?: return
        val identity = identity ?: return
        if (!foreground || job != null) return
        while (commands.tryReceive().isSuccess) { /* Clear previous call actions. */ }
        mutable.value = CallUiState(visible = true, busy = true, status = "正在连接…")
        job = viewModelScope.launch {
            var session: AccessSession? = null
            var socket: MediaSignaling? = null
            var remote: RemoteCall? = null
            val calls = CallApi(api)
            var ended = false
            var machine = CallState()
            fun event(event: CallEvent) {
                machine.session?.let { machine = CallReducer.reduce(machine, it.callId, event) }
                mutable.update { it.copy(machine = machine) }
            }
            try {
                session = api.login(identity, container.deviceSigner(identity.identityId))
                var inviteExpiry: Instant? = null
                if (invite == null) {
                    val created = calls.invite(session)
                    inviteExpiry = created.second
                    mutable.update { it.copy(invite = created.first, status = "将邀请码发给对方，等待来电。") }
                } else remote = calls.redeem(session, invite)
                var localDescription: JSONObject? = null
                val descriptionId = UUID.randomUUID().toString()
                var sent = false
                var cursor = 0
                var negotiationStarted: Instant? = null
                var disconnectedAt: Instant? = null
                var failures = 0
                while (isActive) {
                    if (session == null || !Instant.now().isBefore(session.expiresAt.minusSeconds(30))) {
                        socket?.close(); socket = null
                        session = api.login(identity, container.deviceSigner(identity.identityId))
                    }
                    try {
                        if (socket == null) {
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
                                mutable.update { it.copy(machine = machine, invite = "") }
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
                                    mutable.update { it.copy(local = media.localFeed, remote = media.remoteFeed) }
                                    negotiationStarted = Instant.now()
                                    if (current.caller == identity.identityId) {
                                        val sdp = media.localDescription(offer = true)
                                        localDescription = JSONObject().put("type", "offer").put("sdp", sdp.description)
                                    }
                                }
                                if (localDescription != null && !sent) {
                                    socket.exchange("media.send", JSONObject().put("callId", current.id).put("description", localDescription), descriptionId)
                                    sent = true
                                }
                                val snapshot = socket.exchange("media.sync", JSONObject().put("callId", current.id).put("after", cursor)).getJSONObject("snapshot")
                                val descriptions = snapshot.getJSONArray("descriptions")
                                for (index in 0 until descriptions.length()) {
                                    val description = descriptions.getJSONObject(index)
                                    val seq = description.getInt("sequence")
                                    if (seq <= cursor) continue
                                    val type = description.getString("type")
                                    require(type == if (current.caller == identity.identityId) "answer" else "offer")
                                    requireNotNull(rtc).remoteDescription(type, description.getString("sdp"))
                                    cursor = seq
                                    if (type == "offer") {
                                        val answer = requireNotNull(rtc).localDescription(offer = false)
                                        localDescription = JSONObject().put("type", "answer").put("sdp", answer.description)
                                    }
                                }
                                when (requireNotNull(rtc).iceState.value) {
                                    IceState.CONNECTED -> {
                                        event(CallEvent.MEDIA_CONNECTED); disconnectedAt = null
                                        mutable.update { it.copy(status = "链路已连接，等待远端音视频…") }
                                    }
                                    IceState.DISCONNECTED -> {
                                        event(CallEvent.CONNECTION_LOST)
                                        if (disconnectedAt == null) disconnectedAt = Instant.now()
                                        if (Instant.now().isAfter(disconnectedAt.plusSeconds(15))) throw IOException("ice_disconnected")
                                        mutable.update { it.copy(status = "网络中断，等待恢复…") }
                                    }
                                    IceState.FAILED, IceState.CLOSED -> throw IOException("media_failed")
                                    else -> if (negotiationStarted != null && Instant.now().isAfter(negotiationStarted.plusSeconds(60))) throw IOException("ice_timeout")
                                }
                                val stats = requireNotNull(rtc).stats()
                                mutable.update { it.copy(stats = stats, status = if (stats.videoFrames > 0 && stats.audioReceived > 0 && machine.phase == CallPhase.CONNECTED) "音视频已连接" else it.status) }
                            }
                        }
                        failures = 0
                        delay(1_000)
                    } catch (error: IOException) {
                        // Retry transport failures using the same SDP message ID and receive cursor.
                        // Protocol, media and authorization failures are terminal in this first version.
                        val network = (error is com.zisee.app.auth.remote.AuthFailure && error.reason == com.zisee.app.auth.remote.AuthFailure.Reason.NETWORK) || error.message?.startsWith("signaling_") == true
                        if (!network) throw error
                        socket?.close(); socket = null
                        if (++failures >= 3) throw error
                        mutable.update { it.copy(status = "信令中断，正在重连…") }
                        delay(1_000L shl (failures - 1))
                    }
                }
                event(CallEvent.HANG_UP); event(CallEvent.RELEASED)
            } catch (error: TimeoutCancellationException) {
                event(CallEvent.FAIL); mutable.update { it.copy(status = "连接超时，请确认两台手机网络后重试。") }
                container.logger.error(AppEvent.CALL_FAILED)
            } catch (error: CancellationException) {
                event(CallEvent.HANG_UP); event(CallEvent.RELEASED)
                mutable.update { it.copy(status = "通话已结束。") }
                throw error
            } catch (error: Exception) {
                event(CallEvent.FAIL); mutable.update { it.copy(status = "通话未能建立，请确认邀请码、权限和网络后重试。") }
                container.logger.error(AppEvent.CALL_FAILED)
            } finally {
                socket?.close()
                withContext(NonCancellable) {
                    val media = rtc; rtc = null
                    mutable.update { it.copy(local = null, remote = null, invite = "") }
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
