# 咫尺 Android 来电可达性专项设计

> 范围：Android  
> 目标：后台、锁屏、Doze、进程被回收等情况下，尽可能可靠地收到语音/视频通话请求。  
> 核心技术：FCM + Android Telecom/Core-Telecom + CallStyle Notification + Signaling + WebRTC

---

# 1. 设计目标

咫尺的“常驻”目标不定义为：

> 让 Android 进程永远存在。

而定义为：

> 即使咫尺进程不存在，只要设备联网、FCM 可用且用户没有 Force Stop App，服务器发起来电后，系统仍能重新启动咫尺的来电处理链路，并向用户展示真正的来电界面。

因此整体采用：

```text
FCM                     唤醒 / 通知有来电
        ↓
Android Telecom         注册系统级 VoIP Call
        ↓
CallStyle Notification  响铃 / 接听 / 拒绝 / 锁屏展示
        ↓
Signaling               建立实时信令连接
        ↓
WebRTC                   P2P / TURN 音视频连接
```

不依赖：

```text
永久 Service
永久 WebSocket
1 秒一次心跳
AlarmManager 保活
反复拉起进程
```

Android 12+ 已严格限制后台启动前台服务，因此“永久 Service 保活”本身不是可靠架构。高优先级 FCM 属于允许后台启动通话处理的重要例外，但系统也可能在滥用高优先级 FCM 时将其降级。

---

# 2. 可达性边界

必须明确哪些场景能够保证，哪些场景系统本身就不允许保证。

| 状态 | 目标 |
|---|---|
| App 前台 | 必须收到 |
| App 后台 | 必须收到 |
| 最近任务中被划掉 | 必须收到 |
| App 进程被 LMK 杀死 | 必须收到 |
| 手机锁屏 | 必须收到 |
| Doze | 高优先级 FCM 唤醒 |
| Battery Saver | 尽量正常收到 |
| 重启后 App 未主动打开 | FCM/Telecom 正常初始化后应可收到 |
| Wi-Fi / 4G / 5G 切换 | 应恢复 |
| 暂时断网 | 网络恢复后根据 TTL 判断是否仍响铃 |
| FCM Token 更新 | 自动恢复 |
| 用户关闭普通通知 | Telecom CallStyle 应尽量保持来电能力 |
| 用户 Force Stop | **无法保证** |
| 无 Google Play Services | **FCM 不可用，需要其他 Push Provider** |

Android 的 Force Stop 是硬边界。进入 stopped state 后，应用不能自行启动；FCM 也可能被丢弃。Android 15 对这一行为进一步严格化。

因此产品不能宣传：

> 100% 永远可以收到来电。

正确表述应是：

> 在系统允许的范围内最大化来电可达性。

---

# 3. 总体架构

```text
┌──────────────── Caller Android ────────────────┐
│                                               │
│ Call UI                                       │
│   │                                           │
│   ▼                                           │
│ Signaling Client ───────────────┐              │
└─────────────────────────────────│──────────────┘
                                  │
                                  ▼
                    ┌────────────────────────┐
                    │      Zhichi Server     │
                    │                        │
                    │ Call Orchestrator      │
                    │ Signaling Server       │
                    │ Device Registry        │
                    │ Push Gateway           │
                    └───────────┬────────────┘
                                │
                       FCM HIGH priority
                                │
                                ▼
┌──────────────── Callee Android ─────────────────┐
│                                                 │
│ FirebaseMessagingService                        │
│            │                                    │
│            ▼                                    │
│ IncomingCallCoordinator                         │
│            │                                    │
│            ├──── CallSessionStore               │
│            │                                    │
│            ▼                                    │
│ TelecomController / Core-Telecom                │
│            │                                    │
│            ▼                                    │
│ CallStyle Notification                          │
│       │              │                          │
│     接听             拒绝                       │
│       │                                         │
│       ▼                                         │
│ IncomingCallActivity                            │
│       │                                         │
│       ▼                                         │
│ SignalingClient ─────────────── Server           │
│       │                                         │
│       ▼                                         │
│ WebRTC Session                                  │
└─────────────────────────────────────────────────┘
```

---

# 4. Android Telecom

## 4.1 必须接入 Telecom

咫尺属于真正的 VoIP / Video Call App，不建议单纯通过一个普通 Notification 模拟电话。

Android 官方推荐 VoIP 应用把通话加入 Telecom。这样系统能够理解：

```text
这是一个 Call
而不是普通后台任务
```

并参与：

- 系统通话并发管理
- 蓝牙设备
- Wear OS
- Android Auto
- 音频路由
- Audio Focus
- 系统通话状态
- 前台执行优先级

Core-Telecom 提供 `CallsManager` 来完成这一层。

推荐：

```gradle
implementation("androidx.core:core-telecom:1.0.0")
```

先使用稳定版。

---

# 5. Android 版本策略

咫尺保持：

```text
minSdk = 24
```

因此分为两层。

## API 26+

主实现：

```text
androidx.core:core-telecom
CallsManager
```

注册：

```kotlin
val callsManager = CallsManager(context)

callsManager.registerAppWithTelecom(
    CallsManager.CAPABILITY_BASELINE or
        CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING
)
```

收到来电：

```kotlin
callsManager.addCall(
    callAttributes,
    onAnswer,
    onDisconnect,
    onSetActive,
    onSetInactive
) {
    // RINGING / ACTIVE 生命周期
}
```

Core-Telecom 在 Android 13 及以下使用兼容 Telecom 实现，在 Android 14+ 使用新的通话机制。

## API 24–25

`CallsManager` 要求 API 26，因此保留 Legacy：

```text
FCM
 ↓
IncomingCallForegroundService
 ↓
高优先级来电 Notification
 ↓
IncomingCallActivity
 ↓
WebRTC
```

这部分只作为旧系统兼容层。

不要让 API 24–25 的历史兼容逻辑污染主架构。

---

# 6. Manifest

建议至少声明：

```xml
<uses-permission android:name="android.permission.INTERNET" />

<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />

<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<uses-permission
    android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />

<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
```

Android 14+ 对 Foreground Service Type 有更严格要求，`phoneCall` 类型需要相应前台服务权限，并需要 `MANAGE_OWN_CALLS` 或 Dialer Role。

---

# 7. FCM 的定位

FCM **不是 Signaling Server**。

它只负责：

```text
Wake Up
+
告诉手机：
“call_id=xxx 有来电”
```

绝对不要在 FCM 内塞：

```text
完整 SDP
大量 ICE candidate
WebRTC Session 状态
持续通话信令
```

真正的 SDP / ICE / Hangup / Renegotiation：

```text
WebSocket Signaling
```

负责。

---

# 8. FCM 消息类型

定义：

```text
call_invite
call_cancel
call_state
```

其中：

```text
call_invite
```

必须：

```text
HIGH priority
短 TTL
data-only
```

FCM 官方说明，高优先级消息会尝试立即交付，并可在 Doze 时唤醒设备；这正适合来电这种需要立即展示给用户的事件。

---

# 9. FCM Call Invite Payload

推荐：

```json
{
  "message": {
    "token": "<FCM_TOKEN>",
    "android": {
      "priority": "HIGH",
      "ttl": "30s"
    },
    "data": {
      "type": "call_invite",

      "call_id": "019c...",
      "caller_id": "user_xxx",
      "caller_name": "九云",

      "media_type": "video",

      "issued_at": "2026-09-09T15:30:00Z",
      "expires_at": "2026-09-09T15:30:30Z",

      "call_version": "1",
      "invite_token": "<SHORT_LIVED_OPAQUE_TOKEN>"
    }
  }
}
```

### 为什么使用 data-only

不要：

```json
"notification": {
    ...
}
```

因为 FCM Notification Message 在 App 后台时主要由系统直接放进通知栏，而不会按普通方式交给 `onMessageReceived()`。

咫尺需要：

```text
FCM
→ onMessageReceived()
→ Telecom
→ CallStyle
```

所以来电采用 Data Message。

---

# 10. FCM 收到后的原则

`FirebaseMessagingService.onMessageReceived()` 中：

**只做很少的事情。**

```text
1. parse
2. 检查 expires_at
3. 去重
4. 保存 PendingCall
5. addCall()
6. 发 CallStyle Notification
```

不要：

```text
先请求头像
先请求用户资料
先打开 WebSocket
先请求 5 个 API
然后才显示通知
```

FCM 官方明确建议 `onMessageReceived()` 内立即处理消息和展示通知；耗时异步处理可能因为进程生命周期结束导致通知丢失。

所以：

```text
Push 里直接包含 caller_name
```

头像可以晚一点加载。

---

# 11. IncomingCallCoordinator

所有来电入口统一进入：

```text
IncomingCallCoordinator
```

禁止：

```text
FCM 自己管一套
NotificationReceiver 自己管一套
Activity 自己管一套
WebSocket 又管一套
```

接口建议：

```kotlin
interface IncomingCallCoordinator {

    suspend fun onPushInvite(invite: CallInvite)

    suspend fun answer(callId: String)

    suspend fun reject(callId: String)

    suspend fun cancel(callId: String)

    suspend fun onRemoteCallState(
        callId: String,
        state: RemoteCallState
    )
}
```

内部持有：

```text
CallSessionStore
TelecomController
CallNotificationManager
SignalingClient
CallRepository
```

---

# 12. 来电状态机

客户端：

```text
IDLE
  │
  │ FCM INVITE
  ▼
PUSH_RECEIVED
  │
  ▼
RINGING
  │
  ├──── reject ───────────────► ENDED
  │
  ├──── timeout ──────────────► MISSED
  │
  ├──── remote cancel ────────► ENDED
  │
  ▼
ANSWERING
  │
  ▼
SIGNALING_CONNECTING
  │
  ▼
WEBRTC_CONNECTING
  │
  ▼
ACTIVE
  │
  ▼
ENDING
  │
  ▼
ENDED
```

必须保证状态转换幂等。

例如：

```text
CALL_INVITE
CALL_INVITE
CALL_INVITE
```

重复收到三次，最终仍只能生成：

```text
1 个 CallSession
1 个 Telecom Call
1 个 Notification
```

主键：

```text
call_id
```

---

# 13. Telecom Call

构造：

```kotlin
val attributes = CallAttributesCompat(
    displayName = invite.callerName,
    address = Uri.parse("zhichi:${invite.callerId}"),
    direction = CallAttributesCompat.DIRECTION_INCOMING,
    callType =
        if (invite.mediaType == VIDEO)
            CallAttributesCompat.CALL_TYPE_VIDEO_CALL
        else
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
    callCapabilitiesCompat = ...
)
```

然后：

```text
CallsManager.addCall()
```

成功后代表 Telecom 已经知道：

```text
咫尺现在有一个 RINGING Call
```

Android 官方明确指出 `addCall()` 后 Call 可以处于 `RINGING`，并且应用需要及时发布 CallStyle Notification。

---

# 14. CallStyle Notification

Android 12+ 使用：

```text
NotificationCompat.CallStyle
```

Incoming：

```kotlin
NotificationCompat.CallStyle.forIncomingCall(
    caller,
    declineIntent,
    answerIntent
)
```

它天然支持：

```text
来电人
接听
拒绝
系统通话样式
高通知优先级
```

CallStyle 是 Android 官方专门给来电和持续通话设计的。

---

# 15. 5 秒规则

这一点作为强制设计约束：

```text
Telecom addCall()
        ↓
5 秒以内
        ↓
CallStyle Notification
```

Core-Telecom 官方要求添加通话后 5 秒内发布有效通知；只要有效 Call 和 CallStyle Notification 存在，应用就可以获得通话所需要的前台执行优先级。

所以：

```text
notification 不允许依赖网络
notification 不允许等待头像
notification 不允许等待 WebRTC
```

---

# 16. Notification Channel

创建专用：

```text
calls.incoming
calls.ongoing
```

Incoming：

```text
IMPORTANCE_HIGH
CATEGORY_CALL
AudioAttributes.USAGE_NOTIFICATION_RINGTONE
Vibration = enabled
LockScreen visibility = configurable
```

Ongoing：

```text
ongoing
silent
CallStyle
```

不要把：

```text
普通聊天消息
系统通知
通话
```

放在同一个 NotificationChannel。

---

# 17. Full Screen Intent

锁屏来电使用：

```text
Full Screen Intent
```

但是 Android 14+ 对其进行了限制：

```text
USE_FULL_SCREEN_INTENT
```

主要只允许真正的：

```text
Calling App
Alarm App
```

用户也可以关闭该能力。应通过：

```kotlin
NotificationManager.canUseFullScreenIntent()
```

判断。

策略：

```text
有 Full Screen 权限
    ↓
锁屏 → IncomingCallActivity 全屏

没有
    ↓
CallStyle Heads-up Notification
```

不要为了弹 Activity：

```text
SYSTEM_ALERT_WINDOW
悬浮窗
后台强开 Activity
```

---

# 18. POST_NOTIFICATIONS

Android 13+ 有：

```text
POST_NOTIFICATIONS
```

咫尺仍建议正常向用户申请，因为：

```text
聊天通知
系统通知
未接来电
其他普通通知
```

都需要它。

不过 Android 对正确配置成 Self-managed Calling App 的 `CallStyle` 有特殊豁免机制。

因此 Telecom 接入不只是为了 UI，也能让 Android 正确认识咫尺：

```text
这是通话 App。
```

---

# 19. 不要在收到 FCM 时启动 Camera/Microphone

错误：

```text
FCM
 ↓
直接打开 Camera
直接开始 Microphone
```

Android 14+ 对 camera / microphone 的 while-in-use 权限进行了严格限制。

后台收到 FCM 并不意味着可以直接获得摄像头/麦克风使用权限；后台创建相关 FGS 可能直接抛 `SecurityException`。

正确：

```text
FCM
 ↓
RINGING
 ↓
用户点击“接听”
 ↓
IncomingCallActivity visible
 ↓
启动 WebRTC
 ↓
打开 microphone / camera
```

这也符合隐私预期。

---

# 20. Answer 流程

用户点击：

```text
接听
```

流程：

```text
Notification / Telecom
       │
       ▼
IncomingCallCoordinator.answer(callId)
       │
       ▼
POST /calls/{callId}/answer
       │
       ▼
服务器原子抢占 Answer
       │
       ├── SUCCESS
       │      ↓
       │   Telecom answer()
       │      ↓
       │   打开 CallActivity
       │      ↓
       │   WebSocket
       │      ↓
       │   WebRTC
       │
       └── ALREADY_ANSWERED
              ↓
          结束本机 ringing
```

---

# 21. 多设备来电

一个用户可能：

```text
Phone
Tablet
另一台 Phone
```

服务器应该把 `call_invite` 发到该用户所有有效设备。

例如：

```text
Device A ─┐
Device B ─┼──── 同时响
Device C ─┘
```

Device B 接听：

```text
POST /answer
```

服务器通过事务/CAS：

```text
RINGING
  ↓
ANSWERED(device_B)
```

只有第一个成功。

然后向其他设备发送：

```text
call_cancel
reason=answered_elsewhere
```

结果：

```text
A 停止响铃
C 停止响铃
```

这一步必须由服务器决定，不能依赖客户端自己判断。

---

# 22. 服务端 Call 状态机

```text
CREATED
   │
   ▼
RINGING
   │
   ├──── REJECTED
   ├──── CANCELLED
   ├──── TIMEOUT
   │
   ▼
ANSWERING
   │
   ▼
CONNECTED
   │
   ▼
ENDED
```

数据库：

```text
calls
──────────────────────────────────
id
caller_user_id
callee_user_id
media_type

state
state_version

created_at
expires_at

answered_at
answered_device_id

ended_at
end_reason
```

所有状态变更：

```text
UPDATE ... WHERE state = expected_state
```

防止双接听。

---

# 23. Device Registry

```text
devices
────────────────────────────────
device_id
user_id
installation_id

platform
push_provider

fcm_token

app_version
os_version

token_updated_at
last_seen_at

enabled
```

不要直接认为：

```text
一个 user = 一个 FCM token
```

正确：

```text
User
 ├─ Device 1
 │    └─ token
 ├─ Device 2
 │    └─ token
 └─ Device 3
      └─ token
```

---

# 24. FCM Token 生命周期

客户端至少处理：

```kotlin
FirebaseMessagingService.onNewToken()
```

然后：

```text
PUT /devices/{deviceId}/push-token
```

App 正常启动时，也主动：

```kotlin
FirebaseMessaging.getInstance().token
```

与服务器同步一次。

FCM Token 会因为恢复设备、重装、清除数据等发生变化，因此不能把首次注册的 Token 永久保存。Firebase 也建议持续维护注册时间并清理无效 Token。

服务器遇到：

```text
UNREGISTERED
```

立即：

```text
device.push_enabled = false
```

不要继续无限发送。

---

# 25. FCM High Priority 使用规则

只有真正时间敏感、立即展示给用户的事件使用：

```text
HIGH
```

推荐：

```text
call_invite        HIGH
call_cancel        HIGH
```

其他：

```text
profile_updated
contact_sync
普通后台同步
```

全部：

```text
NORMAL
```

原因是 Android/FCM 会观察高优先级消息是否真正用于用户可见的时间敏感事件；滥用可能导致以后被降成 Normal。

因此绝对禁止：

```text
每分钟 HIGH heartbeat
HIGH keep-alive
HIGH presence ping
```

---

# 26. WebSocket 策略

不要为了来电：

```text
24h 永久在线 WebSocket
```

推荐状态：

```text
App foreground
    → WebSocket ON

Incoming ringing
    → WebSocket ON

Active call
    → WebSocket ON

App background + idle
    → WebSocket OFF
    → 等 FCM 唤醒
```

即：

```text
FCM = Wake Channel
WebSocket = Live Channel
```

这样比单纯依赖后台长连接稳定得多，也更省电。

---

# 27. Incoming Push 到响铃完整流程

```text
Caller
  │
  │ POST /calls
  ▼
Server
  │
  ├── Create Call
  │
  ├── state = RINGING
  │
  └── FCM HIGH
          │
          ▼
Google FCM
          │
          ▼
Android
          │
          ▼
ZhichiFirebaseMessagingService
          │
          ├── validate TTL
          ├── dedupe call_id
          └── IncomingCallCoordinator
                    │
                    ▼
                Telecom
                    │
                    ▼
             CallStyle Notification
                    │
           ┌────────┴────────┐
           │                 │
         reject            answer
           │                 │
           ▼                 ▼
         Server       POST /answer
                             │
                             ▼
                           claim
                             │
                             ▼
                      CallActivity
                             │
                             ▼
                       Signaling WS
                             │
                             ▼
                         WebRTC
```

---

# 28. Call Cancel

呼叫方挂机时：

```text
Server state:
RINGING → CANCELLED
```

同时：

```text
WebSocket call_cancel
+
FCM call_cancel
```

为什么两个都发：

```text
如果被叫此时已经打开 WebSocket
    → WS 几乎立即停止响铃

如果 WS 尚未建立
    → FCM 仍然能通知其停止
```

客户端：

```text
收到 call_cancel
    ↓
IncomingCallCoordinator.cancel()
    ↓
Telecom disconnect()
    ↓
remove notification
    ↓
stop ringtone
```

---

# 29. 超时

服务器决定最终超时。

例如设计：

```text
ring_timeout = 30 seconds
```

这只是产品参数，不依赖 FCM。

服务器：

```text
expires_at = created_at + 30s
```

客户端收到 FCM 时：

```kotlin
if (now >= expiresAt) {
    return
}
```

因此一条延迟 40 秒才到的旧 FCM：

```text
不会突然让手机响起来。
```

---

# 30. App 进程死亡

这是本设计最重要的测试场景。

预期：

```text
咫尺 Process
    ✕ 不存在

FCM
    ↓
FirebaseMessagingService
    ↓
新进程启动
    ↓
IncomingCallCoordinator
    ↓
Telecom
    ↓
CallStyle
```

所以任何 IncomingCall 需要的数据都不能只存在：

```text
Singleton
ViewModel
Activity
内存变量
```

至少持久化：

```text
call_id
caller
media_type
expires_at
call_version
state
```

可以使用：

```text
Room
```

或者轻量：

```text
DataStore + serialized PendingCall
```

推荐 Room，因为以后要支持：

```text
Call History
Missed Call
Diagnostics
```

---

# 31. 重启

不设计：

```text
BOOT_COMPLETED
→ 启动永久 phoneCall Service
```

Android 15+ 对从 BOOT_COMPLETED 启动 phoneCall 前台服务有额外限制。

重启后只做：

```text
初始化基础组件
同步 Device Registration
确认 Push Token
必要时恢复 Telecom Registration
```

然后继续：

```text
等待 FCM。
```

---

# 32. “常驻”最终策略

咫尺没有通话时：

```text
Process
可以死。

Service
可以没有。

WebSocket
可以断。

Notification
可以没有。
```

但是：

```text
FCM Token
必须有效。

Server Device Registry
必须有效。

Telecom Registration
必须有效。
```

有通话时：

```text
Telecom Call
+
CallStyle Notification
+
Signaling
+
WebRTC
```

构成真正的高优先级实时会话。

---

# 33. 无 GMS 设备

FCM 依赖 Google Play Services/Google APIs 环境。

因此 Push 层从一开始抽象：

```kotlin
interface PushProvider {
    fun getToken(): String?
}
```

服务器：

```text
PushGateway
 ├── FCM
 ├── HMS       future
 ├── Xiaomi    future
 ├── OPPO      future
 └── vivo      future
```

第一阶段：

```text
Japan / Google Play
→ FCM only
```

如果以后进入中国大陆：

```text
再增加国产 Push Provider
```

不要把 FCM 直接写死到业务模型里。

---

# 34. 来电可达性诊断页

建议咫尺设置里增加：

```text
设置
└── 通话
    └── 来电可达性
```

显示：

```text
✓ FCM 已连接
✓ Push Token 已注册
✓ Telecom 已注册
✓ 通知可用
✓ 全屏来电可用
✓ 麦克风权限
✓ 摄像头权限
✓ Google Play Services 可用
✓ 最近一次 Push：13:42:31
```

异常时：

```text
⚠ 全屏来电已关闭
  [前往设置]

⚠ Google Play 服务不可用
  无法通过 FCM 接收后台来电
```

这比用户说：

> “为什么昨天没响？”

然后开发者完全不知道哪里断了，要好很多。

---

# 35. Telemetry

每一通电话记录时间点：

```text
call_created_at

push_requested_at
fcm_accepted_at

push_received_at

telecom_added_at
notification_posted_at

answer_clicked_at
answer_server_accepted_at

signaling_connected_at
ice_connected_at

first_audio_at
first_video_frame_at

ended_at
```

客户端上传：

```text
push_receive
incoming_notification_shown
incoming_answer
incoming_reject
incoming_timeout
telecom_add_failed
full_screen_denied
signaling_failed
ice_failed
```

以后可以准确区分：

```text
“没收到来电”
```

到底是：

```text
Server 没发
FCM 没到
FCM 到了但 App 没处理
Telecom 失败
Notification 失败
用户没看到
Signaling 失败
WebRTC 失败
```

---

# 36. 推荐内部指标

这是工程目标，不是对用户的 SLA：

```text
Call Created
    ↓
Push Received
    ↓
Notification Visible
```

重点监控：

```text
push_receive_rate

push_to_notification_ms

call_answer_success_rate

answer_to_signaling_ms

answer_to_ice_connected_ms

ghost_ringing_rate

duplicate_notification_rate
```

特别关注：

```text
ghost_ringing_rate
```

即：

> 呼叫方已经挂机，被叫仍然响。

这会严重破坏通话产品体验。

---

# 37. 必须测试的矩阵

至少覆盖：

### Process

```text
前台
后台
Home
Recent Task 划掉
LMK kill
Force Stop
```

### Device

```text
屏幕亮
锁屏
Doze
Battery Saver
重启
```

### Network

```text
Wi-Fi
4G
5G
Wi-Fi → 5G
5G → Wi-Fi
断网 10 秒恢复
飞行模式恢复
```

### Permissions

```text
Notification Allowed
Notification Denied
Full Screen Allowed
Full Screen Denied
Camera Denied
Microphone Denied
```

### Call

```text
正常接听
拒绝
呼叫方取消
30 秒超时
重复 Push
延迟 Push
Push 乱序
多设备同时响
另一设备接听
同时两个人打入
```

### OEM

至少：

```text
Google Pixel
Samsung
Xiaomi / HyperOS
OPPO / ColorOS
```

---

# 38. ADB 必测场景

开发阶段专门做：

```text
kill process
进入 doze
后台限制
通知关闭
Force Stop
```

不要只测试：

```text
Android Studio
App 正好开着
两台手机同 Wi-Fi
```

这种环境下能响，不代表来电系统真正完成。

---

# 39. 模块结构建议

Android：

```text
android/app/src/main/java/.../

call/
├── model/
│   ├── CallId.kt
│   ├── CallInvite.kt
│   ├── CallState.kt
│   └── CallSession.kt
│
├── incoming/
│   ├── IncomingCallCoordinator.kt
│   └── IncomingCallActivity.kt
│
├── telecom/
│   ├── TelecomController.kt
│   ├── CoreTelecomController.kt
│   └── LegacyTelecomController.kt
│
├── notification/
│   ├── CallNotificationManager.kt
│   └── CallNotificationChannels.kt
│
├── push/
│   ├── ZhichiFirebaseMessagingService.kt
│   ├── PushMessageParser.kt
│   └── PushTokenManager.kt
│
├── signaling/
│   ├── SignalingClient.kt
│   └── SignalingSession.kt
│
├── persistence/
│   ├── CallSessionEntity.kt
│   └── CallSessionDao.kt
│
└── diagnostics/
    └── CallReachabilityDiagnostics.kt
```

服务端：

```text
server/internal/

call/
├── service.go
├── model.go
├── repository.go
└── state_machine.go

push/
├── gateway.go
├── fcm.go
└── device_registry.go

signaling/
└── ...
```

---

# 40. 服务端 API

建议：

```text
PUT  /v1/devices/{deviceId}/push-token

POST /v1/calls
GET  /v1/calls/{callId}

POST /v1/calls/{callId}/answer
POST /v1/calls/{callId}/reject
POST /v1/calls/{callId}/cancel
POST /v1/calls/{callId}/hangup
```

Signaling：

```text
WSS /v1/signaling
```

认证后：

```json
{
  "type": "join_call",
  "call_id": "..."
}
```

之后才交换：

```text
offer
answer
candidate
renegotiation
hangup
```

---

# 41. 第一阶段实现顺序

## Phase 1

> 状态：已实现。
> 服务端 `internal/push`（FCM HTTP v1，凭据走 ADC，`FCM_PROJECT_ID=zisee-app`，与 Firestore 同项目）+ `devices` 表 push token 字段 +
> `PUT /v1/devices/{deviceId}/push-token`；通话进入 `ringing` 时 best-effort 唤醒被叫方所有注册设备。
> Android `com.zisee.app.push`：`ZiseeFirebaseMessagingService` → `IncomingPushGate`
> （过期丢弃 + `call_id` 去重）→ `calls.incoming` heads-up 通知；`PushTokenManager` 在每次
> 后台登录时同步 token。Telecom / CallStyle / full-screen intent 仍属 Phase 2。

先把：

```text
FCM Token
Device Registry
HIGH Priority data push
```

打通。

要求：

```text
kill process
→ 发 push
→ FirebaseMessagingService 确实被启动
```

---

## Phase 2

接：

```text
Core-Telecom
+
CallStyle
+
Answer / Reject
```

做到：

```text
进程死亡
锁屏
Doze
```

依然可以看到真正的来电。

---

## Phase 3

接入：

```text
Signaling
+
现有 WebRTC
```

形成：

```text
FCM
→ Ring
→ Answer
→ Signaling
→ WebRTC
```

---

## Phase 4

加入：

```text
call_cancel
timeout
多设备
duplicate handling
Room state
```

---

## Phase 5

加入：

```text
Diagnostics
Telemetry
OEM tests
Chaos tests
```

---

# 42. 验收标准

这个专项完成的标准不是：

> 我手机上测试能响。

而是：

### Case A

```text
App 完全不在内存
屏幕锁定
Doze
```

服务器呼叫：

```text
→ 手机出现来电
→ 能接听
→ WebRTC 建立
```

### Case B

```text
App 被 recent tasks 划掉
```

仍然：

```text
→ 收到
→ 响铃
→ 接听
```

### Case C

呼叫方取消：

```text
→ 被叫立即停止响铃
```

### Case D

30 秒前的过期 Push：

```text
→ 不响
```

### Case E

同一 FCM 重复到三次：

```text
→ 只有一条来电
```

### Case F

两个设备同时登录：

```text
→ 两台同时响
→ 任意一台接听
→ 另一台立即结束
```

### Case G

用户 Force Stop：

```text
→ 明确认定为系统不可恢复状态
→ 用户再次主动打开咫尺后恢复
```

---

# 43. 最终架构结论

咫尺的后台通话架构固定为：

```text
                    ┌── Telecom
FCM HIGH ──────────►│
                    ├── CallStyle
                    │
                    └── IncomingCallCoordinator
                               │
                               ▼
                           Signaling
                               │
                               ▼
                            WebRTC
```

而不是：

```text
Permanent Service
      +
Permanent WebSocket
      +
疯狂保活
```

原则：

> **Idle 时允许咫尺死。**

> **有来电时，由 FCM 把它唤醒。**

> **唤醒后立即把 Call 注册给 Android Telecom。**

> **Telecom + CallStyle 负责系统级来电生命周期。**

> **用户接听以后才启动真正的 Signaling、Camera、Microphone 和 WebRTC。**

这才是咫尺长期可维护、符合现代 Android 后台规则，也最接近 WhatsApp、Telegram、Discord 等 VoIP App 所需要的通话基础设施方向。