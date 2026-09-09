package com.lazydoglab.zisee.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import com.lazydoglab.zisee.MainActivity
import com.lazydoglab.zisee.R

/**
 * Phase 1 surface for an incoming call: a heads-up notification on a dedicated
 * high-importance channel, enough to prove the push woke the app on a real
 * device. Phase 2 replaces this with Telecom + CallStyle + full-screen intent
 * (docs/architecture/CALL_DELIVERY.md §13–§17).
 */
class CallNotifications(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(INCOMING_CHANNEL) != null) return
        val channel = NotificationChannel(
            INCOMING_CHANNEL,
            context.getString(R.string.calls_incoming_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.calls_incoming_channel_desc)
            setSound(
                android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun showIncoming(invite: CallInvite) {
        ensureChannels()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context,
            invite.callId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val caller = invite.callerName.ifBlank { context.getString(R.string.calls_incoming_unknown_caller) }
        val notification: Notification = NotificationCompat.Builder(context, INCOMING_CHANNEL)
            .setSmallIcon(R.drawable.ic_zisee)
            .setContentTitle(caller)
            .setContentText(context.getString(R.string.calls_incoming_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(open)
            .build()
        manager.notify(invite.callId.hashCode(), notification)
    }

    fun clear(callId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(callId.hashCode())
    }

    companion object {
        const val INCOMING_CHANNEL = "calls.incoming"
    }
}
