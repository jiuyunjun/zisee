package com.lazydoglab.zisee.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger

/** Owned by an accepted foreground call; never binds the process away from its default route. */
class CellularStandby(context: Context, private val logger: AppLogger) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report("available")
        override fun onLost(network: Network) = report("lost")
    }
    @Synchronized private fun report(state: String) {
        if (!closed) logger.info(AppEvent.RTC_CELLULAR_STANDBY, state)
    }
    @Synchronized fun start() {
        if (registered || closed) return
        try {
            manager.requestNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
            registered = true
            // "requested" only says the request was filed. A request that is never satisfied leaves
            // no warm cellular path to hand over to, and looked identical in logs to a warm one.
            val cellular = manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
            logger.info(AppEvent.RTC_CELLULAR_STANDBY, if (cellular) "requested" else "requested_no_cellular")
        } catch (error: RuntimeException) {
            logger.error(AppEvent.RTC_CELLULAR_STANDBY_FAILED)
        }
    }
    @Synchronized fun close() {
        closed = true
        if (!registered) return
        try { manager.unregisterNetworkCallback(callback) }
        catch (error: RuntimeException) { logger.error(AppEvent.RTC_CELLULAR_STANDBY_FAILED) }
        registered = false
    }
}
