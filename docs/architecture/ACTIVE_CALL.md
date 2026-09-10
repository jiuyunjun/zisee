---
title: M3 活动通话后台生命周期
document_id: ARCH-ACTIVE-CALL-001
version: 1.1.0
status: Active
created: 2026-09-10
updated: 2026-09-10
applies_to: "M3-A / M3-B 代码闭环"
owners:
  - android
  - core
---

# M3 活动通话后台生命周期

产品目标见 [M3 通话多任务专项](../product/CALL_MULTITASKING.md)。当前已经建立 Home 后持续通话、系统 PiP 和应用内迷你通话的代码闭环；屏幕共享产品接入仍待 M3-C。

## 当前行为

| 事件 | 行为 |
| --- | --- |
| 普通/Show Me 已建立 RTC 媒体后按 Home | 自动进入系统 PiP；PiP 显示一路当前远端画面，通话与用户已开启的视频继续；通知提供返回、静音、挂断 |
| PiP 关闭或进入失败 | 收起远端渲染并暂停本机摄像头 Track，音频与信令继续；从通知返回后按用户原意恢复视频 |
| 在咫尺内返回主页 | 通话缩为可拖拽迷你窗；可返回通话、静音或挂断，浏览主页期间媒体不换 owner |
| 回到通话 | 用户原来开启摄像头时恢复 Track；用户主动关闭的摄像头不恢复 |
| 本机 AR 现场尝试应用内最小化 | 当前阻止最小化并提示先结束现场 |
| 本机 AR 现场按 Home | 进入显示对方的 PiP，同时由 `onPause` 结束本机 AR；不保留失效空间现场 |
| AR 指导方离开 | 可进入 PiP，显示对方现场一路；小窗不接受标记输入 |
| 仍在邀请、响铃、等待权限时离开 | 当前没有活动媒体服务，取消当前流程，不允许迟到回调后台启动相机 |
| 从最近任务移除 | 前台服务请求当前 owner 挂断，走正常信令和媒体释放 |
| 通知“静音/取消静音” | 转发给当前 call owner，成功后更新通知；不直接操作原生音频资源 |
| 通知“挂断” | 取消当前通话任务，由既有 finally 发送结束、释放 RTC 和停止服务 |

系统 PiP 位置、缩放和关闭手势由 Android 管理，不申请悬浮窗权限。PiP 的静音与挂断使用系统 `RemoteAction`；Compose 小窗不复制系统控制层。锁屏、PiP 关闭、OEM 任务移除和系统回收仍需真机验证。

## 所有权与过渡结构

`CallForegroundService` 只拥有前台状态和通知，不持有 PeerConnection、Camera、EGL、信令或用户身份。当前媒体由 `AppContainer` 中进程级唯一的 `CallViewModel` 持有，Activity 重建或 PiP 关闭不会触发其清理。应用级 `ActiveCallActions` 只把通知动作路由到当前 owner；注册使用不透明 owner token，旧 owner 无法清除后来注册的 owner。

```text
MainActivity / PiP / in-app mini presentation
        │
        ▼
AppContainer.callModel (进程级过渡 owner) ◄── ActiveCallActions ◄── notification / PiP action
        │
        ├── signaling / RTC / camera / audio
        └── starts, updates, stops
                    │
                    ▼
           CallForegroundService
           (notification only)
```

这是 M3-B 的过渡结构。Activity 销毁和 UI 重建已不决定媒体寿命；后续仍应把活动会话从 UI 型 `CallViewModel` 迁入独立 `CallSessionCoordinator`。进程死亡后不会自动恢复麦克风、相机或旧媒体会话。

## Android 约束

- 服务只在用户已于可见页面触发通话、摄像头和麦克风权限有效、远端接受且即将创建媒体时启动。目标 SDK 35 声明 `camera|microphone` 类型及对应前台服务权限。
- 服务使用 `START_NOT_STICKY`。进程被系统终止后不凭旧通知或 Intent 自动复活媒体。
- Android 13+ 即使通知权限被拒绝，前台服务仍受系统任务管理器展示规则约束；产品接入不得向用户承诺通知抽屉一定可见。通知授权体验仍需后续按需优化。
- Activity `onStop` 只停止空闲联系人轮询。已建立 RTC 且 PiP 可见时保留用户已开启的视频；没有可见 PiP 时暂停摄像头并保留语音；尚未建立 RTC 时取消。
- 通知 channel 使用 LOW、无声音、无振动，避免活动通话重复打扰；锁屏内容为 PRIVATE。
- Manifest 对 `MainActivity` 启用 PiP 和可调整尺寸。API 31+ 使用自动进入，API 26–30 从 `onUserLeaveHint` 手动进入；画面比例按系统允许范围钳制。
- PiP 和应用内迷你窗只解析一路远端源。用户在全屏选中的有效远端来源优先；否则 Show Me/后摄/AR 默认现场，人像模式默认人像。

## 验证边界

单元测试验证通知动作只到当前 owner、PiP 比例策略和紧凑窗口远端来源解析。`:app:testDebugUnitTest` 的 186 个测试全部通过，`:app:assembleDebug` 与 `:app:lintDebug` 通过；合并后的 Debug manifest 包含摄像头/麦克风前台服务权限、服务类型和 PiP Activity 声明。

真实后台媒体、PiP 自动/手动进入与关闭、通知动作、Home/返回、任务移除、锁屏、权限拒绝、来电接听、网络切换、蓝牙路由、进程回收与 OEM 行为必须用双设备验证。没有这些结果前，路线图只标记 M3-A/B 代码闭环，不能标记设备验收完成。

## Changelog

### 1.1.0 - 2026-09-10

- 增加系统 PiP、应用内迷你通话、单远端主源和 PiP 可见时的视频策略。
- 把过渡通话 owner 提升到进程级 `AppContainer`，避免 Activity/PiP 销毁结束仍在进行的通话。

### 1.0.0 - 2026-09-10

- 建立活动媒体前台服务、通知动作桥和 Home 后音频继续/视频暂停规则。
