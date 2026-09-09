package com.lazydoglab.zisee.rtc.audio

/** Only local audio sections are changed; payload IDs come from the actual negotiated SDP. */
object OpusPolicy {
    fun apply(sdp: String): String {
        val sections = sdp.replace("\r\n", "\n").trimEnd('\n').split(Regex("\n(?=m=)"))
        return sections.joinToString("\r\n") { section ->
            if (!section.startsWith("m=audio ") || section.substringBefore('\n').split(' ').getOrNull(1) == "0")
                return@joinToString section.replace("\n", "\r\n")
            val lines = section.split('\n').toMutableList()
            val opus = Regex("a=rtpmap:(\\d+) opus/48000/2", RegexOption.IGNORE_CASE)
            val payloads = lines.mapNotNull { opus.matchEntire(it)?.groupValues?.get(1) }
            if (payloads.isEmpty()) return@joinToString lines.joinToString("\r\n")
            val media = lines[0].split(' ')
            lines[0] = (media.take(3) + payloads + media.drop(3).filter { it !in payloads }).joinToString(" ")
            for (payload in payloads) {
                val prefix = "a=fmtp:$payload "
                val index = lines.indexOfFirst { it.startsWith(prefix) }
                val values = linkedMapOf<String, String>()
                if (index >= 0) lines[index].removePrefix(prefix).split(';').forEach {
                    val parts = it.trim().split('=', limit = 2)
                    if (parts.size == 2) values[parts[0]] = parts[1]
                }
                values.putAll(mapOf("useinbandfec" to "1", "usedtx" to "1", "stereo" to "0",
                    "sprop-stereo" to "0", "maxaveragebitrate" to "32000"))
                val fmtp = prefix + values.entries.joinToString(";") { "${it.key}=${it.value}" }
                if (index < 0) lines.add(fmtp) else lines[index] = fmtp
            }
            lines.removeAll { it.startsWith("a=ptime:") }
            lines.add("a=ptime:20")
            lines.joinToString("\r\n")
        } + "\r\n"
    }
}
