package com.lazydoglab.zisee.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lazydoglab.zisee.MainActivity
import com.lazydoglab.zisee.R
import com.lazydoglab.zisee.ZiseeApplication

/** Keeps an explicitly started call process eligible for camera/microphone use after Home.
 * The call session remains owned by the call coordinator; this service owns only the notification
 * lifetime and forwards user actions to the registered in-process owner.
 */
class CallForegroundService : Service() {
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
        }
        val peer = intent?.getStringExtra(EXTRA_PEER)?.take(MAX_PEER_LENGTH)
            ?.takeIf(String::isNotBlank) ?: getString(R.string.call_active_peer)
        val muted = intent?.getBooleanExtra(EXTRA_MUTED, false) == true
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, notification(peer, muted))
        return START_NOT_STICKY
    }

    private fun notification(peer: String, muted: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun command(action: String, requestCode: Int) = PendingIntent.getService(
            this, requestCode, Intent(this, CallForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_zisee)
            .setContentTitle(getString(R.string.call_active_title, peer))
            .setContentText(getString(if (muted) R.string.call_active_muted else R.string.call_active_microphone_on))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, getString(if (muted) R.string.call_unmute else R.string.call_mute),
                command(ACTION_TOGGLE_MUTE, REQUEST_MUTE))
            .addAction(0, getString(R.string.call_hang_up), command(ACTION_HANG_UP, REQUEST_HANG_UP))
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
        private const val MAX_PEER_LENGTH = 80
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_MUTED = "muted"
        private const val ACTION_START = "com.lazydoglab.zisee.call.START"
        private const val ACTION_UPDATE = "com.lazydoglab.zisee.call.UPDATE"
        private const val ACTION_STOP = "com.lazydoglab.zisee.call.STOP"
        private const val ACTION_HANG_UP = "com.lazydoglab.zisee.call.HANG_UP"
        private const val ACTION_TOGGLE_MUTE = "com.lazydoglab.zisee.call.TOGGLE_MUTE"

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

        fun stop(context: Context) {
            // An explicit action lets an already-running service remove its notification promptly.
            runCatching { context.startService(Intent(context, CallForegroundService::class.java).setAction(ACTION_STOP)) }
        }

        private fun intent(context: Context, action: String, peer: String, muted: Boolean) =
            Intent(context, CallForegroundService::class.java).setAction(action)
                .putExtra(EXTRA_PEER, peer.take(MAX_PEER_LENGTH)).putExtra(EXTRA_MUTED, muted)
    }
}
