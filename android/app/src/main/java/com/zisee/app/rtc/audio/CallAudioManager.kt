package com.zisee.app.rtc.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner of focus, communication mode and routes for one foreground call. */
@Suppress("DEPRECATION")
class CallAudioManager(context: Context, private val logger: AppLogger, private val interrupt: (Boolean) -> Unit) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(AudioDeviceState())
    val state = mutable.asStateFlow()
    private var request: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var previousDevice: AudioDeviceInfo? = null
    private var explicitSpeaker = false
    private var registered = false
    private var recordingRegistered = false
    private var scoRegistered = false
    private var scoStarted = false
    private var scoFailed = false
    private val scoTimeout = Runnable {
        if (request != null && scoStarted && !manager.isBluetoothScoOn) {
            scoFailed = true
            safely { manager.stopBluetoothSco() }; scoStarted = false
            recover()
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { scoFailed = false; recover() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { scoFailed = false; recover() }
    }
    private val recording = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) { publish() }
    }
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (request == null) return
            if (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                handler.removeCallbacks(scoTimeout)
                safely { manager.isBluetoothScoOn = true }
                publish()
            } else if (!isInitialStickyBroadcast && scoStarted &&
                intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                scoStarted = false; scoFailed = true
                safely { manager.isBluetoothScoOn = false }
                recover()
            } else publish()
        }
    }
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (request != null) return
        previousMode = manager.mode; previousSpeaker = manager.isSpeakerphoneOn
        if (Build.VERSION.SDK_INT >= 31) previousDevice = manager.communicationDevice
        mutable.value = AudioDeviceState(AudioState.PREPARING)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (request != null) {
                    val blocked = change != AudioManager.AUDIOFOCUS_GAIN
                    interrupt(blocked)
                    mutable.value = mutable.value.copy(state = if (blocked) AudioState.INTERRUPTED else AudioState.RECOVERING)
                    if (!blocked) recover()
                }
            }, handler).build()
        request = focus
        try {
            val granted = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            interrupt(!granted)
            mutable.value = mutable.value.copy(state = if (granted) AudioState.RECOVERING else AudioState.INTERRUPTED)
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            manager.registerAudioDeviceCallback(devices, handler); registered = true
            manager.registerAudioRecordingCallback(recording, handler); recordingRegistered = true
            if (Build.VERSION.SDK_INT >= 31) {
                val listener = AudioManager.OnCommunicationDeviceChangedListener { publish() }
                manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
                communicationListener = listener
            } else {
                context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                scoRegistered = true
            }
            recover()
        } catch (error: Exception) { stop(); throw error }
    }

    fun setSpeaker(enabled: Boolean): Boolean {
        if (request == null) return false
        explicitSpeaker = enabled
        if (!enabled) scoFailed = false
        if (mutable.value.state == AudioState.INTERRUPTED) safely {
            if (manager.requestAudioFocus(requireNotNull(request)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                interrupt(false); mutable.value = mutable.value.copy(state = AudioState.RECOVERING)
            }
        }
        recover()
        return mutable.value.output == AudioRoute.SPEAKER
    }

    private fun recover() {
        if (request == null) return
        safely {
            if (mutable.value.state != AudioState.INTERRUPTED) manager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val available = manager.availableCommunicationDevices
                val route = AudioRoutePolicy.select(available.map { route(it) }.toSet(), explicitSpeaker)
                val device = available.firstOrNull { route(it) == route }
                if (device != null && manager.communicationDevice?.id != device.id && !manager.setCommunicationDevice(device)) {
                    logger.error(AppEvent.RTC_MEDIA_FAILED)
                }
            } else {
                val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS) + manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val available = devices.map { route(it) }.toMutableSet()
                // Some legacy devices advertise only A2DP until SCO has been requested.
                if (manager.isBluetoothScoAvailableOffCall && devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })
                    available.add(AudioRoute.BLUETOOTH)
                if (scoFailed) available.remove(AudioRoute.BLUETOOTH)
                val selected = AudioRoutePolicy.select(available, explicitSpeaker)
                if (selected == AudioRoute.BLUETOOTH && !scoStarted) {
                    manager.isSpeakerphoneOn = false
                    manager.startBluetoothSco(); scoStarted = true
                    handler.postDelayed(scoTimeout, 3_000)
                } else if (selected != AudioRoute.BLUETOOTH) {
                    handler.removeCallbacks(scoTimeout)
                    if (scoStarted) { manager.stopBluetoothSco(); scoStarted = false }
                    manager.isBluetoothScoOn = false
                    manager.isSpeakerphoneOn = selected == AudioRoute.SPEAKER
                }
            }
            publish()
        }
    }

    private fun publish() {
        if (request == null) return
        safely {
            val output = if (Build.VERSION.SDK_INT >= 31) route(manager.communicationDevice) else when {
                manager.isBluetoothScoOn -> AudioRoute.BLUETOOTH
                manager.isSpeakerphoneOn -> AudioRoute.SPEAKER
                else -> AudioRoutePolicy.select(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .map { route(it) }.filter { it != AudioRoute.BLUETOOTH }.toSet(), false) ?: AudioRoute.UNKNOWN
            }
            mutable.value = AudioDeviceState(if (mutable.value.state == AudioState.INTERRUPTED)
                AudioState.INTERRUPTED else AudioState.ACTIVE, output,
                route(manager.activeRecordingConfigurations.firstOrNull()?.audioDevice))
        }
    }

    fun stop() {
        val focus = request ?: return
        request = null
        mutable.value = mutable.value.copy(state = AudioState.STOPPING)
        handler.removeCallbacks(scoTimeout)
        if (registered) safely { manager.unregisterAudioDeviceCallback(devices) }
        registered = false
        if (recordingRegistered) safely { manager.unregisterAudioRecordingCallback(recording) }
        recordingRegistered = false
        if (scoRegistered) safely { context.unregisterReceiver(scoReceiver) }
        scoRegistered = false
        if (Build.VERSION.SDK_INT >= 31) {
            communicationListener?.let { listener -> safely { manager.removeOnCommunicationDeviceChangedListener(listener) } }
            communicationListener = null
            safely {
                val device = previousDevice?.let { old -> manager.availableCommunicationDevices.firstOrNull { it.id == old.id } }
                if (device == null || !manager.setCommunicationDevice(device)) manager.clearCommunicationDevice()
            }
        } else {
            if (scoStarted) safely { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false }
            safely { manager.isSpeakerphoneOn = previousSpeaker }
        }
        scoStarted = false
        safely { manager.mode = previousMode }
        safely { manager.abandonAudioFocusRequest(focus) }
        mutable.value = AudioDeviceState()
    }

    private fun safely(block: () -> Unit) { try { block() } catch (error: Exception) { logger.error(AppEvent.RTC_MEDIA_FAILED) } }
    private fun route(device: AudioDeviceInfo?): AudioRoute = when (device?.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioRoute.MICROPHONE
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoute.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRoute.USB
        else -> AudioRoute.UNKNOWN
    }
}
