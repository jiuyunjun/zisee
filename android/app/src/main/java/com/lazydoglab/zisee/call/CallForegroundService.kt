package com.lazydoglab.zisee.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lazydoglab.zisee.MainActivity
import com.lazydoglab.zisee.R
import com.lazydoglab.zisee.ZiseeApplication

/** Keeps an explicitly started call process eligible for camera/microphone use after Home.
 * The call session remains owned by the call coordinator; this service owns only the notification
 * lifetime and forwards user actions to the registered in-process owner.
 */
class CallForegroundService : Service() {
    /** Survives ordinary notification updates so a live projection keeps its foreground type. */
    private var sharing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the task is an explicit dismissal, unlike Home. End through the call owner so
        // signaling and native media follow their ordinary cleanup path.
        if (!actions().hangUp()) stopNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNow()
                return START_NOT_STICKY
            }
            ACTION_HANG_UP -> {
                if (!actions().hangUp()) stopNow()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> actions().toggleMute()
            ACTION_STOP_SHARING -> actions().stopSharing()
        }
        val peer = intent?.getStringExtra(EXTRA_PEER)?.take(MAX_PEER_LENGTH)
            ?.takeIf(String::isNotBlank) ?: getString(R.string.call_active_peer)
        val muted = intent?.getBooleanExtra(EXTRA_MUTED, false) == true
        // Sharing is a property of the call, not of this particular command: a mute update must not
        // drop the mediaProjection type out from under a live projection.
        if (intent?.hasExtra(EXTRA_SHARING) == true) sharing = intent.getBooleanExtra(EXTRA_SHARING, false)
        ensureChannel(this)
        // The manifest declares the union of types this service can ever take. Selecting them here
        // is what keeps them honest: claiming mediaProjection before a projection exists is
        // rejected, and MediaProjectionManager.getMediaProjection refuses until it is claimed.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(peer, muted, sharing), types)
        return START_NOT_STICKY
    }

    private fun notification(peer: String, muted: Boolean, sharing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_zisee)
            .setContentTitle(getString(R.string.call_active_title, peer))
            // Sharing outranks the microphone line: it is the state a user most needs to see here.
            .setContentText(getString(when {
                sharing -> R.string.call_active_sharing
                muted -> R.string.call_active_muted
                else -> R.string.call_active_microphone_on
            }))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(if (muted) R.drawable.ic_call_mic_off else R.drawable.ic_call_mic,
                getString(if (muted) R.string.call_unmute else R.string.call_mute),
                mutePendingIntent(this))
            // §4.2: the notification must carry every stop the system PiP has no room for.
            .apply {
                if (sharing) addAction(R.drawable.ic_call_end,
                    getString(R.string.call_stop_sharing), stopSharingPendingIntent(this@CallForegroundService))
            }
            .addAction(R.drawable.ic_call_end, getString(R.string.call_hang_up), hangUpPendingIntent(this))
            .build()
    }

    private fun actions() = (application as ZiseeApplication).container.activeCallActions

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL = "calls.active"
        private const val NOTIFICATION_ID = 0x5A15EE
        private const val REQUEST_OPEN = 1
        private const val REQUEST_MUTE = 2
        private const val REQUEST_HANG_UP = 3
        private const val REQUEST_STOP_SHARING = 4
        private const val MAX_PEER_LENGTH = 80
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_MUTED = "muted"
        private const val EXTRA_SHARING = "sharing"
        private const val ACTION_START = "com.lazydoglab.zisee.call.START"
        private const val ACTION_UPDATE = "com.lazydoglab.zisee.call.UPDATE"
        private const val ACTION_STOP = "com.lazydoglab.zisee.call.STOP"
        private const val ACTION_HANG_UP = "com.lazydoglab.zisee.call.HANG_UP"
        private const val ACTION_TOGGLE_MUTE = "com.lazydoglab.zisee.call.TOGGLE_MUTE"
        private const val ACTION_STOP_SHARING = "com.lazydoglab.zisee.call.STOP_SHARING"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, context.getString(R.string.calls_active_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.calls_active_channel_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }

        /** Must be called while the call UI is visible and camera/microphone grants are current. */
        fun start(context: Context, peer: String, muted: Boolean) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START, peer, muted))
        }

        fun update(context: Context, peer: String, muted: Boolean) {
            context.startService(intent(context, ACTION_UPDATE, peer, muted))
        }

        /**
         * Claims or releases the mediaProjection foreground type, and must complete before
         * MediaProjectionManager.getMediaProjection is called. Releasing it keeps the camera and
         * microphone types the ongoing call still needs.
         *
         * Returns whether the service accepted the change; a refusal means the share must not
         * start, rather than the call ending.
         */
        fun setSharing(context: Context, peer: String, muted: Boolean, sharing: Boolean): Boolean =
            runCatching {
                ContextCompat.startForegroundService(context,
                    intent(context, ACTION_UPDATE, peer, muted).putExtra(EXTRA_SHARING, sharing))
            }.isSuccess

        fun stop(context: Context) {
            // An explicit action lets an already-running service remove its notification promptly.
            runCatching { context.startService(Intent(context, CallForegroundService::class.java).setAction(ACTION_STOP)) }
        }

        fun mutePendingIntent(context: Context): PendingIntent =
            command(context, ACTION_TOGGLE_MUTE, REQUEST_MUTE)

        fun hangUpPendingIntent(context: Context): PendingIntent =
            command(context, ACTION_HANG_UP, REQUEST_HANG_UP)

        fun stopSharingPendingIntent(context: Context): PendingIntent =
            command(context, ACTION_STOP_SHARING, REQUEST_STOP_SHARING)

        private fun command(context: Context, action: String, requestCode: Int) = PendingIntent.getService(
            context, requestCode, Intent(context, CallForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun intent(context: Context, action: String, peer: String, muted: Boolean) =
            Intent(context, CallForegroundService::class.java).setAction(action)
                .putExtra(EXTRA_PEER, peer.take(MAX_PEER_LENGTH)).putExtra(EXTRA_MUTED, muted)
    }
}
