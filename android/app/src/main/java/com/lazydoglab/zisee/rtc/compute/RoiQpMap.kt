package com.lazydoglab.zisee.rtc.compute

/** Standard Android 15 QP-offset map: one signed byte per 16x16 encoded block. */
class RoiQpMap(val width: Int, val height: Int, val rotation: Int, offsets: ByteArray) {
    val blocksWide = (width + 15) / 16
    val blocksHigh = (height + 15) / 16
    private val values = offsets.copyOf()
    init { require(blocksWide > 0 && blocksHigh > 0 && values.size == blocksWide * blocksHigh) }
    fun bytes(): ByteArray = values.copyOf()
}

/** Pure planner kept independent from MediaCodec so mapping can be verified on every API level.
 * Face blocks receive a small quality bias; background blocks remain neutral.
 */
object RoiQpMapPlanner {
    const val BLOCK_SIZE = 16
    const val FACE_OFFSET = -3
    // Detection is asynchronous: never penalize a face that moved outside its historical box.
    const val BACKGROUND_OFFSET = 0

    fun create(width: Int, height: Int, rotation: Int, boxes: List<RoiBox>?): RoiQpMap? {
        require(width > 0 && height > 0)
        return create(width, height, RoiBackgroundPlan.create(boxes, rotation))
    }

    internal fun create(width: Int, height: Int, plan: RoiBackgroundPlan?): RoiQpMap? {
        require(width > 0 && height > 0)
        plan ?: return null
        val blocksWide = (width + BLOCK_SIZE - 1) / BLOCK_SIZE
        val blocksHigh = (height + BLOCK_SIZE - 1) / BLOCK_SIZE
        val offsets = ByteArray(blocksWide * blocksHigh)
        for (box in plan.boxes) {
            val bounds = when (plan.rotation) {
                90 -> RoiBox(box.top, 1f - box.right, box.bottom, 1f - box.left)
                180 -> RoiBox(1f - box.right, 1f - box.bottom, 1f - box.left, 1f - box.top)
                270 -> RoiBox(1f - box.bottom, box.left, 1f - box.top, box.right)
                else -> box
            }
            val left = kotlin.math.floor(bounds.left * width / BLOCK_SIZE).toInt().coerceIn(0, blocksWide)
            val right = kotlin.math.ceil(bounds.right * width / BLOCK_SIZE).toInt().coerceIn(left, blocksWide)
            val top = kotlin.math.floor(bounds.top * height / BLOCK_SIZE).toInt().coerceIn(0, blocksHigh)
            val bottom = kotlin.math.ceil(bounds.bottom * height / BLOCK_SIZE).toInt().coerceIn(top, blocksHigh)
            for (y in top until bottom) offsets.fill(FACE_OFFSET.toByte(), y * blocksWide + left, y * blocksWide + right)
        }
        return RoiQpMap(width, height, plan.rotation, offsets)
    }
}
