package com.lazydoglab.zisee.rtc.compute

/** Standard Android 15 QP-offset map: one signed byte per 16x16 encoded block. */
class RoiQpMap(val blocksWide: Int, val blocksHigh: Int, offsets: ByteArray) {
    private val values = offsets.copyOf()
    init { require(blocksWide > 0 && blocksHigh > 0 && values.size == blocksWide * blocksHigh) }
    fun bytes(): ByteArray = values.copyOf()
}

/** Pure planner kept independent from MediaCodec so mapping can be verified on every API level.
 * Face blocks receive a small quality bias; background blocks pay a smaller positive offset.
 */
object RoiQpMapPlanner {
    const val BLOCK_SIZE = 16
    const val FACE_OFFSET = -3
    const val BACKGROUND_OFFSET = 1

    fun create(width: Int, height: Int, rotation: Int, boxes: List<RoiBox>?): RoiQpMap? {
        require(width > 0 && height > 0)
        val plan = RoiBackgroundPlan.create(boxes, rotation) ?: return null
        val blocksWide = (width + BLOCK_SIZE - 1) / BLOCK_SIZE
        val blocksHigh = (height + BLOCK_SIZE - 1) / BLOCK_SIZE
        val offsets = ByteArray(blocksWide * blocksHigh)
        for (y in 0 until blocksHigh) for (x in 0 until blocksWide) {
            val left = x * BLOCK_SIZE.toFloat() / width
            val top = y * BLOCK_SIZE.toFloat() / height
            val right = minOf(width, (x + 1) * BLOCK_SIZE).toFloat() / width
            val bottom = minOf(height, (y + 1) * BLOCK_SIZE).toFloat() / height
            offsets[y * blocksWide + x] = (if (plan.protectsEncodedBlock(left, top, right, bottom))
                FACE_OFFSET else BACKGROUND_OFFSET).toByte()
        }
        return RoiQpMap(blocksWide, blocksHigh, offsets)
    }
}
