package com.zisee.app.rtc

enum class CameraMode { FACE, STARTING, DUAL, BACK_ONLY }
data class ShowMeState(val mode: CameraMode = CameraMode.FACE, val message: String = "")

/** Versioned, bounded, ordered DataChannel state. Never a command to activate the peer camera. */
data class CameraPresentation(val mode: CameraMode, val enabled: Boolean) {
    fun encode(): String = "1|${mode.name}|${if (enabled) 1 else 0}"
    companion object {
        fun decode(text: String): CameraPresentation? {
            if (text.length > 32) return null
            val fields = text.split('|')
            if (fields.size != 3 || fields[0] != "1" || fields[2] !in setOf("0", "1")) return null
            val mode = CameraMode.entries.firstOrNull { it.name == fields[1] && it != CameraMode.STARTING } ?: return null
            return CameraPresentation(mode, fields[2] == "1")
        }
    }
}
