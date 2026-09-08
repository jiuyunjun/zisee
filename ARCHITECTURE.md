# ARCHITECTURE.md

# Zisee 系统架构

## 1. 文档目的

本文档定义 Zisee 当前阶段的系统架构基线。

Zisee 是一个 Android 原生的实时视觉通信应用，核心目标不是单纯的视频通话，而是：

- 1v1 高清、低延迟实时通信
- P2P 优先
- P2P 不可用时 TURN 自动回退
- 前后摄像头同时传输
- 屏幕共享
- 多 Track 媒体能力
- 普通远程标注
- ARCore 现实空间锚点
- 弱网自适应
- 后续可扩展 AV1、SVC、AI 视频增强、多路径网络选择等能力

当前架构优先满足：

1. 可快速实现 MVP
2. 媒体链路可控
3. 便于调试
4. 便于后续做深度优化
5. 不被某个中心化 RTC 平台长期锁死

---

# 2. 产品定位

Zisee 的核心不是：

```text
普通视频聊天
```

而是：

```text
让远方的人看到你看到的东西，
并能够一起理解、指示和操作现实场景。
```

主要模式：

```text
Face Call
    ↓
Show Me
    ↓
Screen Share
    ↓
AR Assist
```

其中：

### Face Call

普通高清前摄视频通话。

### Show Me

后摄为主画面，前摄为辅助画面。

典型场景：

- 修东西
- 看商品
- 看房
- 旅行
- 远程指导
- 现场技术支持

### Screen Share

共享 Android 屏幕。

### AR Assist

远程用户对现场摄像头画面进行现实空间标注。

例如：

```text
“这个螺丝”
“这个插口”
“把这里拆掉”
“这里往左转”
```

标记应固定在现实空间中，而不是固定在屏幕像素位置。

---

# 3. 架构原则

## 3.1 P2P First

1v1 通话优先直接建立 WebRTC PeerConnection。

媒体路径优先级：

```text
Direct UDP P2P
    ↓
TURN UDP
    ↓
TURN TCP
    ↓
TURN TLS 443
```

第一版不默认让所有媒体流经过 SFU。

---

## 3.2 Control Plane 与 Media Plane 分离

### Control Plane

负责：

- 用户鉴权
- Signaling
- Call state
- SDP 交换
- ICE candidate 交换
- TURN credential
- Push notification
- Presence
- 会话元数据

### Media Plane

负责：

- Audio
- Front Camera
- Back Camera
- Screen Share
- DataChannel
- AR metadata

正常情况下 Media Plane 直接 P2P。

---

## 3.3 多 Track

不同媒体源使用独立 Track。

不要默认把多个媒体源合成为一个视频。

推荐逻辑模型：

```text
Participant
│
├── audio_microphone
│
├── video_front
│
├── video_back
│
├── video_screen
│
└── data
    ├── control
    ├── annotation
    ├── ar
    └── telemetry
```

---

## 3.4 能力检测优先于设备假设

以下能力必须运行时检测：

- Concurrent Camera
- 前后摄具体组合
- AV1 编码
- AV1 解码
- H.264 hardware codec
- ARCore
- Depth API
- HDR
- MediaProjection
- Camera resolutions
- Encoder resolutions
- Thermal state

禁止假设所有 Android 手机支持相同能力。

---

# 4. 总体系统架构

```text
                        ┌─────────────────────┐
                        │      Backend        │
                        │                     │
                        │ Auth                │
                        │ Signaling           │
                        │ Call State          │
                        │ TURN Credentials    │
                        │ Push                │
                        └──────────┬──────────┘
                                   │
                          HTTPS / WebSocket
                                   │
             ┌─────────────────────┴─────────────────────┐
             │                                           │
             │                                           │
        Android A                                   Android B
             │                                           │
             │             WebRTC P2P                    │
             └═══════════════════════════════════════════┘
                           preferred
                                   │
                              fallback
                                   │
                          ┌────────▼────────┐
                          │      TURN       │
                          │                 │
                          │ Cloudflare TURN │
                          │ future: coturn  │
                          └─────────────────┘
```

---

# 5. Android 客户端模块

推荐按功能拆分：

```text
app
│
├── core
│   ├── common
│   ├── logging
│   ├── permission
│   ├── device
│   └── network
│
├── auth
│
├── signaling
│
├── rtc
│   ├── peer
│   ├── ice
│   ├── codec
│   ├── stats
│   ├── audio
│   └── datachannel
│
├── media
│   ├── camera
│   │   ├── front
│   │   ├── back
│   │   └── concurrent
│   ├── screen
│   ├── renderer
│   └── processing
│
├── ar
│   ├── session
│   ├── pose
│   ├── depth
│   ├── anchor
│   ├── annotation
│   └── synchronization
│
├── call
│   ├── state
│   ├── controller
│   └── ui
│
└── telemetry
```

第一版不强制严格多 Gradle Module。

可以先单 module + package 分层。

当模块稳定后再拆 Gradle modules。

---

# 6. RTC 技术栈

## 6.1 底层

首选：

```text
WebRTC Native
```

原因：

- 原生支持 ICE
- STUN
- TURN
- SRTP
- DTLS
- RTP/RTCP
- NACK
- RTX
- Congestion Control
- DataChannel
- Hardware codec integration
- Android Camera/WebRTC renderer integration

---

## 6.2 为什么不默认 LiveKit

LiveKit 很适合：

- 快速原型
- 多人
- SFU
- Track abstraction
- Screen Share
- RPC
- 录制
- 服务端生态

但 Zisee 的核心架构目标是：

```text
1v1
+
P2P First
+
TURN Fallback
+
Media Path 可控
```

因此正式底座优先原生 WebRTC。

LiveKit 可以：

- 用于技术验证
- 用于早期 prototype
- 作为未来多人/SFU方案参考

但不作为当前基础架构的硬依赖。

---

# 7. Signaling

Signaling 不承载媒体。

建议：

```text
Client
    │
    ├── HTTPS
    │
    └── WebSocket
           │
           ▼
      Signaling Server
```

主要消息：

```text
CALL_INVITE
CALL_ACCEPT
CALL_REJECT

SDP_OFFER
SDP_ANSWER

ICE_CANDIDATE

ICE_RESTART

CALL_END

TRACK_STATE

DEVICE_CAPABILITY

TURN_CREDENTIAL

HEARTBEAT
```

推荐数据格式第一版使用 JSON。

稳定以后如果确有需要再考虑：

```text
Protocol Buffers
```

不要过早优化 signaling 消息大小。

---

# 8. Call State

通话必须使用显式状态机。

推荐：

```text
Idle

Incoming
Outgoing

Connecting

Connected

Reconnecting

Ending

Ended

Failed
```

示例：

```text
Idle
  ↓
Outgoing
  ↓
Connecting
  ↓
Connected
  ↓
Reconnecting
  ↓
Connected
  ↓
Ending
  ↓
Ended
```

避免大量 Boolean：

```text
isCalling
isConnecting
isConnected
isReconnecting
isEnding
```

组合造成非法状态。

---

# 9. ICE / STUN / TURN

## 9.1 默认策略

```text
ICE
│
├── host
├── srflx
└── relay
```

优先：

```text
P2P
```

失败后：

```text
TURN
```

---

## 9.2 TURN

早期建议：

```text
Cloudflare TURN
```

原因：

- 无需维护实例
- 非常适合个人/小规模使用
- TURN 仅 fallback
- 后续可以切换自建 coturn

长期可演进：

```text
Cloudflare TURN
        ↓
Hybrid
        ↓
Self-hosted TURN Cluster
```

---

## 9.3 TURN Credential

禁止：

```text
TURN username/password
hardcode in APK
```

推荐：

```text
Client
    ↓
Authenticated Backend
    ↓
Short-lived TURN Credential
```

credential 必须短期有效。

---

# 10. 网络切换

Android 用户可能发生：

```text
Wi-Fi
   ↓
5G
```

或：

```text
5G
   ↓
Wi-Fi
```

目标：

```text
network changed
    ↓
detect
    ↓
ICE restart
    ↓
new candidate pair
    ↓
resume media
```

不要求第一版做到完全无感。

但架构必须允许 ICE restart。

---

# 11. 多摄像头

## 11.1 CameraX

优先使用：

```text
CameraX
```

复杂能力必要时进入：

```text
Camera2
```

---

## 11.2 Concurrent Camera

支持设备上：

```text
Front Camera
+
Back Camera
```

同时采集。

必须查询设备 capability。

---

## 11.3 两路独立 Track

推荐：

```text
Front Camera
    ↓
video_front
    ↓
Encoder #1
    ↓
WebRTC

Back Camera
    ↓
video_back
    ↓
Encoder #2
    ↓
WebRTC
```

不要默认：

```text
Front
   \
    → GPU composite → single stream
   /
Back
```

因为独立 Track 更适合：

- 独立订阅
- 独立画质
- 主辅画面切换
- 动态带宽
- 未来更多 camera

---

# 12. Bandwidth Follows Attention

Show Me 模式中：

```text
Back Camera
1080p30
high bitrate

Front Camera
360p15
low bitrate
```

如果远端用户把前摄放大：

```text
Front
    ↓
becomes primary
```

则：

```text
Front
high quality

Back
low quality
```

该机制定义为：

```text
Bandwidth Follows Attention
```

第一版可以使用简单规则实现。

后期再使用更复杂策略。

---

# 13. 编码

第一阶段：

```text
H.264
```

主要原因：

- Android 兼容性好
- hardware codec 覆盖率高
- 调试成熟

后期增加：

```text
AV1
```

策略：

```text
Device Capability
    ↓
Codec Policy

AV1 hardware available
    → AV1

otherwise
    → H.264
```

禁止在不确认硬件编码能力时强制 AV1。

---

# 14. 编码策略

Media Policy Engine 输入：

```text
Network
+
Device
+
Content
+
User Attention
```

输出：

```text
codec
bitrate
resolution
fps
track priority
fec
```

第一版：

```text
rule based
```

后续：

```text
prediction based
```

---

# 15. Screen Share

Android 使用：

```text
MediaProjection
```

生成独立：

```text
video_screen
```

Screen Share 与 Camera 不应互斥。

理论上允许：

```text
Audio
+
Front Camera
+
Screen Share
```

或：

```text
Audio
+
Front Camera
+
Back Camera
+
Screen Share
```

但实际是否同时发送全部 Track 应根据：

- device load
- thermal
- bandwidth
- encoder count

动态限制。

---

# 16. DataChannel

WebRTC DataChannel 用于非媒体实时数据。

逻辑 channel 可以划分：

```text
control
annotation
ar
telemetry
```

不一定第一版真的建立多个 RTCDataChannel。

可以第一版使用：

```text
single DataChannel
+
message type
```

例如：

```json
{
  "type": "AR_CREATE_MARKER",
  "payload": {}
}
```

---

# 17. 普通远程标注

普通 2D Annotation：

```text
remote user touch
    ↓
normalized coordinate
    ↓
DataChannel
    ↓
remote overlay
```

坐标：

```text
0.0 ~ 1.0
```

不要直接传：

```text
pixel x/y
```

以适应不同屏幕比例。

---

# 18. AR Assist

## 18.1 运行角色

### Field Device

现场方。

运行：

```text
Camera
+
ARCore
+
Depth
+
Anchor
```

### Remote Device

指导方。

一般不运行 ARCore。

只：

```text
watch video
+
touch / annotate
```

---

# 19. AR 数据流

```text
Field Camera
    ↓
ARCore Frame
    ├── Camera Pose
    ├── Intrinsics
    ├── Depth
    └── Tracking State
    ↓
Video Encoder
    ↓
WebRTC
    ↓
Remote Device
```

远端：

```text
Remote video
    ↓
user touches point
    ↓
normalized x/y
+
video timestamp
    ↓
DataChannel
    ↓
Field Device
```

现场：

```text
timestamp
    ↓
Pose History
    ↓
Depth / Intrinsics
    ↓
2D → 3D
    ↓
Anchor
```

---

# 20. AR 时间同步

这是 Zisee AR Remote Assist 的关键问题。

远端看到：

```text
T = 10.000 sec
```

现场当前可能已经：

```text
T = 10.200 sec
```

因此禁止：

```text
remote touch
    ↓
current ARCore frame
    ↓
hit test
```

否则手机移动时位置会偏。

正确方案：

```text
Video Frame Timestamp
        ↓
Remote touch
        ↓
Timestamp returned
        ↓
Pose History lookup
        ↓
Pose(T)
Depth(T)
Intrinsics(T)
        ↓
2D → 3D
```

---

# 21. Pose History

现场端维护有限长度：

```text
PoseRingBuffer
```

建议初期保存：

```text
2 ~ 5 seconds
```

每条数据：

```text
timestamp
cameraPose
intrinsics
trackingState
optional depth reference
```

不要无限保存。

---

# 22. AR Anchor

第一版只需要：

```text
local session anchor
```

生命周期：

```text
AR Session
```

结束通话后无需保存。

后期：

```text
Persistent Anchor
```

可以研究：

- Cloud Anchor
- Geospatial Anchor
- Visual relocalization

---

# 23. AR Annotation 类型

目标支持：

```text
Pin
Circle
Arrow
Text
Line
Measure
```

MVP：

```text
Pin
Arrow
Circle
```

即可。

---

# 24. 渲染

Android 视频渲染优先：

```text
SurfaceViewRenderer / TextureView
```

AR 渲染：

```text
OpenGL ES
```

后续如果复杂 3D 内容增加，可考虑：

```text
Filament
```

不要第一版同时引入多个渲染引擎。

---

# 25. 音频

音频优先级高于视频。

弱网时：

```text
protect audio
    ↓
reduce video
```

优先保持：

- 语音不断
- 延迟低
- 回声抑制
- 降噪
- 自动增益

后续支持：

- Bluetooth headset
- speaker
- earpiece
- wired headset

---

# 26. 弱网策略

输入：

```text
RTT
packet loss
jitter
estimated bitrate
send bitrate
receive bitrate
encoder fps
thermal
```

简单策略：

```text
good
    → 1080p30

medium
    → 720p30

poor
    → 540p20

very poor
    → 360p15
```

实际参数后续根据测试调整。

---

# 27. Reliability

重点关注：

```text
ICE disconnected
ICE failed
network changed
camera unavailable
encoder error
decoder error
AR tracking lost
screen capture stopped
app background
remote app killed
```

每种情况都必须明确：

```text
retry
fallback
notify
end call
```

不能无限卡死。

---

# 28. Telemetry

RTC 是高度依赖可观测性的系统。

每次通话建议记录：

```text
callId

connectionType
P2P / TURN

candidateType
host / srflx / relay

protocol
UDP / TCP / TLS

codec

frontCameraEnabled
backCameraEnabled
screenShareEnabled
arEnabled

resolution
fps
bitrate

RTT
jitter
packetLoss

ICE reconnect count

network switch count

thermal state
```

注意：

不要记录实际音视频内容。

---

# 29. 隐私

原则：

```text
Media privacy first
```

默认：

```text
P2P encrypted
```

TURN：

```text
relay encrypted packets
```

TURN 不应解码媒体。

服务端不保存媒体。

未来如果增加：

- recording
- AI server processing
- transcription

必须显式设计独立权限和隐私策略。

---

# 30. Backend

第一版推荐：

```text
Go
```

负责：

```text
REST API
WebSocket signaling
TURN credential
Push integration
Auth
```

部署可以非常轻量。

例如：

```text
Cloud Run
```

或者：

```text
small VPS
```

第一版不要引入复杂微服务。

推荐：

```text
single backend service
```

---

# 31. 数据库

早期数据库只存：

```text
User
Device
Call metadata
Push token
Optional contacts
```

不存：

```text
media stream
AR frame
Depth data
camera frames
```

第一阶段推荐轻量关系数据库。

---

# 32. Push

Android incoming call 需要：

```text
FCM
```

典型：

```text
Caller
    ↓
Backend
    ↓
FCM
    ↓
Callee
    ↓
Signaling connect
```

Push 不是 signaling transport 本身。

---

# 33. Security

必须：

- HTTPS
- WSS
- DTLS-SRTP
- 短期 TURN credential
- Server-side authentication
- Token expiration
- 权限最小化

禁止：

```text
hardcoded secret
```

---

# 34. MVP 范围

## Phase 1

实现：

```text
Android Native App

1v1 call
Microphone
Front camera

WebRTC P2P

STUN

TURN fallback

Basic signaling

Basic call state

Basic RTC stats
```

目标：

```text
稳定打通
```

---

## Phase 2

实现：

```text
Back Camera

Front / Back switching

Concurrent Camera

Dual Video Tracks

Show Me mode

Bandwidth Follows Attention
```

目标：

```text
形成明显差异化
```

---

## Phase 3

实现：

```text
Screen Share

Basic 2D Annotation

Remote pointer
```

---

## Phase 4

实现：

```text
ARCore

Remote touch

Hit Test

Spatial Anchor

Pin / Arrow
```

---

## Phase 5

实现：

```text
Video timestamp
Pose history

Depth

Timestamp alignment

High accuracy remote AR annotation
```

---

# 35. 后续增强

后续可以逐步研究：

```text
AV1

SVC

AI Denoise

Low Light Enhancement

ROI Encoding

Super Resolution

Object Detection

Object Tracking

Semantic Segmentation

Persistent AR Anchors

AR Measurement

Multi-path Network Probing

Predictive Bandwidth Control

Adaptive FEC

USB Camera

External Camera

3+ participants

SFU
```

这些均不是 MVP 阻塞项。

---

# 36. 第一版明确不做

避免 scope explosion。

第一版不做：

- 自研视频 codec
- 自研 RTP
- 自研 NAT traversal protocol
- 自研 TURN
- 自研 SLAM
- 自研 SFU
- 4K
- 120fps
- 复杂 AI enhancement
- 多人会议
- 云端录制
- Persistent AR map
- Cloud Anchor
- Remote Android control
- 完整 Desktop Client

---

# 37. 技术决策记录

重要架构变化建议使用 ADR：

```text
docs/adr/
```

例如：

```text
0001-use-native-webrtc.md
0002-p2p-first.md
0003-use-camerax.md
0004-cloudflare-turn.md
0005-multi-track-camera.md
```

每个 ADR 应包含：

```text
Context
Decision
Alternatives
Consequences
```

---

# 38. 当前推荐技术栈

Android：

```text
Kotlin
Jetpack Compose
Coroutines
Flow
CameraX
WebRTC Native
ARCore
MediaProjection
MediaCodec
OpenGL ES
```

Backend：

```text
Go
HTTPS
WebSocket
FCM
```

Infrastructure：

```text
P2P
STUN
Cloudflare TURN
```

Future：

```text
coturn
SFU
AV1
SVC
AI
```

---

# 39. 核心数据路径

## 普通通话

```text
Camera
    ↓
WebRTC Encoder
    ↓
SRTP
    ↓
P2P / TURN
    ↓
Decoder
    ↓
Renderer
```

---

## 双摄

```text
Front Camera
    ↓
Encoder A
    ↓
Track A
        \
         \
          WebRTC
         /
        /
Back Camera
    ↓
Encoder B
    ↓
Track B
```

---

## 屏幕共享

```text
MediaProjection
    ↓
Screen Capturer
    ↓
Video Track
    ↓
WebRTC
```

---

## AR Remote Annotation

```text
Remote
Video Frame
    ↓
Touch
    ↓
x/y + timestamp
    ↓
DataChannel
    ↓
Field Device
    ↓
Pose(T)
Depth(T)
Intrinsics(T)
    ↓
3D Point
    ↓
Anchor
    ↓
AR Renderer
```

---

# 40. 核心工程指标

未来开发过程中应关注：

### RTC

```text
Call Setup Time
P2P Success Rate
TURN Rate
Reconnection Time
RTT
Packet Loss
Jitter
```

### Video

```text
Encode FPS
Decode FPS
Resolution
Bitrate
Frame Drop
Encode Time
```

### Device

```text
CPU
Memory
Thermal
Battery
```

### AR

```text
Tracking Quality
Anchor Error
Timestamp Alignment Error
Pose Lookup Error
```

---

# 41. 成功标准

Zisee 不以：

```text
支持 4K
功能最多
```

作为首要成功标准。

优先级：

```text
1. 接得通
2. 不容易断
3. 声音稳定
4. 视频低延迟
5. 主画面清楚
6. 网络变化能恢复
7. 双摄真正实用
8. AR 标记真正稳定
9. 功耗可接受
10. 再追求更高画质
```

---

# 42. 核心架构结论

当前架构基线：

```text
Android Native
+
Kotlin
+
WebRTC Native
+
P2P First
+
Cloudflare TURN Fallback
+
CameraX Concurrent Camera
+
Multi Video Tracks
+
MediaProjection
+
ARCore
+
DataChannel
+
Go Signaling Backend
```

这是当前项目实现阶段的默认架构。

如果后续需求变化，尤其出现：

```text
多人
录制
大规模分发
服务端媒体处理
```

再重新评估 SFU 架构。

不要为了未来可能存在的需求提前把 1v1 产品复杂化。
