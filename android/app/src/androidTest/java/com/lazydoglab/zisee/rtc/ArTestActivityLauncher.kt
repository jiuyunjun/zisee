package com.lazydoglab.zisee.rtc

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.lazydoglab.zisee.ui.ArVideoTestActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Waits for a normal foreground launch when OEM policy refuses background test Activities. */
internal object ArTestActivityLauncher {
    suspend fun open(instrumentation: Instrumentation, waitForForeground: Boolean): ArVideoTestActivity {
        val application = instrumentation.targetContext.applicationContext as Application
        val host = if (waitForForeground) {
            val resumed = CompletableDeferred<Activity>()
            val observer = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { resumed.complete(activity) }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(observer) }
            try { withTimeout(30_000) { resumed.await() } }
            finally { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(observer) } }
        } else null
        val monitor = instrumentation.addMonitor(ArVideoTestActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                (host ?: application).startActivity(Intent(application, ArVideoTestActivity::class.java)
                    .addFlags(if (host == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0))
            }
            return requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000)) {
                "Test activity did not launch"
            } as ArVideoTestActivity
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
