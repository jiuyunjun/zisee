package com.lazydoglab.zisee.rtc.compute

/** Immutable face protection passed to the GL stage. Boxes use upright, top-left coordinates. */
class RoiBackgroundPlan private constructor(val boxes: List<RoiBox>, val rotation: Int) {
    init {
        require(boxes.isNotEmpty() && boxes.size <= 8 && boxes.all { it.valid() })
        require(rotation in listOf(0, 90, 180, 270))
    }

    /** CPU mirror of the shader transform, used to verify rotation and bounds without a GPU. */
    fun protects(rawU: Float, rawV: Float): Boolean {
        if (rawU !in 0f..1f || rawV !in 0f..1f) return false
        val (x, y) = upright(rawU, rawV)
        return boxes.any { x in it.left..it.right && y in it.top..it.bottom }
    }

    /** True when a top-left-origin encoded block overlaps any upright detector box. */
    fun protectsEncodedBlock(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        require(left in 0f..1f && top in 0f..1f && right in left..1f && bottom in top..1f)
        val corners = listOf(upright(left, 1f - top), upright(right, 1f - top),
            upright(left, 1f - bottom), upright(right, 1f - bottom))
        val minX = corners.minOf { it.first }; val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }; val maxY = corners.maxOf { it.second }
        return boxes.any { it.right > minX && it.left < maxX && it.bottom > minY && it.top < maxY }
    }

    private fun upright(rawU: Float, rawV: Float): Pair<Float, Float> =
        when (rotation) {
            90 -> rawV to rawU
            180 -> (1f - rawU) to rawV
            270 -> (1f - rawV) to (1f - rawU)
            else -> rawU to (1f - rawV)
        }

    companion object {
        fun create(boxes: List<RoiBox>?, rotation: Int): RoiBackgroundPlan? =
            boxes?.takeIf { it.isNotEmpty() }?.let { RoiBackgroundPlan(it.toList(), rotation) }
    }
}
