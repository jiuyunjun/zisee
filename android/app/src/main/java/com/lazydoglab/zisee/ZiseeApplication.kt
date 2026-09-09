package com.lazydoglab.zisee

import android.app.Application
import com.lazydoglab.zisee.core.AppContainer

class ZiseeApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Register the incoming-call channel up front so a push that arrives
        // before the UI is ever opened can still ring (CALL_DELIVERY.md §16).
        container.callNotifications.ensureChannels()
    }
}
