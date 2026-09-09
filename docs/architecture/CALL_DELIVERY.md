# 咫尺来电可达性专项设计

> 范围：Android、国产 Android ROM、HarmonyOS
> 目标：在后台、锁屏、Doze、进程被回收等情况下，最大化真实 VoIP 来电可达性。
> 核心原则：**不要保活 App，要保活“来电通道”。**

---

# 1. 目标重新定义

咫尺不追求：

```text
App 永不被杀
Service 永久运行
WebSocket 永久在线
```

而追求：

```text
即使 App 进程已经不存在

服务器
   ↓
系统 Push 通道
   ↓
操作系统感知有一通真实 VoIP 来电
   ↓
展示来电
   ↓
用户接听
   ↓
恢复咫尺进程
   ↓
Signaling
   ↓
WebRTC
```

因此：

```text
后台空闲
→ 允许进程死亡

来电
→ 系统级 Push 唤醒/展示

通话
→ Telecom / OEM Call Framework 提升生命周期优先级
```

---

# 2. 不再假设所有 Android 都是 FCM

原本：

```text
Server
  ↓
FCM
  ↓
Android
```

修改为：

```text
                         ┌─ FCM
                         ├─ Xiaomi VoIP Push
                         ├─ OPPO Push
Call Orchestrator ──────►├─ vivo Push
                         ├─ HONOR Push
                         └─ HarmonyOS Push Kit
```

客户端统一抽象：

```kotlin
interface PushProvider {

    val type: PushProviderType

    suspend fun initialize()

    suspend fun getToken(): String?

    fun capabilities(): PushCapabilities
}
```

Capabilities：

```kotlin
data class PushCapabilities(
    val canWakeProcess: Boolean,
    val supportsSystemNotification: Boolean,
    val supportsVoipMessage: Boolean,
    val supportsDeliveryReceipt: Boolean,
    val supportsNotificationRecall: Boolean
)
```

**不要根据 `Build.MANUFACTURER` 直接假设能力。**

必须：

```text
检测 SDK
+
检测系统 capability
+
检测 token
+
记录实际发送结果
```

---

# 3. Push 与业务彻底分层

业务层永远只认识：

```text
CALL_INVITE
CALL_CANCEL
CALL_ANSWERED_ELSEWHERE
CALL_ENDED
```

不认识：

```text
FCM
MiPush
OPush
VivoPush
HonorPush
```

统一模型：

```kotlin
data class CallPushEnvelope(
    val type: CallPushType,
    val callId: String,
    val version: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val payload: CallInvitePayload
)
```

每个 Provider：

```text
FCM Adapter
Xiaomi Adapter
OPPO Adapter
vivo Adapter
HONOR Adapter
HarmonyOS Adapter
```

最终全部进入：

```text
IncomingCallCoordinator
```

---

# 4. Global Android

拥有正常 Google Play Services：

```text
FCM HIGH priority data message
        ↓
FirebaseMessagingService
        ↓
IncomingCallCoordinator
        ↓
Android Telecom
        ↓
CallStyle
```

这是海外 Android 主路径。

此时：

```text
FCM = Wake Channel
WebSocket = Live Signaling Channel
WebRTC = Media Channel
```

---

# 5. 中国大陆 Android

不能假设：

```text
FCM 一定存在
```

更不能假设：

```text
FCM 即使装了就一定长期可靠
```

国内发行版本使用：

```text
OEM System Push
```

优先级：

```text
Xiaomi / Redmi
    → MiPush VoIP

OPPO / OnePlus / realme
    → OPPO Push

vivo / iQOO
    → vivo Push

HONOR
    → HONOR Push

Huawei HarmonyOS
    → HarmonyOS 原生 Push Kit
```

---

# 6. Xiaomi / MIUI / HyperOS

## 6.1 Xiaomi 是重点优化对象

不能把 Xiaomi 简单设计为：

```text
MiPush Data Message
→ App
```

因为普通 MiPush 的透传消息服务已经停止。

小米官方从 2022 年 9 月起停止提供普通透传消息下发。

但是，小米现在提供了一个对咫尺非常重要的能力：

```text
VoIP Service Kit
```

这是专门针对：

```text
IM
语音通话
视频通话
```

设计的系统能力。

官方定义包括：

* 来电一键接听
* 横幅通知
* 静音/取消静音
* VoIP 消息
* `onCallMessage()`
* 消息回执
* VoIP 能力检测

所以 Xiaomi 的正确架构是：

```text
Zhichi Server
      ↓
MiPush VoIP Message
      ↓
HyperOS System Push
      ↓
onCallMessage()
      ↓
IncomingCallCoordinator
      ↓
Telecom / Call UI
      ↓
Signaling
      ↓
WebRTC
```

---

# 7. Xiaomi Capability Detection

初始化后检查：

```kotlin
val pushSupported =
    MiPushSdk.getInstance().isSupport(context)

val voipSupported =
    MiPushSdk.getInstance().isPushSupport(
        MiPushSdk.FLAG_SUPPORT_CALLKIT
    )
```

只有：

```text
pushSupported == true
AND
voipSupported == true
```

才标记：

```text
XIAOMI_NATIVE_VOIP
```

否则降级：

```text
XIAOMI_NOTIFICATION_PUSH
```

再不行：

```text
FCM
```

如果设备有 GMS。

---

# 8. Xiaomi VoIP 消息

服务端：

```text
message-type: 3
```

即：

```text
VoIP Message
```

核心数据：

```json
{
  "call_id": "...",
  "caller_id": "...",
  "caller_name": "九云",
  "media_type": "video",
  "issued_at": "...",
  "expires_at": "...",
  "version": 1,
  "invite_token": "..."
}
```

小米允许 VoIP `extraData` 携带最多约 4 KB 自定义业务信息。

客户端：

```kotlin
override fun onCallMessage(message: CallMessage) {

    val envelope =
        parser.parse(message.message)

    incomingCallCoordinator
        .onIncomingPush(
            PushProviderType.XIAOMI,
            envelope
        )
}
```

---

# 9. Xiaomi TTL

绝对不要用默认长 TTL。

小米 VoIP API 默认缓存时间可以达到一小时，因此咫尺必须自己设置短 TTL。

例如：

```text
Call Ring Timeout = 30s
Push TTL          = 35s
```

客户端仍然二次判断：

```kotlin
if (clock.now() >= envelope.expiresAt) {
    ignore()
}
```

这样：

```text
断网
↓
一分钟后恢复
↓
收到旧 Push
```

不会突然响起已经结束的电话。

---

# 10. Xiaomi 消息分类

2026 年小米 Push 新规已经明确把：

```text
一对一语音通话
一对一视频通话
通话发起
通话结束
未接来电
```

归入：

```text
私信消息 → 音视频通话
```

而不是普通运营通知。

这非常适合咫尺。

因此建立专门：

```text
zhichi_call_incoming
```

不要拿：

```text
general
news
marketing
```

Channel 发来电。

---

# 11. Xiaomi 2026 模板要求

小米正在进一步要求私信消息模板化。

2026 年的新规则要求私信消息携带：

```text
channel_id
+
template_id
```

并要求既有私信 Channel 在 2026 年 12 月 31 日前完成模板接入，否则会影响下发。

因此 Server 配置不能把这些 ID 写死进业务：

```go
type XiaomiPushConfig struct {
    VoipChannelID string
    VoipTemplateID string
}
```

放：

```text
Push Provider Configuration
```

统一管理。

---

# 12. Xiaomi VoIP Service Kit 不是无条件开放

这是一个很重要的产品风险。

小米目前对 VoIP Service Kit 有申请条件，包括：

```text
政企通信
纯 IM
客服
告警
以及审核认可的特殊场景
```

官方申请流程还要求：

```text
应用信息
包名
AppID
Channel
实际使用场景
来电界面截图
相关设置截图
测试方式
```

并注明审核回复周期为 15 个工作日。

咫尺本身就是：

```text
真实用户之间的视频通信 App
```

在产品性质上比“营销来电”更符合该能力的设计目标，但：

> **最终能否取得权限必须以小米审核结果为准。**

因此架构不能建立在：

```text
VoIP Kit 一定审核通过
```

这个前提上。

必须拥有 fallback。

---

# 13. Xiaomi 最终策略

```text
if Xiaomi:
    if Native VoIP supported && permission granted:
        MiPush VoIP Service Kit
    else:
        MiPush Private Notification
```

如果国际版设备同时有 GMS：

```text
Global Xiaomi
    → FCM 为主

China Xiaomi
    → MiPush 为主
```

不要默认同时双发。

---

# 14. OPPO / OnePlus / realme / ColorOS

OPPO Push 是：

```text
ColorOS System Push Channel
```

由系统维护长连接，不依赖咫尺自己驻留后台。

其历史官方文档明确：

```text
普通 Push 主要是通知栏消息

消息可以由系统展示
而不先启动目标 App
```

因此不能写：

```text
OPush callback
→ 一定可以像 FCM data-only 一样立即执行业务
```

这是错误前提。

---

# 15. ColorOS 新消息分类

OPPO 在新的消息分类中已经支持：

```text
通知栏
锁屏
横幅
铃声
震动
```

并明确：

```text
聊天交友
电话短信
办公

可以申请更高等级提醒能力
```

相关新分类从 ColorOS 13+ 开始支持，并持续覆盖更多版本。

所以咫尺申请：

```text
即时通信 / 电话类
```

而不是：

```text
普通公信消息
```

---

# 16. OPPO 路径

理想：

```text
OPPO Push
    ↓
System Incoming Notification
    ↓
用户点击 / 系统允许进入 App
    ↓
IncomingCallCoordinator
    ↓
查询服务器 Call State
    ↓
Telecom
    ↓
WebRTC
```

这里有一个关键设计：

> **任何厂商通知被点击后，不直接相信 Push 本身仍有效。**

必须：

```text
GET /v1/calls/{callId}
```

服务器返回：

```text
RINGING
```

才允许进入接听流程。

否则：

```text
CANCELLED
ANSWERED
TIMEOUT
ENDED
```

立即关闭界面。

这样可以减轻 OEM Push 延迟导致的“幽灵来电”。

---

# 17. OPPO 分发来源也可能影响策略

OPPO 对非 OPPO 软件商店官方来源应用的部分 Push 场景存在额外管控策略。

所以测试矩阵必须加入：

```text
OPPO Store 安装

vs

官网下载 APK / sideload
```

不能只用：

```text
adb install
```

测试完就认为真实用户环境可靠。

---

# 18. vivo / iQOO / OriginOS

vivo 同样提供自己的系统 Push 服务。

设备注册后：

```text
vivo Push Token
```

上传服务器。

服务端：

```text
VivoPushProvider
```

进行推送。

vivo 官方开放平台当前仍提供推送服务和独立 Push SDK。

但目前公开可核实资料不足以让我确认：

```text
vivo 普通 Push
```

是否在所有当前 OriginOS 版本上都提供一个等价于：

```text
FCM high-priority data
或 Xiaomi onCallMessage
```

的 VoIP 业务唤醒接口。

因此设计上不能猜。

第一阶段把 vivo 定义为：

```text
SYSTEM_NOTIFICATION_PUSH
```

而不是：

```text
GUARANTEED_PROCESS_WAKE
```

等拿到 vivo 官方 VoIP / 私信特殊通道审核能力后，再升级 Capability。

---

# 19. HONOR / MagicOS

荣耀已经拥有独立的：

```text
HONOR Push
```

SDK 目前提供：

```text
Push 初始化
Token 获取
Token 删除
Push Support 检测
通知中心状态检测
```

例如：

```text
HonorPushClient.checkSupportHonorPush()
HonorPushClient.getPushToken()
HonorPushClient.getNotificationCenterStatus()
```

因此：

```text
HONOR
→ HonorPushProvider
```

而不是继续认为：

```text
荣耀 = 华为 HMS Push
```

这是现在架构上必须区分的。

---

# 20. Huawei 是另一种情况

新一代华为设备不能再简单放到：

```text
Android OEM
```

这一层处理。

针对 HarmonyOS NEXT / HarmonyOS 5+ / 6 / 7：

```text
单独视为 HarmonyOS Platform
```

当前 HarmonyOS 已经提供专门：

```text
Push Kit VoIP Message
+
CallServiceKit
```

其中 Push Kit 明确支持：

```text
VoIP
BACKGROUND
IM
```

等场景化消息。

通话系统又提供：

```text
voipCall.reportIncomingCall()
voipCall.reportOutgoingCall()
voipCall.reportCallStateChange()
```

因此未来华为版本应当：

```text
HarmonyOS Push Kit
       ↓
VoIP Message
       ↓
CallServiceKit
       ↓
Zhichi Signaling
       ↓
WebRTC / HarmonyOS RTC implementation
```

而不是：

```text
HMS Push
→ Android Telecom
```

硬套 Android 架构。

---

# 21. Platform Architecture

最终形成：

```text
                    Zhichi Call Backend
                           │
                           ▼
                    Push Orchestrator
                           │
          ┌────────────────┼──────────────────┐
          │                │                  │
          ▼                ▼                  ▼
       GLOBAL            CHINA             HARMONY
          │                │                  │
        FCM      ┌─────────┼────────┐      Push Kit
                 │         │        │          │
               Xiaomi    OPPO     vivo       VoIP
                 │         │        │          │
             VoIP Kit   OPush    VPush    CallServiceKit
                 │         │        │
                 └─────────┴────────┘
                           │
                           ▼
             IncomingCallCoordinator
                           │
                           ▼
                       Signaling
                           │
                           ▼
                        WebRTC
```

---

# 22. 国内版和国际版建议拆 Build Flavor

不建议：

```text
一个 APK
+
FCM
+
HMS
+
MiPush
+
OPPO
+
vivo
+
HONOR
```

全塞进去。

原因：

```text
APK 增大
初始化复杂
SDK 冲突
隐私合规声明膨胀
不同区域的数据处理要求不同
厂商 SDK 更新风险
```

建议：

```text
productFlavors {

    global {
        dimension = "market"
    }

    china {
        dimension = "market"
    }
}
```

---

# 23. Global Build

```text
zhichi-global.apk
```

包含：

```text
FCM
Android Telecom
WebRTC
```

必要时以后增加：

```text
Xiaomi Global Push
```

但不是第一优先级。

---

# 24. China Build

```text
zhichi-cn.apk
```

包含：

```text
MiPush
OPPO Push
vivo Push
HONOR Push
Android Telecom
WebRTC
```

根据当前设备：

```text
只激活对应厂商 Provider
```

例如 Xiaomi：

```text
MiPush.initialize()

OPPO SDK
不初始化

vivo SDK
不初始化
```

---

# 25. Xiaomi SDK 还要区分区域

小米官方目前对：

```text
中国大陆发行
```

与：

```text
非中国大陆发行
```

提供不同 Push SDK / 数据存储配置要求。

这进一步说明：

```text
global / china flavor
```

是更干净的工程方案。

---

# 26. Device Registry 重构

原：

```text
device_id
fcm_token
```

不够。

改为：

```text
devices
────────────────────────────

device_id

user_id
installation_id

platform
manufacturer
brand
model

os_name
os_version

app_distribution
app_version

last_seen_at
```

然后独立：

```text
device_push_tokens
────────────────────────────

device_id

provider

token

capabilities

registered_at
last_verified_at

last_delivery_at
last_delivery_result

enabled
```

一个设备允许：

```text
多个 Push Token
```

但有：

```text
一个 Primary Provider
```

---

# 27. Push Provider Selection

服务器计算：

```text
PushRoute
```

例如：

```text
Pixel
GMS=true
    → FCM

Samsung Japan
GMS=true
    → FCM

Xiaomi Japan
GMS=true
    → FCM

Xiaomi China
MiPushVoIP=true
    → Xiaomi VoIP

Xiaomi China
MiPushVoIP=false
    → Xiaomi private notification

OPPO China
    → OPPO Push

vivo China
    → vivo Push

HONOR China
    → HONOR Push

HarmonyOS
    → HarmonyOS Push Kit
```

---

# 28. 不要简单双发

例如 Xiaomi：

```text
FCM
+
MiPush
```

同时发，可能：

```text
出现两个系统通知
两个 callback
双响铃
```

客户端虽然可以：

```text
call_id dedupe
```

但是：

> 系统厂商自己已经展示出来的 Notification，不一定能在 App 去重前阻止。

因此：

```text
Primary Push Provider
```

原则上只选一个。

如果以后真的设计 fallback：

```text
Primary
↓
Provider delivery failure
↓
Secondary
```

而不是：

```text
两个同时发。
```

---

# 29. Server Push Orchestrator

```go
type PushOrchestrator interface {

    SendCallInvite(
        ctx context.Context,
        device Device,
        call Call,
    ) PushResult
}
```

内部：

```text
resolveRoute()
    ↓
buildProviderPayload()
    ↓
send()
    ↓
recordProviderMessageId()
    ↓
recordReceipt()
```

Provider：

```text
FcmProvider
XiaomiPushProvider
OppoPushProvider
VivoPushProvider
HonorPushProvider
HarmonyPushProvider
```

---

# 30. Push Receipt

能够获得回执的 Provider：

```text
必须使用。
```

例如 Xiaomi VoIP Push 官方支持服务端消息回执。

记录：

```text
PUSH_REQUESTED
PROVIDER_ACCEPTED
PROVIDER_DELIVERED
CLIENT_RECEIVED
NOTIFICATION_SHOWN
ANSWERED
```

于是以后可以知道：

```text
Xiaomi Push
provider delivered

但是

咫尺没有响
```

说明：

```text
问题在 Client / OS 展示
```

而不是推送服务器。

---

# 31. IncomingCallCoordinator

不管：

```text
FCM
MiPush
OPPO
vivo
HONOR
Harmony
```

全部：

```text
onIncomingPush()
```

例如：

```kotlin
suspend fun onIncomingPush(
    provider: PushProviderType,
    envelope: CallPushEnvelope
) {

    if (envelope.isExpired()) {
        return
    }

    if (sessionStore.exists(envelope.callId)) {
        merge(envelope)
        return
    }

    val serverState =
        callRepository.fetchState(
            envelope.callId
        )

    if (serverState != RINGING) {
        return
    }

    sessionStore.create(...)

    telecomController.showIncomingCall(...)
}
```

---

# 32. 必须服务器二次确认 Call

国产 Push 最大的问题之一：

```text
延迟
```

所以国产端进入 App 时：

```text
Push == Hint
```

而不是最终真相。

真正真相：

```text
Call Server
```

因此：

```text
收到 PUSH
   ↓
检查 expiresAt
   ↓
必要时 GET call
   ↓
确认 RINGING
   ↓
展示
```

FCM 极快路径可以：

```text
先 Telecom
异步校验
```

但 OEM 通知点击恢复路径：

```text
建议先查询 Call
```

避免幽灵来电。

---

# 33. OEM 电池优化

这一层作为：

```text
Reliability Enhancement
```

而不是核心架构。

绝对不能设计：

```text
没有自启动权限
=
无法通话
```

否则说明 Push 架构本身就失败了。

厂商 System Push 的价值就是：

```text
App 不运行时仍然由系统维护 Push 通道
```

例如 MiPush 和 OPPO Push 都明确描述了这种系统级长连接机制。

---

# 34. 但仍然需要 OEM Reachability Diagnostics

增加：

```text
设置
└── 通话
    └── 来电可达性
```

显示：

```text
来电状态              良好

系统推送              ✓ Xiaomi Push
VoIP Push             ✓ 已启用
通知权限              ✓
来电通知 Channel      ✓
全屏来电              ✓
后台限制              正常
最近 Push             16:42:13
最近测试来电           成功
```

---

# 35. 对 OEM 设置不要过度自动化

可以设计：

```kotlin
interface OemSettingsNavigator
```

提供：

```text
打开通知设置
打开 App Details
打开电池设置
打开 Full Screen Intent 设置
```

但是：

> 不应依赖未经文档保证的厂商内部 Activity 名称作为核心功能。

因为：

```text
MIUI / HyperOS 更新
ColorOS 更新
OriginOS 更新
```

随时可能让 undocumented Intent 失效。

优先使用 Android 官方：

```text
ACTION_APPLICATION_DETAILS_SETTINGS
ACTION_APP_NOTIFICATION_SETTINGS
```

厂商特殊页面只作为：

```text
best effort
```

并需要真机测试。

---

# 36. 不要一启动就要求“无限制后台”

用户第一次打开：

```text
通知
电池
后台
自启动
悬浮窗
所有权限
```

一起要，是非常差的体验。

推荐：

### 第一次首次通话前

只要求：

```text
Notification
Camera
Microphone
```

### 检测到可达性问题

才提示：

```text
“为了避免错过咫尺来电，
建议允许后台来电。”
```

然后给：

```text
修复
```

---

# 37. Reachability Score

内部可以计算：

```text
A
B
C
D
```

例如：

### A

```text
Native VoIP Push ✓
Notification ✓
Call UI ✓
```

### B

```text
System Push ✓
Notification ✓
Process Wake uncertain
```

### C

```text
FCM/OEM Push token abnormal
```

### D

```text
Push unavailable
Notification disabled
```

注意：

这个等级是：

```text
诊断等级
```

不是：

```text
“保证 99.999%”
```

---

# 38. Background WebSocket

国产 ROM 也不要因此重新走回：

```text
永久 WebSocket
```

策略仍然：

```text
Foreground
    WebSocket ON

Ringing
    WebSocket ON

Call Active
    WebSocket ON

Background Idle
    WebSocket OFF
```

Push：

```text
负责找到 App
```

WebSocket：

```text
负责找到 Call Session
```

---

# 39. Push 到达后马上建立 WebSocket

一旦 App 确实被 VoIP Push 唤醒：

```text
Push
↓
IncomingCallCoordinator
├─ Telecom
└─ Signaling warm-up
```

可以并行：

```text
Telecom.addCall()

AND

connectSignaling()
```

但是：

```text
Camera
Microphone
WebRTC Media
```

仍然等：

```text
用户 Answer
```

之后再开始。

---

# 40. 来电取消必须多路径

Caller：

```text
Cancel
```

服务器：

```text
RINGING
→ CANCELLED
```

同时：

```text
WebSocket CallCancel
+
OEM Push CallCancel
```

支持 Recall 的 Provider：

```text
再执行 Notification Recall
```

目的：

```text
减少 OEM System Notification
已经显示后继续存在。
```

---

# 41. Client State Version

每次：

```text
Call State
```

携带：

```text
version
```

例如：

```text
INVITE
version = 1

CANCEL
version = 2

ANSWERED_ELSEWHERE
version = 3
```

客户端只接受：

```text
version > currentVersion
```

这样即使：

```text
CANCEL
先到

INVITE
后到
```

也不会重新响铃。

---

# 42. Push Payload Security

国产 Push 服务商都能够看到 Push envelope 的传输内容。

所以不要放：

```text
SDP
ICE Candidate
完整头像
联系人隐私数据
Access Token
长期 Credential
```

Push 仅：

```text
callId
callerDisplayName
mediaType
expiresAt
version
short-lived invite token
```

甚至以后可以进一步：

```text
Push 只携带 callId
```

真正数据：

```text
HTTPS 拉取。
```

---

# 43. Push SDK 隐私合规

China build 引入：

```text
MiPush
OPush
VivoPush
HonorPush
```

意味着：

```text
隐私政策
第三方 SDK 列表
数据处理说明
```

必须同步维护。

因此建立：

```text
docs/compliance/PUSH_SDK.md
```

记录：

```text
SDK
版本
厂商
用途
初始化时机
收集信息
隐私政策版本
升级日期
```

不要等上架审核时再补。

---

# 44. 真机测试矩阵

至少购买/长期保留：

```text
Pixel
Samsung

Xiaomi / Redmi
OPPO
vivo / iQOO
HONOR
```

Huawei：

```text
单独 HarmonyOS 测试设备。
```

---

# 45. 每台国产机必须测试

### Process

```text
前台
后台
最近任务划掉
LMK kill
```

### System

```text
锁屏
息屏
省电模式
超级省电
Doze
重启
```

### App Settings

```text
通知 ON/OFF
来电 Channel ON/OFF
横幅 ON/OFF
锁屏 ON/OFF
铃声 ON/OFF
后台限制
自启动 ON/OFF
```

### Distribution

```text
ADB Install

官网 APK

官方应用商店安装
```

---

# 46. 特别测试 Xiaomi

```text
MiPush 普通通知

MiPush VoIP Service Kit

VoIP capability false

VoIP capability true

Channel 审核前

Channel 审核后

VoIP 权限没有获批

VoIP 权限获批
```

以及：

```text
HyperOS
MIUI legacy
```

---

# 47. 特别测试 OPPO

```text
ColorOS < 13

ColorOS >= 13

新消息分类关闭

新消息分类开启

锁屏提醒

横幅

铃声

OPPO Store build

Sideload build
```

---

# 48. OEM Push Chaos Test

模拟：

```text
INVITE 延迟 10 秒

INVITE 重复 3 次

INVITE / CANCEL 乱序

CANCEL 丢失

Provider 返回失败

Token 过期

App 正在升级

设备刚刚重启

网络从 Wi-Fi → 5G
```

全部必须：

```text
不会重复来电
不会幽灵响铃
不会已经接听又弹来电
```

---

# 49. Telemetry 增强

新增：

```text
push_provider

push_provider_message_id

push_requested_at
provider_accepted_at
provider_delivered_at

client_received_at
call_ui_shown_at

manufacturer
model
os
rom_version

notification_enabled
full_screen_enabled

call_result
```

以后可以直接得到：

```text
Xiaomi
Push Receive Rate

OPPO
Call UI Show Rate

vivo
Answer Success Rate
```

---

# 50. OEM Dashboard

后台建立：

```text
Call Reachability Dashboard
```

例如：

```text
                   RECEIVE    UI SHOW    ANSWER

FCM                 99.x%       ...
Xiaomi VoIP         ...
OPPO Push           ...
vivo Push           ...
HONOR Push          ...
```

任何厂商：

```text
突然下降
```

就能发现：

```text
是不是 ROM 更新
是不是 SDK 更新
是不是厂商策略修改
```

这种基础设施对通话 App 非常重要。

---

# 51. 最终 Android 架构

Global：

```text
FCM HIGH
   ↓
FirebaseMessagingService
   ↓
IncomingCallCoordinator
   ↓
Android Telecom
   ↓
CallStyle
   ↓
Signaling
   ↓
WebRTC
```

Xiaomi China：

```text
MiPush VoIP
   ↓
onCallMessage
   ↓
IncomingCallCoordinator
   ↓
Android Telecom
   ↓
Signaling
   ↓
WebRTC
```

OPPO/vivo/HONOR：

```text
OEM System Push
   ↓
OEM Notification / callback
   ↓
IncomingCallCoordinator
   ↓
Server state validation
   ↓
Telecom
   ↓
Signaling
   ↓
WebRTC
```

HarmonyOS：

```text
HarmonyOS Push Kit
   ↓
VoIP Message
   ↓
CallServiceKit
   ↓
Zhichi Signaling
   ↓
RTC
```

---

# 52. 推荐实现优先级

不要一开始同时接五家。

## P0 — Global

```text
FCM
+
Core-Telecom
+
CallStyle
```

先建立真正稳定的：

```text
Process Dead → Incoming Call
```

---

## P1 — Xiaomi

第一家国产适配：

```text
MiPush
+
VoIP Service Kit
+
VoIP Channel
+
Receipt
```

原因：

```text
官方已经提供真正针对 VoIP 的能力
```

而且与咫尺场景非常匹配。

---

## P2 — OPPO

```text
OPush
+
私信/通话分类
+
锁屏/横幅/铃声适配
```

---

## P3 — vivo + HONOR

加入：

```text
VivoPushProvider
HonorPushProvider
```

---

## P4 — HarmonyOS

单独：

```text
harmony/
```

工程。

不是：

```text
android/
```

里面堆一堆：

```text
if Huawei
```

---

# 53. 工程结构

```text
android/app/src/main/java/.../call/

push/
├── PushProvider.kt
├── PushCapabilities.kt
├── PushProviderResolver.kt
│
├── fcm/
│   └── FcmPushProvider.kt
│
├── xiaomi/
│   ├── XiaomiPushProvider.kt
│   └── XiaomiCallMessageReceiver.kt
│
├── oppo/
│   └── OppoPushProvider.kt
│
├── vivo/
│   └── VivoPushProvider.kt
│
└── honor/
    └── HonorPushProvider.kt
```

公共：

```text
incoming/
├── IncomingCallCoordinator.kt
├── IncomingCallValidator.kt
└── IncomingCallActivity.kt

telecom/
├── TelecomController.kt
└── CoreTelecomController.kt

diagnostics/
├── ReachabilityDiagnostics.kt
├── ReachabilityGrade.kt
└── OemSettingsNavigator.kt
```

服务端：

```text
server/internal/push/

orchestrator.go

provider/
├── fcm.go
├── xiaomi.go
├── oppo.go
├── vivo.go
├── honor.go
└── harmony.go

routing/
├── resolver.go
└── capabilities.go
```

---

# 54. 最终原则

咫尺必须避免两个极端：

错误方案 A：

```text
Android 都用 FCM。
```

在中国大陆显然不够。

错误方案 B：

```text
为了国产 ROM，
自己搞各种黑科技保活。
```

这同样不可维护。

正确方案是：

```text
                    OS SYSTEM
                        │
      ┌─────────────────┼─────────────────┐
      │                 │                 │
     FCM           OEM PUSH         Harmony Push
      │                 │                 │
      └─────────────────┼─────────────────┘
                        ▼
             IncomingCallCoordinator
                        │
                 ┌──────┴───────┐
                 ▼              ▼
             Telecom        Signaling
                                │
                                ▼
                              WebRTC
```

也就是说：

> **国外信 Google 的系统通道。**

> **国内信手机厂商自己的系统通道。**

> **小米能走专用 VoIP Service Kit 就绝不退回普通通知。**

> **OPPO/vivo/HONOR 不假定具备 FCM data-only 等价能力，必须基于实际 Capability 设计。**

> **后台保活只是辅助优化，不是来电架构。**

> **华为新鸿蒙直接视为独立平台，而不是继续往 Android 兼容层里塞。**

最终要做到：

```text
咫尺可以死，
但是“有人正在找你”这条系统级通道不能死。
```
