package com.lazydoglab.zisee.rtc.compute

/** Owns a small bottom-up GL RGBA snapshot, independent of camera textures. No full video copy.
 * Transfer the byte array exclusively to this object; its pixels are cleared on release.
 */
class RgbaRoiInput(override val geometry: RoiGeometry, override val timestampNs: Long,
    val width: Int, val height: Int, private val rgba: ByteArray) : RoiInput {
    private var closed = false
    init {
        require(width in 1..640 && height in 1..640 && width * height <= 640 * 360)
        require(rgba.size == width * height * 4)
        require(geometry.rotation in listOf(0, 90, 180, 270))
    }
    val uprightWidth get() = if (geometry.rotation % 180 == 0) width else height
    val uprightHeight get() = if (geometry.rotation % 180 == 0) height else width

    /** Worker-only conversion. Flip GL rows, then apply clockwise video rotation exactly once. */
    fun uprightArgb(): IntArray {
        check(!closed)
        val output = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = ((height - 1 - y) * width + x) * 4
            val color = (255 shl 24) or ((rgba[offset].toInt() and 255) shl 16) or
                ((rgba[offset + 1].toInt() and 255) shl 8) or (rgba[offset + 2].toInt() and 255)
            val destination = when (geometry.rotation) {
                90 -> x * height + height - 1 - y
                180 -> (height - 1 - y) * width + width - 1 - x
                270 -> (width - 1 - x) * height + y
                else -> y * width + x
            }
            output[destination] = color
        }
        return output
    }

    override fun close() { if (!closed) { closed = true; rgba.fill(0) } }
}
