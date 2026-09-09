package com.lazydoglab.zisee.ar.session

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException

enum class ArAvailability { CHECKING, UNSUPPORTED, INSTALL_REQUIRED, READY, UNAVAILABLE }
enum class ArPreparation { READY, CAMERA_PERMISSION_REQUIRED, INSTALL_REQUESTED, UNSUPPORTED, RETRY, DECLINED, FAILED }

/** Does not create a session, open a camera, request permissions, or install anything at startup. */
object ArCoreAvailability {
    fun check(context: Context, callback: (ArAvailability) -> Unit) {
        ArCoreApk.getInstance().checkAvailabilityAsync(context.applicationContext) { result ->
            callback(when (result) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.READY
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArAvailability.INSTALL_REQUIRED
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArAvailability.UNSUPPORTED
                ArCoreApk.Availability.UNKNOWN_CHECKING -> ArAvailability.CHECKING
                else -> ArAvailability.UNAVAILABLE
            })
        }
    }

    /** Call on the main thread only after the user chooses AR; after installation resume with
     * userRequestedInstall=false so declining does not repeatedly prompt. No permission UI here.
     */
    fun prepare(activity: Activity, userRequestedInstall: Boolean): ArPreparation {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ArPreparation.CAMERA_PERMISSION_REQUIRED
        }
        val apk = ArCoreApk.getInstance()
        val availability = apk.checkAvailability(activity)
        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) return ArPreparation.UNSUPPORTED
        if (!availability.isSupported) return ArPreparation.RETRY
        return try {
            when (apk.requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALLED -> ArPreparation.READY
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArPreparation.INSTALL_REQUESTED
            }
        } catch (_: com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException) {
            ArPreparation.DECLINED
        } catch (_: UnavailableException) {
            ArPreparation.FAILED
        }
    }
}
