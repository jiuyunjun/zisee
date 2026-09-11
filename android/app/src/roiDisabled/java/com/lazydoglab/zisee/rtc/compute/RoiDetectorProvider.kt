package com.lazydoglab.zisee.rtc.compute

/** Ordinary Debug and all Release builds contain neither the model nor its SDK initializer. */
object RoiDetectorProvider {
    fun create(): RoiDetector? = null
}
