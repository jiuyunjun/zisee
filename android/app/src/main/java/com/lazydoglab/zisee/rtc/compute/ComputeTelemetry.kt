package com.lazydoglab.zisee.rtc.compute

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger

/** Called only by the RTC owner. Headroom polling is shared by all tracks, at most once/10s. */
class ComputeTelemetry(context: Context, private val logger: AppLogger) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var sampledMs: Long? = null
    private var forecast: Float? = null
    private var failed = false

    fun read(nowMs: Long, thermalStatus: Int?): ComputeInput {
        if (Build.VERSION.SDK_INT >= 30 && sampledMs?.let { nowMs - it in 0 until 10_000 } != true) {
            sampledMs = nowMs
            forecast = try {
                power.getThermalHeadroom(10).takeIf { it.isFinite() && it >= 0f }
            } catch (error: RuntimeException) {
                if (!failed) logger.error(AppEvent.RTC_COMPUTE_UNAVAILABLE)
                failed = true
                null
            }
        }
        return ComputeInput(nowMs, thermalStatus, forecast, power.isPowerSaveMode,
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
            encodeMs = null, fps = 30, sampleFresh = false)
    }
}
