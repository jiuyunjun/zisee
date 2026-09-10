---
title: 屏幕共享框架与接入契约
document_id: ARCH-SCREEN-001
version: 1.3.0
status: Active
created: 2026-09-10
updated: 2026-09-10
applies_to: "M3-F 框架 + M3-C 单向共享接入；待真机验收"
owners:
  - android
  - core
---

# 屏幕共享框架与接入契约

产品规则见 [M3 多任务专项](../product/CALL_MULTITASKING.md)。当前里程碑为 **M3**。M3-F 交付了独立的屏幕共享框架；M3-C 已把它接进真实通话，形成「申请归属 → 系统授权 → 本机共享 → 对方观看 → 任一侧停止」的单向闭环，双方同抢由主叫端裁决。「改由我共享」邀请、AR 跨端互斥和定格讲解仍未实现，见 §6。

## 1. 已实现

### 1.1 M3-C 接入（本次）

- [ScreenShareSession.kt](../../android/app/src/main/java/com/lazydoglab/zisee/screen/ScreenShareSession.kt)：持有专用非主线程 `HandlerThread("ZiseeScreen")`、controller、`SurfaceTextureHelper` 与输出 Surface；把系统授权结果换成 MediaProjection，并把帧投递给屏幕 `VideoSource`。`onActive` 只在**真实首帧**后为真。`close()` 在 owner 线程上有界阻塞，保证 RTC 释放 `VideoSource` 前采集管线已经拆除。
- [ScreenCaptureSize.kt](../../android/app/src/main/java/com/lazydoglab/zisee/screen/ScreenCaptureSize.kt)：长边降到 1600、保持比例、16 对齐，兼顾文字可读与编码器约束。
- [NativeRtcSession.kt](../../android/app/src/main/java/com/lazydoglab/zisee/rtc/NativeRtcSession.kt)：`video_screen` Track 在 connect 时**预分配并禁用**（与后摄同一手法），避免中途 SDP 重协商；`onTrack` 按 m-section 三路分流；`requestScreenShare / startScreenShare / stopScreenShare` 串行于 RTC dispatcher。
- 相机独占：`screenSharing` 与 `arLeaseId` 是同一类抑制位。共享开始时关闭 `dualCapture`、`releaseCamera()` 释放设备、禁用两条相机 Track；`setTrackEnabled` 与 `applyQuality` 都尊重它，所以前后台切换不会偷偷把相机开回来。停止时按记录的 `shareSuspendedMode` 恢复；共享期间用户按过关闭摄像头则清空该记录，不再恢复。双摄重开失败退回单前摄并提示，不留下「模式是双摄、Track 已禁用」的死角。`sendPresentation` 在共享期间上报单路且 `enabled = false`，对方看到「对方摄像头已暂停」而不是两个空槽。
- [SharePresentation](../../android/app/src/main/java/com/lazydoglab/zisee/rtc/ShowMeState.kt)：控制 DataChannel 上的 `S1|sharing|session` 状态消息，只报告本机实际在发什么，不是命令。`session` 每次共享变化，接收端据此丢弃上一轮的选择与等待状态。
- [CollaborationOwnership.kt](../../android/app/src/main/java/com/lazydoglab/zisee/rtc/CollaborationOwnership.kt)：**先申请后启动**的归属状态机。`K1|C/G/D/R|<id>` 复用同一条控制通道（≤32 字节，旧客户端直接忽略）。同时点击由主叫端胜出，落败方主动放弃自己的申请并 GRANT，绝不出现两个系统授权框抢两路投影；当前 owner 收到 CLAIM 一律 DENY，不被远程停掉。5 秒无应答则超时放弃——不应答就不共享是安全结果。状态先于发送落地，因此同步到达的应答不会被丢进 IDLE 状态机。
- [CallForegroundService.kt](../../android/app/src/main/java/com/lazydoglab/zisee/call/CallForegroundService.kt)：manifest 声明类型并集，运行时用 `ServiceCompat.startForeground` **显式选取**类型；`setSharing` 在取 MediaProjection 之前claim `mediaProjection`，停止共享后退回 `camera|microphone`。通知增加「停止共享」。
- [ActiveCall.kt](../../android/app/src/main/java/com/lazydoglab/zisee/ui/ActiveCall.kt) / [CallVideoLayout.kt](../../android/app/src/main/java/com/lazydoglab/zisee/ui/CallVideoLayout.kt)：「更多 → 展示与协作 → 共享我的屏幕」入口、常驻「停止共享」条、`PeerScreen` 来源与自动切主画面。**没有本机共享来源**：整屏共享不回放自身画面，避免递归。

### 1.2 M3-F 框架（既有）

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

## 3. 接入顺序与资源归属

以下 1–7 为设计顺序，1–7 已按 M3-C 第一刀落地，归属协调部分见 §6。

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

不采集系统音频、不申请悬浮窗/无障碍权限、不保存屏幕。API 34 回调在旧系统不会触发，不能省掉旧版尺寸更新；OEM 捕获行为、系统停止、受保护窗口和双端方向适配仍需物理设备验收。

## 5. 验证

已执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`：213 个 JVM 测试通过，Debug APK 构建与 lint 通过。除既有 12 个框架测试外，新增线路消息编解码与边界拒绝、采集尺寸对齐/比例/降级、`PeerScreen` 来源与主画面/紧凑窗口选择，以及归属协调 14 项：同时申请只产生一个 owner、owner 不被远程停掉、释放后可再申请、超时与迟到应答、通道不可用与发送失败、非本次申请的释放、畸形消息、与其它控制消息不冲突。归属测试的双端 harness 采用**同步重入投递**（应答在发送调用内部返回），比真实 DataChannel 更严苛。

**尚未真机验证**：真实 MediaProjection 授权、Surface 出帧、`mediaProjection` 前台服务类型切换、系统「停止共享」回调、OEM 资源回收，以及对端实际看到的画面。JVM 测试完全不覆盖这些。验收至少两台物理设备，覆盖 API 26 基线、34/35、旋转、单应用/整屏、系统停止、锁屏、网络切换和 30/60 分钟资源检查。

## 6. M3-C 剩余工作

以下属于 M3-C 但本刀**未做**，不能当作已有能力：

- 「改由我共享」邀请流程（20 秒过期 / 30 秒冷却）。当前非 owner 一方只被告知「对方正在共享」，没有请求接管的入口。
- **AR 与共享的跨端互斥**。本机 AR 现场与本机共享已互斥（两侧都有前置检查），但 AR 现场自己走的是 `ArCollaboration` 的独立归属，尚未并入 `CollaborationOwnership`；因此「我在共享、对方同时开 AR 现场」仍可能同时成立。
- 共享源的码率/帧率档位实测与降级顺序（§5.2），以及 `video_screen` 是否需要单独的 codec 偏好（屏幕内容与摄像头画面编码特性不同，需实测）。当前只有 `isScreencast = true` 与采集尺寸上限。
- 单应用共享的引导文案与受保护内容黑屏提示。

## Changelog

### 1.3.0 - 2026-09-10

- 归属协调：`CollaborationOwnership` 申请/授予/拒绝/释放，主叫端裁决同时申请，超时与通道不可用一律不启动设备。
- 锁屏结束共享：`ScreenLockWatcher` 在 `ACTION_SCREEN_OFF` 时走产品自己的清理，不依赖各家 OEM 是否发投影停止回调。
- 剩余工作收敛为「改由我共享」邀请、AR 跨端互斥、码率/codec 实测与单应用共享文案。

### 1.2.0 - 2026-09-10

- 共享期间暂停并释放全部摄像头（设计变更，见产品文档 1.5.0）：`screenSharing` 抑制位、设备释放、按记录布置恢复、共享期间上报单路暂停相机。

### 1.1.0 - 2026-09-10

- M3-C 第一刀：预分配 `video_screen` Track、`ScreenShareSession` 采集接线、`SharePresentation` 状态消息、`mediaProjection` 前台服务类型切换、授权 Activity Result、共享入口与 `PeerScreen` 布局。
- 记录尚未真机验证的部分，以及归属协调、锁屏、相机策略等 M3-C 剩余工作。

### 1.0.0 - 2026-09-10

- M3-F：建立独立生命周期控制器、Android Surface 投影后端、测试及服务/RTC 接入边界。
