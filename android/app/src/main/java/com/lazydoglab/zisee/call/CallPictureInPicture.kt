package com.lazydoglab.zisee.call

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.lazydoglab.zisee.R

internal data class PipAspect(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    fun rational() = Rational(width, height)
}

internal object CallPipPolicy {
    /** Android accepts PiP ratios from 1:2.39 through 2.39:1. Invalid frames use landscape. */
    fun aspect(width: Int, height: Int): PipAspect {
        if (width <= 0 || height <= 0) return PipAspect(16, 9)
        val ratio = width.toDouble() / height
        return when {
            ratio > 2.39 -> PipAspect(239, 100)
            ratio < 1.0 / 2.39 -> PipAspect(100, 239)
            else -> {
                val divisor = gcd(width, height)
                PipAspect(width / divisor, height / divisor)
            }
        }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

/** Activity-only PiP adapter. It changes window presentation, never call or renderer ownership. */
class CallPictureInPicture(private val activity: Activity) {
    private var eligible = false
    private var params = PictureInPictureParams.Builder().build()
    private var applied: List<Any>? = null

    fun update(requested: Boolean, width: Int, height: Int, muted: Boolean) {
        eligible = requested && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        val aspect = CallPipPolicy.aspect(width, height)
        val key = listOf(eligible, aspect.width, aspect.height, muted)
        if (key == applied) return
        applied = key
        val mute = RemoteAction(
            Icon.createWithResource(activity,
                if (muted) R.drawable.ic_call_mic_off else R.drawable.ic_call_mic),
            activity.getString(if (muted) R.string.call_unmute else R.string.call_mute),
            activity.getString(if (muted) R.string.call_unmute else R.string.call_mute),
            CallForegroundService.mutePendingIntent(activity),
        )
        val hangUp = RemoteAction(
            Icon.createWithResource(activity, R.drawable.ic_call_end),
            activity.getString(R.string.call_hang_up), activity.getString(R.string.call_hang_up),
            CallForegroundService.hangUpPendingIntent(activity),
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspect.rational())
            .setActions(listOf(mute, hangUp))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(eligible)
            builder.setSeamlessResizeEnabled(false)
        }
        params = builder.build()
        try { activity.setPictureInPictureParams(params) } catch (_: RuntimeException) {
            eligible = false
            applied = null
        }
    }

    /** Android 12+ uses auto-enter; older versions enter from onUserLeaveHint. */
    fun onUserLeaveHint(): Boolean {
        if (!eligible || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        return try { activity.enterPictureInPictureMode(params) } catch (_: RuntimeException) { false }
    }
}
