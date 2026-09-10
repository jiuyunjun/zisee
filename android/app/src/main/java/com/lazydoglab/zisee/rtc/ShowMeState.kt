package com.lazydoglab.zisee.rtc

enum class CameraMode { FACE, STARTING, DUAL, BACK_ONLY, AR }
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

/**
 * Whether this end is publishing its screen, and which share it belongs to. Versioned, bounded and
 * ordered like [CameraPresentation], and equally never a command to start or stop the peer's
 * capture: it only reports what this end is actually sending.
 *
 * [session] changes on every new share, so a viewer can drop a stale selection, annotation
 * generation or "waiting for the picture" state instead of carrying it into the next one.
 */
data class SharePresentation(val sharing: Boolean, val session: String = "") {
    init { require(sharing == session.isNotEmpty()) }
    fun encode(): String = "S1|${if (sharing) 1 else 0}|$session"
    companion object {
        const val MAX_SESSION = 12
        val None = SharePresentation(false)
        fun decode(text: String): SharePresentation? {
            if (text.length > 32) return null
            val fields = text.split('|')
            if (fields.size != 3 || fields[0] != "S1" || fields[1] !in setOf("0", "1")) return null
            val session = fields[2]
            if (session.length > MAX_SESSION || !session.all { it.isLetterOrDigit() }) return null
            val sharing = fields[1] == "1"
            if (sharing != session.isNotEmpty()) return null
            return SharePresentation(sharing, session)
        }
    }
}

enum class ViewSize { LARGE, SMALL }

/**
 * How large the viewer is actually showing each of the sender's cameras, so a thumbnail is not
 * encoded at full size. This is a hint about the viewer's own layout, never a command: the sender
 * decides what to do with it, and a peer that cannot parse it simply keeps sending full size.
 */
data class ViewRequest(val front: ViewSize, val back: ViewSize) {
    fun encode(): String = "V1|${front.name}|${back.name}"
    companion object {
        val Default = ViewRequest(ViewSize.LARGE, ViewSize.LARGE)
        fun decode(text: String): ViewRequest? {
            if (text.length > 32) return null
            val fields = text.split('|')
            if (fields.size != 3 || fields[0] != "V1") return null
            val front = ViewSize.entries.firstOrNull { it.name == fields[1] } ?: return null
            val back = ViewSize.entries.firstOrNull { it.name == fields[2] } ?: return null
            return ViewRequest(front, back)
        }
    }
}
