package com.zisee.app.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the default route identity, including Wi-Fi to another Wi-Fi network. */
class DefaultNetworkWatcher(context: Context, private val logger: AppLogger) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val changes = MutableStateFlow(0L)
    val version = changes.asStateFlow()
    private var active: Network? = null
    private var initialized = false
    private var registered = false
    private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed(network)
        override fun onLost(network: Network) {
            synchronized(this@DefaultNetworkWatcher) { if (active == network) changed(null) }
        }
    }
    @Synchronized private fun changed(network: Network?) {
        if (closed) return
        if (initialized && active != network) {
            changes.value++
            logger.info(AppEvent.RTC_NETWORK_CHANGED)
        }
        active = network; initialized = true
    }
    fun start() {
        try { manager.registerDefaultNetworkCallback(callback); registered = true }
        catch (error: RuntimeException) { logger.error(AppEvent.RTC_NETWORK_MONITOR_FAILED) }
    }
    @Synchronized fun close() {
        closed = true
        if (registered) {
            try { manager.unregisterNetworkCallback(callback) }
            catch (error: RuntimeException) { logger.error(AppEvent.RTC_NETWORK_MONITOR_FAILED) }
            registered = false
        }
    }
}
