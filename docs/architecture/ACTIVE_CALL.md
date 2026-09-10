---
title: M3 活动通话后台生命周期
document_id: ARCH-ACTIVE-CALL-001
version: 1.0.0
status: Active
created: 2026-09-10
updated: 2026-09-10
applies_to: "M3-A 第一阶段"
owners:
  - android
  - core
---

# M3 活动通话后台生命周期

产品目标见 [M3 通话多任务专项](../product/CALL_MULTITASKING.md)。本阶段建立 Home 后继续语音的最小安全闭环，系统 PiP、应用内迷你通话和屏幕共享仍待后续阶段。

## 当前行为

| 事件 | 行为 |
| --- | --- |
| 已建立 RTC 媒体后按 Home | 通话与音频继续；摄像头 Track 暂停，对端收到真实关闭状态；常驻通知提供返回、静音、挂断 |
| 回到通话 | 用户原来开启摄像头时恢复 Track；用户主动关闭的摄像头不恢复 |
| AR 现场方离开 | 沿用现有 `onPause` 结束 AR，再暂停普通摄像头；不保留失效空间现场 |
| 仍在邀请、响铃、等待权限时离开 | 当前没有活动媒体服务，取消当前流程，不允许迟到回调后台启动相机 |
| 从最近任务移除 | 前台服务请求当前 owner 挂断，走正常信令和媒体释放 |
| 通知“静音/取消静音” | 转发给当前 call owner，成功后更新通知；不直接操作原生音频资源 |
| 通知“挂断” | 取消当前通话任务，由既有 finally 发送结束、释放 RTC 和停止服务 |

锁屏、PiP 关闭、OEM 任务移除和系统回收仍需真机验证。当前代码不具备视频浮窗，因此后台只保留语音；这不是最终 M3-B 行为。

## 所有权与过渡结构

`CallForegroundService` 只拥有前台状态和通知，不持有 PeerConnection、Camera、EGL、信令或用户身份。当前媒体仍由 Activity 保留的 `CallViewModel` 持有，应用级 `ActiveCallActions` 只把通知动作路由到当前 owner。注册使用不透明 owner token，旧 ViewModel 无法清除后来注册的 owner。

```text
MainActivity visibility
        │
        ▼
CallViewModel (当前过渡 owner) ◄── ActiveCallActions ◄── notification action
        │
        ├── signaling / RTC / camera / audio
        └── starts, updates, stops
                    │
                    ▼
           CallForegroundService
           (notification only)
```

这是 M3-A 第一阶段。后续把活动会话迁入独立 `CallSessionCoordinator`，使配置变化、Activity 销毁和 UI 重建不决定媒体寿命；迁移前服务不会假装自己拥有媒体，也不会在进程重建后自动恢复麦克风或相机。

## Android 约束

- 服务只在用户已于可见页面触发通话、摄像头和麦克风权限有效、远端接受且即将创建媒体时启动。目标 SDK 35 声明 `camera|microphone` 类型及对应前台服务权限。
- 服务使用 `START_NOT_STICKY`。进程被系统终止后不凭旧通知或 Intent 自动复活媒体。
- Android 13+ 即使通知权限被拒绝，前台服务仍受系统任务管理器展示规则约束；产品接入不得向用户承诺通知抽屉一定可见。通知授权体验仍需后续按需优化。
- Activity `onStop` 只停止空闲联系人轮询。已建立 RTC 时暂停摄像头，保留通话任务；尚未建立 RTC 时取消。
- 通知 channel 使用 LOW、无声音、无振动，避免活动通话重复打扰；锁屏内容为 PRIVATE。

## 验证边界

单元测试验证通知动作只到当前 owner，旧 owner 不能清除新注册。`:app:testDebugUnitTest` 的 183 个测试全部通过（新增 2 个动作路由测试），`:app:assembleDebug` 与 `:app:lintDebug` 通过；合并后的 Debug manifest 包含摄像头/麦克风前台服务权限和类型。

真实后台媒体、通知动作、Home/返回、任务移除、锁屏、权限拒绝、来电接听、网络切换、蓝牙路由、进程回收与 OEM 行为必须用双设备验证。没有这些结果前，路线图只标记 M3-A 代码闭环，不能标记设备验收完成。

## Changelog

### 1.0.0 - 2026-09-10

- 建立活动媒体前台服务、通知动作桥和 Home 后音频继续/视频暂停规则。
