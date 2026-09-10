---
title: 屏幕共享框架与接入契约
document_id: ARCH-SCREEN-001
version: 1.0.0
status: Active
created: 2026-09-10
updated: 2026-09-10
applies_to: "M3-F 框架；尚未接入产品通话"
owners:
  - android
  - core
---

# 屏幕共享框架与接入契约

产品规则见 [M3 多任务专项](../product/CALL_MULTITASKING.md)。当前里程碑为 **M3**；本次先交付独立的 M3-F 屏幕共享框架，再回到 M3-A 后台通话基础。框架不启动服务、不索取权限、不改变已有摄像头或通话生命周期，也不代表 APK 已能共享屏幕。

## 1. 已实现

- [ScreenShareController.kt](../../android/app/src/main/java/com/lazydoglab/zisee/screen/ScreenShareController.kt)：每次共享尝试一个 owner，StateFlow 状态、callId + UUID 请求、一次性授权消费门、首帧就绪、尺寸/可见性、启动超时入口、幂等终止与枚举诊断。
- [AndroidScreenProjection.kt](../../android/app/src/main/java/com/lazydoglab/zisee/screen/AndroidScreenProjection.kt)：持有一份新 MediaProjection 和一个 VirtualDisplay，输出到借用的 Surface；创建前注册系统回调，尺寸变化复用原 VirtualDisplay，停止时尝试全部资源释放。
- [ScreenShareControllerTest.kt](../../android/app/src/test/java/com/lazydoglab/zisee/screen/ScreenShareControllerTest.kt)：使用 fake backend 验证生命周期和竞态，不伪装成 Android 系统投影测试。
- 复用现有 `MediaTrack.SCREEN` / `video_screen` 作为后续独立视频源身份。本次不修改 SDP、前后摄映射或旧版 DataChannel。

## 2. 状态和一次性资源

```text
IDLE → AWAITING_CONSENT → STARTING → ACTIVE
             │               │        │
             └───────────────┴────────┴→ STOPPING → STOPPED / FAILED
任意可用状态 → close → CLOSED
```

`request()` 只能调用一次；拒绝、停止或失败后使用新 controller、新请求和新系统授权。`start(request, size, factory)` 只在本次请求仍等待授权时调用 factory，重复、别的通话和迟到请求均不触发投影资源创建。factory 返回前的部分资源失败由 factory 清理；返回后的 start 失败由 controller 清理。

授权与 VirtualDisplay 创建不能代表正在发送：后续屏幕专属 frame sink 收到真实帧后，携带对应 request 调用 `onFrame` 才进入 ACTIVE。ACTIVE 仅表示本机采集有帧，不代表远端已收到或用户正在看。owner 必须安排首帧 deadline 并调用 `firstFrameTimeout`，此框架不自行持有定时线程；ACTIVE、已停止或旧请求的迟到 timer 无效。

所有可变状态操作和系统事件必须在同一非主线程执行。纯 Kotlin controller 记录构造线程并校验；Android 后端另外强制非主 Looper。诊断回调必须快速且不抛异常，不能重入 controller；接入时转换为现有结构化日志，禁止传出平台 exception message、授权 Intent 或画面数据。

## 3. 后续接入顺序与资源归属

1. 活动 call owner 在前台接收显式共享意图，完成对端能力、协作归属和 AR/双摄互斥检查，才生成 request。
2. Activity 仅负责发起本次系统共享授权。授权返回再次校验 callId、请求有效性和通话存活；失效结果丢弃，不持久化 Intent，不尝试后台复活通话。
3. 完成本次授权后合法启动 `mediaProjection` 前台服务并发布可停止的通知，再获取本次 MediaProjection。服务权限和 manifest 声明随真实服务实现一起加入；本次没有空壳服务或无用途权限。
4. 在屏幕 GL handler 创建 `SurfaceTextureHelper`、`VideoSource(isScreencast = true)`、输出 Surface；使用 `AndroidScreenProjection` 将画面直接送到该 Surface。`resizeOutput` 更新纹理 buffer 尺寸，传入未裁切内容比例。
5. 屏幕 frame sink 向独立 `video_screen` Track 投递，通知 controller 首帧；保留帧时间戳和尺寸元数据。借用 RTC 的 EGL context / factory，不创建第二套音频、相机或 PeerConnection。
6. API 34+ 内容尺寸/可见性从系统回调进入 controller；旧系统由 display/orientation owner 调用 `resize`。不可见仅是内容状态，不视为通话结束。
7. 用户/系统停止、锁屏、挂断和错误进入同一 controller stop。调用前发送端先禁用屏幕发布；结束后拆除 frame sink、停止并释放纹理 helper、Surface、VideoSource、VideoTrack。停止屏幕绝不关闭 microphone Track。

```text
Call/service owner
  ├─ call/request/ownership checks + startup deadline
  ├─ RTC screen sender + VideoSource + SurfaceTextureHelper + Surface
  └─ ScreenShareController
       └─ AndroidScreenProjection
            ├─ MediaProjection (owned)
            ├─ VirtualDisplay (owned)
            └─ output Surface (borrowed)
```

Android 后端构造成功才转移 projection 所有权；构造参数无效时调用方仍负责停止投影。controller.close 完成前不释放借用 Surface。后端 close 先分离并释放 VirtualDisplay，再移除回调和 stop 投影；单项失败仍尝试其余项，错误上报为 CLEANUP_FAILED，不自动复用或重新启动。不要在主线程等待 EGL/采集释放。

## 4. 平台依据与边界

Android 要求每次投影会话单独授权；现代目标版本必须声明并运行对应前台服务，创建 VirtualDisplay 前注册停止回调，停止后的投影不可重新使用。框架以“一次 controller / 一次 projection / 一次 display”落实这个约束。见 [Android MediaProjection 官方文档](https://developer.android.com/media/grow/media-projection)。

本次**尚未接入**：授权 Activity Result、前台服务/通知、WebRTC 纹理 frame sink 和屏幕 sender、远端能力/归属/状态同步、屏幕布局/PiP、锁屏监听、2D 标注。后端只有在这些前置条件成立后才能用于真实共享。当前 App 切后台仍会结束通话；先完成 M3-A/B 才开放共享入口。

不采集系统音频、不申请悬浮窗/无障碍权限、不保存屏幕。API 34 回调在旧系统不会触发，不能省掉旧版尺寸更新；OEM 捕获行为、系统停止、受保护窗口和双端方向适配仍需物理设备验收。

## 5. 验证

已执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`：181 个 JVM 测试通过（新增 12 个框架测试），Debug APK 构建与 lint 通过。框架单测覆盖：授权重复/拒绝/迟到、挂断后授权、首帧与超时、部分启动失败、同步系统停止、重复释放、尺寸变化、内容不可见、旧回调、清理失败、线程和输入限制。

Android 后端已纳入编译/lint；真实 MediaProjection 授权、Surface 出帧和系统/OEM 资源回收尚未真机验证。接入阶段至少两台物理设备，覆盖 API 26 基线、34/35、旋转、单应用/整屏、系统停止、锁屏、网络切换和 30/60 分钟资源检查。

## Changelog

### 1.0.0 - 2026-09-10

- M3-F：建立独立生命周期控制器、Android Surface 投影后端、测试及服务/RTC 接入边界。
