package com.lazydoglab.zisee.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the default route identity, including Wi-Fi to another Wi-Fi network. */
class DefaultNetworkWatcher(context: Context, private val logger: AppLogger) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val changes = MutableStateFlow(0L)
    val version = changes.asStateFlow()
    private val warnings = MutableStateFlow(0L)
    /** Advances when the system says the current route is about to go, before it does. */
    val losing = warnings.asStateFlow()
    private var active: Network? = null
    private var initialized = false
    private var registered = false
    private var closed = false
    private var validated = true
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed(network)
        /**
         * The system grants a departing network a linger period, and this fires at the start of it.
         * It is the only warning that arrives while the old path still carries media, which is the
         * one moment anything can be prepared rather than repaired. It is not guaranteed: switching
         * Wi-Fi off outright removes the network with no linger at all, so this only ever shortens
         * a handover, never replaces detecting one.
         */
        override fun onLosing(network: Network, maxMsToLive: Int) {
            synchronized(this@DefaultNetworkWatcher) {
                if (closed || !initialized || network != active) return
                warnings.value++
                logger.info(AppEvent.RTC_NETWORK_LOSING, "ms=$maxMsToLive")
            }
        }
        override fun onLost(network: Network) {
            synchronized(this@DefaultNetworkWatcher) { if (active == network) changed(null) }
        }
        /**
         * Walking out of Wi-Fi range rarely changes the default route straight away: the network
         * stays connected and simply stops working, and the system takes its time before handing
         * over to cellular. Waiting for that handover leaves media on a path that no longer
         * carries it. Losing validation is the earliest reliable signal that this route is done.
         */
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@DefaultNetworkWatcher) {
                if (closed || !initialized || network != active) return
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !usable) {
                    validated = false
                    changes.value++
                    logger.info(AppEvent.RTC_NETWORK_CHANGED, "unvalidated")
                } else if (usable) validated = true
            }
        }
    }
    @Synchronized private fun changed(network: Network?) {
        if (closed) return
        if (initialized && active != network) {
            changes.value++
            logger.info(AppEvent.RTC_NETWORK_CHANGED, "route")
        }
        active = network; initialized = true; validated = true
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
