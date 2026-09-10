package com.lazydoglab.zisee.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import com.lazydoglab.zisee.ar.session.*
import com.lazydoglab.zisee.call.CallUiState
import com.lazydoglab.zisee.call.CallViewModel
import com.lazydoglab.zisee.rtc.CameraMode

/** Preparation never automatically resumes capture after a permission/install Activity returns. */
@Composable
internal fun ArCallControls(state: CallUiState, model: CallViewModel) {
    val context = LocalContext.current
    val activity = context.activity()
    val view = LocalView.current
    val uri = LocalUriHandler.current
    var availability by remember { mutableStateOf(ArAvailability.CHECKING) }
    var explanation by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        var alive = true
        ArCoreAvailability.check(context) { if (alive) availability = it }
        onDispose { alive = false }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.arNotice(if (granted) "摄像头权限已允许，请再次点击开启 AR。" else "未获得摄像头权限，AR 未开启。")
    }
    val active = state.showMe.mode == CameraMode.AR || state.arState in setOf(
        ArSessionState.STARTING, ArSessionState.SCANNING, ArSessionState.TRACKING, ArSessionState.TRACKING_LOST)
    val collaboration = state.arCollaboration
    Text("AR 现场协作")
    Text(when (state.arState) {
        ArSessionState.STARTING -> "正在接管摄像头…"
        ArSessionState.SCANNING -> "缓慢移动手机，扫描现场表面。"
        ArSessionState.TRACKING -> if (collaboration.fieldPeerJoined) "我的现场可标记，对方已加入。"
            else "我的现场可标记，等待对方加入。"
        ArSessionState.TRACKING_LOST -> "暂时失去跟踪，请增加光线并缓慢移动手机。"
        ArSessionState.FAILED -> "AR 已停止，请检查设备环境后重试。"
        else -> when (availability) {
            ArAvailability.CHECKING -> "正在检查 AR 支持情况…"
            ArAvailability.UNSUPPORTED -> "此设备不支持 AR，仍可正常视频通话。"
            ArAvailability.INSTALL_REQUIRED -> "需要安装或更新 Google Play Services for AR。"
            ArAvailability.UNAVAILABLE -> "暂时无法确认 AR 支持情况，请稍后重试。"
            ArAvailability.READY -> "使用后置摄像头进行现场跟踪。"
        }
    })
    if (state.arNotice.isNotBlank()) Text(state.arNotice)
    if (active) {
        TextButton(onClick = model::stopAr) { Text("结束我的现场") }
    } else if (collaboration.remote != null) {
        Text("对方已开启现场。加入只会打开标记工具，不会启动你的 ARCore 或摄像头。")
        if (collaboration.joined) {
            TextButton(onClick = model::leaveRemoteAr) { Text("退出标记") }
        } else {
            TextButton(enabled = collaboration.connected, onClick = model::joinRemoteAr) { Text("加入对方标记") }
        }
    } else {
        TextButton(enabled = activity != null && state.cameraEnabled && state.showMe.mode != CameraMode.STARTING &&
            availability in setOf(ArAvailability.READY, ArAvailability.INSTALL_REQUIRED),
            onClick = { explanation = true }) { Text("开启我的现场") }
        if (availability in setOf(ArAvailability.UNAVAILABLE, ArAvailability.CHECKING)) {
            TextButton(onClick = {
                ArCoreAvailability.check(context) { availability = it }
            }) { Text("重新检查") }
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("开启我的 AR 现场") },
        text = { Column {
            Text("AR 会切换到后置摄像头并将现场画面发送给通话对方；前摄和双摄会暂停，结束后恢复。对方加入后可以共同放置标记。相机和运动传感器用于本机环境跟踪。")
            Text("此功能使用 Google 提供的 Google Play Services for AR（ARCore），其数据处理受 Google 隐私政策约束。")
            TextButton(onClick = { uri.openUri("https://policies.google.com/privacy") }) { Text("Google 隐私政策") }
            TextButton(onClick = { uri.openUri("https://developers.google.com/ar/develop/terms") }) { Text("ARCore 条款") }
            Text("离开通话页面后 AR 会停止；应用进入后台仍按现有规则结束通话。安装完成后请回到通话中手动开启。")
        } }, dismissButton = { TextButton(onClick = { explanation = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            explanation = false
            val request = model.beginArRequest() ?: return@TextButton
            val current = activity ?: return@TextButton
            when (ArCoreAvailability.prepare(current, userRequestedInstall = true)) {
                ArPreparation.READY -> model.startAr(request, view.display?.rotation ?: 0,
                    view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                ArPreparation.CAMERA_PERMISSION_REQUIRED -> permission.launch(Manifest.permission.CAMERA)
                ArPreparation.INSTALL_REQUESTED -> model.arNotice("安装返回后，请重新进入通话并点击开启 AR。")
                ArPreparation.DECLINED -> model.arNotice("已取消 AR 安装，普通视频通话仍可使用。")
                ArPreparation.UNSUPPORTED -> { availability = ArAvailability.UNSUPPORTED; model.arNotice("此设备不支持 AR。") }
                ArPreparation.RETRY -> model.arNotice("AR 服务正在准备，请稍后重试。")
                ArPreparation.FAILED -> model.arNotice("AR 服务准备失败，请稍后重试。")
            }
        }) { Text("继续开启") } })
}

/** Geometry follows the actual display, including 180-degree changes without a size change. */
@Composable
internal fun ArCallGeometry(state: CallUiState, model: CallViewModel) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view, state.local, state.showMe.mode) {
        val manager = context.getSystemService(DisplayManager::class.java)
        fun update() {
            if (state.showMe.mode == CameraMode.AR && view.width > 0 && view.height > 0)
                model.updateArGeometry(view.display?.rotation ?: 0, view.width, view.height)
        }
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) { if (view.display?.displayId == id) update() }
        }
        view.addOnLayoutChangeListener(layout)
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        update()
        onDispose { view.removeOnLayoutChangeListener(layout); manager.unregisterDisplayListener(listener) }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
