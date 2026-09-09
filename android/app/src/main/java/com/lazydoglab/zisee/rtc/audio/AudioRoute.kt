package com.lazydoglab.zisee.rtc.audio

enum class AudioRoute { SPEAKER, EARPIECE, BLUETOOTH, WIRED, USB, MICROPHONE, UNKNOWN }
enum class AudioState { IDLE, PREPARING, ACTIVE, INTERRUPTED, RECOVERING, STOPPING }
data class AudioDeviceState(val state: AudioState = AudioState.IDLE, val output: AudioRoute = AudioRoute.UNKNOWN,
    val input: AudioRoute = AudioRoute.UNKNOWN)

/** External devices take precedence until the user explicitly selects the speaker. */
object AudioRoutePolicy {
    fun select(available: Set<AudioRoute>, speaker: Boolean): AudioRoute? {
        val order = if (speaker) listOf(AudioRoute.SPEAKER) else
            listOf(AudioRoute.WIRED, AudioRoute.USB, AudioRoute.BLUETOOTH, AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        return order.firstOrNull { it in available }
    }
}
