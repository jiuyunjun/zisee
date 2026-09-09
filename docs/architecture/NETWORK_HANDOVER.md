# WebRTC 网络切换低卡顿专项设计

## 1. 背景

本项目为 Android WebRTC 高清视频通话应用。

需要重点解决通话过程中网络发生切换时的连续性问题，例如：

* Wi-Fi → 5G / 4G
* 5G / 4G → Wi-Fi
* Wi-Fi A → Wi-Fi B
* Wi-Fi 突然断开后蜂窝网络接管
* 蜂窝网络信号短暂丢失后恢复
* P2P 路径失效后切换 TURN
* TURN 临时承载后重新恢复 P2P

目标不是保证网络切换绝对零丢包，而是：

> 网络发生变化时保持当前通话 Session 和 `RTCPeerConnection`，尽可能快速建立新媒体路径，将用户可感知的音视频中断压缩到最低。

---

# 2. 设计目标

## 2.1 核心目标

网络切换时：

1. 不主动结束当前通话。
2. 默认不销毁 `RTCPeerConnection`。
3. 不重新创建整个 Call Session。
4. 尽早感知 Android 网络变化。
5. 尽早发现新网络对应的 ICE Candidate。
6. 优先恢复任何可用路径。
7. P2P 无法快速恢复时允许 TURN 接管。
8. 条件允许时重新恢复 P2P。
9. 网络恢复后尽快恢复视频关键帧。
10. Signaling 层必须支持 ICE Restart。

---

# 3. 用户体验目标

理想情况：

```text
Wi-Fi P2P
    ↓
网络变化
    ↓
短暂抖动
    ↓
5G P2P / TURN
    ↓
继续通话
```

用户不应该看到：

```text
网络变化
↓
通话退出
↓
重新呼叫
↓
重新进入通话
```

也应该尽量避免：

```text
Wi-Fi 断开
↓
等待数秒 ICE timeout
↓
ICE failed
↓
重新建立 PeerConnection
↓
黑屏数秒
```

---

# 4. 性能指标

第一阶段目标：

| 指标             |                   目标 |
| -------------- | -------------------: |
| 网络变化检测         |             < 500 ms |
| 音频明显中断         |          尽量 < 500 ms |
| 视频明显中断         |             尽量 < 1 s |
| 通话 Session     |                  不重建 |
| PeerConnection |               原则上不重建 |
| 网络切换成功率        | > 99%（存在至少一条可用网络路径时） |

这些是项目工程目标，不应被实现为 WebRTC 本身能够严格保证的 SLA。

后续通过真机测试数据调整。

---

# 5. 总体架构

```text
┌───────────────────────────────┐
│          Call Session         │
│                               │
│   ┌───────────────────────┐   │
│   │   RTCPeerConnection   │   │
│   └───────────┬───────────┘   │
│               │               │
│   ┌───────────▼───────────┐   │
│   │ ICE / DTLS / SRTP     │   │
│   └───────────┬───────────┘   │
└───────────────┼───────────────┘
                │
        ┌───────┴────────┐
        │                │
       P2P              TURN
        │                │
        └───────┬────────┘
                │
             Internet
```

另外 Android 层增加：

```text
ConnectivityManager
        │
        ▼
NetworkCallback
        │
        ▼
NetworkMonitor
        │
        ▼
WebRTC Connection Manager
        │
        ├── Candidate Gathering
        ├── ICE State Monitoring
        ├── ICE Restart
        └── Recovery Logic
```

---

# 6. 网络切换核心原则

## 6.1 保持 PeerConnection

网络变化时禁止默认执行：

```text
peerConnection.close()
↓
new PeerConnection()
```

优先：

```text
Existing PeerConnection
        ↓
Network Changed
        ↓
ICE Path Recovery
        ↓
ICE Restart（必要时）
        ↓
Continue Session
```

只有 PeerConnection 已进入无法恢复的异常状态时才允许完整重建。

完整重建属于最终兜底方案。

---

# 7. Android 网络监控

使用：

```kotlin
ConnectivityManager.NetworkCallback
```

监听至少：

```text
onAvailable()
onLost()
onLosing()
onCapabilitiesChanged()
onLinkPropertiesChanged()
```

需要记录：

```text
Network
Transport Type
Validated
Internet Capability
Metered
Default Network
Timestamp
```

Transport 至少区分：

```text
WIFI
CELLULAR
ETHERNET
VPN
OTHER
```

---

# 8. 不直接根据单个 Callback Restart ICE

Android 网络切换可能产生：

```text
CELLULAR available
WIFI losing
default network changed
capabilities changed
WIFI lost
```

这些可能发生在非常短的时间内。

禁止：

```text
每收到一次 callback
    ↓
restartIce()
```

否则可能造成：

* 重复 ICE Restart
* Signaling 风暴
* SDP 状态冲突
* Candidate 混乱
* 不必要的媒体中断

应该设计：

```text
NetworkCallback
      ↓
Network Event Queue
      ↓
Debounce / Coalesce
      ↓
Network Snapshot
      ↓
Recovery Decision
```

初始 debounce 建议：

```text
100 ~ 300 ms
```

具体数值必须通过真机测试调整。

---

# 9. Network Snapshot

不要只保存：

```text
currentNetwork = WIFI
```

应该保存当前所有可用网络，例如：

```text
NetworkSnapshot {
    defaultNetwork
    wifiNetwork
    cellularNetwork

    wifiValidated
    cellularValidated

    timestamp
}
```

这样可以识别：

```text
Wi-Fi 当前仍工作
+
5G 已经出现
```

这对于 make-before-break 优化非常重要。

---

# 10. ICE Candidate 策略

WebRTC 建立连接时通常存在：

```text
host candidate
srflx candidate
relay candidate
```

分别代表：

```text
host
→ 本地网络地址

srflx
→ STUN 获取的 NAT 公网映射

relay
→ TURN 中继地址
```

TURN 必须从通话建立阶段就配置。

不要等 P2P 失败后才临时配置 TURN。

即：

```text
PeerConnection Configuration

ICE Servers:
    STUN
    TURN UDP
    TURN TCP/TLS（视部署情况）
```

正常情况下仍优先：

```text
P2P
```

TURN 只是始终处于可使用状态。

---

# 11. Continual ICE Gathering

如果当前 Android WebRTC SDK/版本支持，应启用持续 Candidate 收集能力。

目标：

```text
开始通话
↓
收集 Wi-Fi candidates
↓
建立 Wi-Fi P2P
↓
5G 网络出现
↓
发现新的 candidates
↓
建立新的可用路径
```

而不是：

```text
通话开始时收集一次
↓
以后永远使用旧 candidates
```

具体 API 必须根据项目实际使用的 WebRTC Android 版本确认，不允许 Agent 根据过时 API 名称硬编码。

---

# 12. Wi-Fi → 5G 标准流程

正常状态：

```text
A ←──── Wi-Fi P2P ────→ B
```

Android 检测：

```text
CELLULAR AVAILABLE
```

此时：

```text
不要关闭 Wi-Fi 路径
不要关闭 PeerConnection
```

更新 Network Snapshot。

随后如果：

```text
WIFI LOST
```

当前路径开始失效：

```text
Wi-Fi P2P
    X
```

系统进入：

```text
RECOVERING
```

尝试：

```text
已有 Candidate Path
        ↓
新网络 Candidate
        ↓
ICE Connectivity Check
```

如果可以：

```text
5G P2P
```

立即恢复。

如果 P2P 无法快速建立：

```text
5G
 ↓
TURN
 ↓
Peer
```

优先恢复媒体。

---

# 13. TURN 作为安全垫

TURN 不应该只被理解为：

> 永久 P2P 失败之后才使用。

在网络切换场景中，TURN 可以承担临时恢复路径。

例如：

```text
Wi-Fi P2P
    ↓
Wi-Fi Lost
    ↓
5G 网络出现
    ↓
5G P2P 尚未成功
    ↓
TURN 可用
    ↓
5G → TURN → Peer
    ↓
媒体恢复
```

后续 ICE 如果发现：

```text
5G P2P available
```

则允许 ICE 根据实际 Candidate Pair/策略迁移到更优路径。

注意：

> 不要自行假设所有 WebRTC 实现都会自动从 TURN 切回 P2P。

必须通过当前 SDK 行为验证。

---

# 14. ICE Restart

如果现有 ICE 无法通过新网络恢复，则执行 ICE Restart。

逻辑：

```text
Network Changed
      ↓
Existing ICE Path Working?
      │
   YES│
      └──── 不 Restart
      │
     NO
      ↓
短暂 Recovery Window
      ↓
仍然无法恢复
      ↓
ICE Restart
```

ICE Restart 必须通过 Signaling Server 与远端完成新的 SDP 协商。

概念流程：

```text
A
↓
restart ICE
↓
createOffer(ICE restart)
↓
new SDP Offer
↓
Signaling Server
↓
B
↓
setRemoteDescription
↓
createAnswer
↓
Signaling Server
↓
A
↓
setRemoteDescription
↓
新的 ICE Connectivity Check
```

Signaling Server 必须支持同一个 Call Session 内多轮 Offer/Answer。

---

# 15. Signaling 状态保护

网络切换可能恰好发生在：

```text
Offer
Answer
Renegotiation
ICE Restart
Track Change
Screen Share
Camera Switch
```

因此必须避免 SDP 冲突。

需要实现统一的：

```text
Negotiation Manager
```

负责串行处理：

```text
normal renegotiation
ICE restart
media changes
```

禁止多个模块独立调用：

```text
createOffer()
setLocalDescription()
```

否则容易出现：

```text
signalingState != stable
```

等竞争问题。

---

# 16. Recovery State Machine

建议增加明确状态机：

```text
CONNECTED
    │
    │ network change
    ▼
NETWORK_CHANGING
    │
    ▼
RECOVERING
    │
    ├── existing ICE recovered
    │       ↓
    │    CONNECTED
    │
    ├── TURN recovered
    │       ↓
    │    CONNECTED
    │
    └── timeout
            ↓
       ICE_RESTARTING
            │
       ┌────┴─────┐
       │          │
    success     failure
       │          │
CONNECTED     REBUILDING
                  │
               success
                  │
              CONNECTED
```

不要把：

```text
DISCONNECTED
FAILED
CLOSED
```

全部当成相同情况。

---

# 17. ICE 状态处理

重点监听：

```text
PeerConnection.IceConnectionState
PeerConnection.PeerConnectionState
```

例如：

```text
CONNECTED
COMPLETED
DISCONNECTED
FAILED
CLOSED
```

特别注意：

```text
DISCONNECTED
```

不等于：

```text
FAILED
```

短暂网络切换出现 `DISCONNECTED` 是正常现象。

禁止：

```text
if DISCONNECTED:
    closeCall()
```

应该：

```text
DISCONNECTED
↓
进入 Recovery Window
↓
观察是否恢复
↓
必要时 ICE Restart
```

---

# 18. Recovery Timer

建议实现可配置 Timer，例如：

```text
T0
Network change detected

T0 + 0~300ms
合并 Android 网络事件

T0 + ~300ms
检查 PeerConnection 状态

短时间内
尝试自然 ICE 恢复

仍失败
↓
ICE Restart

ICE Restart 仍失败
↓
最终 PeerConnection Rebuild
```

具体超时值不要写死在业务代码中。

统一放：

```text
WebRtcRecoveryConfig
```

方便真机调优。

---

# 19. 音频优先恢复

网络切换过程中：

```text
Audio > Video
```

用户对短暂视频冻结通常比声音中断更能接受。

因此恢复策略应该优先保证：

```text
Audio RTP
```

快速恢复。

如果带宽突然下降：

```text
Wi-Fi
100 Mbps
↓
5G / 4G
5 Mbps
```

不能继续强行发送：

```text
1080p
10 Mbps
```

否则恢复后仍然会持续拥塞。

依赖 WebRTC Congestion Control，同时应用层应允许快速降低：

```text
bitrate
resolution
framerate
```

---

# 20. 视频恢复

网络切换期间可能丢失视频参考帧。

即使网络已经恢复：

```text
RTP resumed
```

解码器也可能暂时：

```text
黑屏
花屏
冻结
```

因此恢复后需要确保接收端能够尽快请求新的关键帧。

通常通过 WebRTC 自身：

```text
RTCP PLI
```

等机制完成。

如果 SDK 提供合适的显式 Keyframe 请求能力，可考虑使用。

但不要为了实现此功能破坏 WebRTC 自身的拥塞控制和反馈机制。

---

# 21. Make-Before-Break

高级优化目标：

```text
Wi-Fi 仍然存在
+
5G 已经 available
```

时尽可能：

```text
旧路还没断
↓
新路已经准备
↓
切换
↓
旧路消失
```

即：

```text
Make Before Break
```

而不是：

```text
Break
↓
发现断网
↓
寻找新网络
↓
重新建立路径
```

后者天然会产生更长中断。

---

# 22. Android 双网络高级优化

后续版本可以研究：

```text
ConnectivityManager.requestNetwork()
```

主动保持：

```text
Wi-Fi
+
Cellular
```

短时间同时可用。

但必须注意：

* Android 网络绑定行为
* WebRTC native network monitor 行为
* Socket 与 Network 的绑定
* VPN
* OEM ROM 差异
* 电池消耗
* 蜂窝流量消耗
* Android 后台限制

因此该能力不进入第一阶段 MVP。

---

# 23. 不要强行 processBindProcessToNetwork

除非经过充分验证，不要简单：

```kotlin
bindProcessToNetwork(cellular)
```

因为这可能导致：

* Signaling WebSocket 路径变化
* DNS 行为变化
* TURN 连接变化
* App 内其他 HTTP 请求全部切网
* WebRTC Socket 行为异常

如果未来需要指定 WebRTC 使用某个 Network，应设计专门的网络层，而不是粗暴改变整个 Process 默认网络。

---

# 24. Signaling Server 断线恢复

Wi-Fi → 5G 不仅影响媒体 P2P，也可能影响：

```text
WebSocket Signaling
```

因此 Signaling 层必须支持：

```text
WebSocket disconnected
↓
new network available
↓
reconnect
↓
恢复 Call Session
```

必须存在稳定的：

```text
callId
peerId
sessionId
```

重新连接后不能把它识别成一个全新的通话。

---

# 25. Signaling 与 Media 分离

必须明确：

```text
Signaling disconnected
```

不代表：

```text
Media disconnected
```

例如：

```text
P2P SRTP 正常
+
WebSocket 临时断开
```

此时不能结束通话。

同理：

```text
Media path broken
```

也不代表用户账号或 Call Session 已失效。

两个状态必须独立管理。

---

# 26. 建议模块划分

建议：

```text
webrtc/
├── WebRtcClient
├── PeerConnectionManager
├── IceManager
├── NegotiationManager
├── NetworkMonitor
├── NetworkSnapshot
├── ConnectionRecoveryManager
├── RecoveryStateMachine
├── WebRtcRecoveryConfig
└── WebRtcStatsCollector
```

职责：

### NetworkMonitor

负责：

```text
Android ConnectivityManager
↓
标准化 NetworkEvent
```

### IceManager

负责：

```text
ICE candidate
ICE state
ICE restart
```

### NegotiationManager

负责：

```text
Offer / Answer
Renegotiation
ICE Restart SDP
```

保证协商串行。

### ConnectionRecoveryManager

负责：

```text
Network Event
+
ICE State
+
PeerConnection State
↓
决定是否恢复 / Restart / Rebuild
```

### WebRtcStatsCollector

负责收集实际网络切换性能。

---

# 27. 日志

每次网络切换必须能够完整追踪。

例如：

```text
10:00:00.000 NETWORK_CELLULAR_AVAILABLE
10:00:00.080 NETWORK_DEFAULT_CHANGED WIFI -> CELLULAR
10:00:00.120 ICE_DISCONNECTED
10:00:00.260 NEW_CANDIDATE srflx
10:00:00.340 CANDIDATE_PAIR_CHANGED
10:00:00.410 ICE_CONNECTED
10:00:00.450 AUDIO_RESUMED
10:00:00.620 VIDEO_RESUMED
```

禁止只记录：

```text
network changed
```

这种无法分析的日志。

---

# 28. Stats

通过 `getStats()` 记录至少：

```text
candidate-pair
local-candidate
remote-candidate
availableOutgoingBitrate
currentRoundTripTime
packetsLost
jitter
bytesSent
bytesReceived
framesPerSecond
framesDropped
```

重点记录 Candidate Type：

```text
host
srflx
relay
```

以及：

```text
networkType
protocol
```

用于判断实际发生的是：

```text
Wi-Fi P2P
→
5G P2P
```

还是：

```text
Wi-Fi P2P
→
5G TURN
```

---

# 29. 网络切换专项 Metrics

定义：

```text
networkChangeDetectedAt

iceDisconnectedAt

iceRecoveredAt

audioLastPacketBeforeSwitch
audioFirstPacketAfterSwitch

videoLastFrameBeforeSwitch
videoFirstFrameAfterSwitch
```

计算：

```text
Network Recovery Time

Audio Interruption Time

Video Interruption Time
```

这样后续优化才有数据依据。

---

# 30. 必测场景

至少覆盖：

### Case 1

```text
Wi-Fi P2P
→
关闭 Wi-Fi
→
5G
```

### Case 2

```text
5G P2P
→
连接 Wi-Fi
```

### Case 3

```text
Wi-Fi P2P
→
Wi-Fi 断开
→
5G TURN
```

### Case 4

```text
Wi-Fi TURN
→
5G TURN
```

### Case 5

```text
Wi-Fi A
→
Wi-Fi B
```

### Case 6

```text
5G
→
飞行模式
→
5G恢复
```

### Case 7

```text
Wi-Fi
→
进入无信号区域
→
短暂 DISCONNECTED
→
恢复
```

### Case 8

网络切换同时：

```text
前后摄像头切换
```

### Case 9

网络切换同时：

```text
开启/关闭屏幕共享
```

### Case 10

网络切换同时：

```text
Signaling WebSocket 断线
```

---

# 31. 真机测试

该功能不能只依赖 Android Emulator 验证。

必须至少使用两台真机测试：

```text
Device A
Wi-Fi + 5G

Device B
不同网络
```

重点测试：

```text
家庭 Wi-Fi
公司 Wi-Fi
手机热点
docomo
au
SoftBank
Rakuten
```

条件允许时覆盖不同运营商，因为：

```text
NAT
CGNAT
IPv4
IPv6
Firewall
```

行为可能不同。

---

# 32. 第一阶段 MVP

第一阶段不要直接实现复杂 Multipath。

实现：

```text
ConnectivityManager.NetworkCallback
+
Network Snapshot
+
TURN 从建立连接开始配置
+
正确处理 ICE DISCONNECTED
+
Recovery State Machine
+
ICE Restart
+
Signaling reconnect
+
Stats / Logging
```

目标：

> 网络切换不会导致 Call Session 被错误结束，并且能够自动恢复。

---

# 33. 第二阶段

增加：

```text
Continual ICE Gathering
+
Candidate Pair Change Monitoring
+
更智能 ICE Restart 时机
+
快速 TURN fallback
+
恢复后 Keyframe 优化
+
Bitrate 快速收敛
```

目标：

> 将用户明显感知的视频中断控制在约 1 秒以内。

---

# 34. 第三阶段

研究：

```text
Wi-Fi + Cellular 同时保持
+
Make-Before-Break
+
Android Network Binding
+
Multipath / Path Migration
```

目标：

```text
Wi-Fi
───────────────┐
               ├── handover
5G        ─────┘
```

尽可能接近：

> 用户感觉不到网络切换。

---

# 35. 禁止事项

Agent 实现时禁止：

```text
Network changed
→
直接 close PeerConnection
```

禁止：

```text
ICE DISCONNECTED
→
直接结束通话
```

禁止：

```text
每个 NetworkCallback
→
restartIce()
```

禁止：

```text
P2P 失败后
→
才开始配置 TURN Server
```

禁止：

```text
多个模块同时 createOffer()
```

禁止：

```text
为了切 5G
→
直接 bind 整个 Process 到 Cellular
```

禁止：

```text
没有 getStats / 时间戳日志
→
凭主观感觉判断优化效果
```

---

# 36. Agent 实现要求

实现前：

1. 阅读当前项目 `AGENTS.md`。
2. 阅读项目 `ARCHITECTURE.md`。
3. 阅读项目 `PRODUCT.md`。
4. 阅读现有 WebRTC 相关代码。
5. 确认当前 WebRTC Android SDK 版本。
6. 确认当前 SDK 是否支持 continual gathering 及对应 API。
7. 确认现有 Signaling 是否支持同一 Session 多轮 SDP。
8. 不要根据网络博客中的旧 WebRTC API 直接实现。

实现过程中：

```text
小步修改
↓
编译
↓
测试
↓
记录
↓
commit
```

每次有意义修改都必须 commit。

所有文本文件：

```text
UTF-8
```

---

# 37. 验收标准

第一阶段完成必须满足：

* [ ] Wi-Fi → 5G 不退出通话
* [ ] 5G → Wi-Fi 不退出通话
* [ ] ICE `DISCONNECTED` 不会立即结束 Call
* [ ] 可以在原 PeerConnection 上完成恢复
* [ ] 必要时可以执行 ICE Restart
* [ ] ICE Restart 可以完成 Signaling Offer/Answer
* [ ] TURN 可以作为恢复路径
* [ ] Signaling WebSocket 可以跨网络恢复
* [ ] Signaling 断开不会错误结束仍正常工作的 P2P Media
* [ ] NetworkCallback 不会造成重复 ICE Restart
* [ ] SDP Negotiation 不存在并发 `createOffer`
* [ ] 能记录切换前后的 Candidate Pair
* [ ] 能识别当前媒体走 P2P 还是 TURN
* [ ] 能计算音频中断时间
* [ ] 能计算视频中断时间
* [ ] 至少两台 Android 真机通过专项测试

---

# 38. 最终设计原则

整个功能遵循：

```text
Detect Early
    ↓
Keep Session
    ↓
Keep PeerConnection
    ↓
Recover Existing ICE
    ↓
TURN if Needed
    ↓
ICE Restart if Needed
    ↓
Rebuild Only as Last Resort
```

即：

> **尽早发现网络变化，尽量保住当前连接；有路先恢复媒体，P2P 能回来再优化路径，完整重建永远作为最后手段。**

最终目标不是追求理论上的“绝对零中断”，而是让 Wi-Fi、5G、TURN、P2P 的变化尽量成为 WebRTC 内部的路径变化，而不是用户感知到的一次“掉线重连”。

---

# 39. 实施记录（2026-09-09）

本次落实已有恢复链路的策略修正，不代表第一阶段所有验收项已经完成。

## 已有基础

- 实际依赖为 `io.github.webrtc-sdk:android:144.7559.15`。通过本地 AAR 的 `javap` 核对支持 `GATHER_CONTINUALLY`、`continualGatheringPolicy`、`restartIce()` 和 `getStats()`；当前会话已启用持续收集。
- `NativeRtcSession` 在建立连接时配置 STUN/TURN，ICE Restart 保留原 PeerConnection。
- `MediaNegotiator` 是当前唯一 SDP 协商入口；服务端通过 `media.restart` 和 generation 支持原 callId 内多轮 SDP，原主叫始终负责 offer，避免双方同时发起 offer。

## 本次修改

- 网络通知经过 200 ms 合并后重新连接信令，减少连续回调反复取消 WebSocket。网络通知本身不再把仍正常工作的媒体标记为断线。
- `WebRtcRecoveryConfig` 集中恢复窗口、冷却、重试预算和统计采样参数。
- 已完成协商且 ICE 为 CONNECTED 时，提供 750 ms 自然恢复窗口；必须看到检测时间之后的两次统计中接收 RTP 字节增长，才取消本次 ICE Restart。旧 CONNECTED 状态、旧采样和计数器回退均不能单独证明恢复。
- ICE CHECKING/DISCONNECTED 等路径继续采用 250 ms 路由等待及既有有界重试。没有网络事件的短暂 DISCONNECTED 保留 1,500 ms 宽限期。
- 网络事件唤醒统计采样，随后 5 秒内按 200 ms 间隔采集，平时仍为 1 秒。恢复决策等待期间信令循环按 100 ms 检查；HTTP/WebSocket 往返仍会影响实际决策延迟。
- 同类型 candidate pair 的变化现在也会记录；日志附带受限枚举的网络类型、协议和单调时钟。`RTC_ROUTE_RECOVERED durationMs` 表示应用检测至观察到收包恢复的时长，**不等同于音频中断或视频冻结时长**。不记录 candidate 地址、SDP 或 credential。

## 验证与边界

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 通过。新增自然恢复、旧样本、计数器重置、连续切网、DISCONNECTED 不被收包掩盖及同类型候选对变化回归覆盖。
- 修正现有信令切网测试的本地服务器 URL，显式使用 `127.0.0.1`，避免 Windows 主机名解析导致测试在进入信令流程前失败。
- 当前 ADB 无设备连接，尚未执行两台真机专项验收，不能宣称音频 <500 ms、视频 <1 s 或 >99% 成功率。
- 全量 Network Snapshot、完整恢复状态机、精确音频包/视频帧间断指标和最终 PeerConnection rebuild 仍待实现；当前默认网络监听及既有通话状态机继续工作。
- 现有信令恢复仍有 30 秒已建立通话重试预算，超时会终止通话；尚未实现健康媒体下无限保留会话。TURN→P2P 自动回迁尚待当前 SDK 真机验证。

## 2026-09-09 切网等待修正

- 网络回调立即记录恢复窗口起点并唤醒循环；WSS 重连及 call.sync 的耗时计入窗口，避免恢复信令后再等一次 750 ms。
- 新 ICE 候选到达即唤醒交换，包括蜂窝和晚到的 TURN 候选。媒体 restart 已请求但尚未被服务器代次确认时，不再将旧 SDP 的完成状态视作新协商完成；代次恢复期间保持 100 ms 轮询。
- 原生 receiving timeout 为 1000 ms，稳定连接和备用候选探测间隔为 500 ms；失去写入响应至少 1500 ms 且达到 3 次检查才标记 unwritable。缩短旧 Wi-Fi 路径阻挡已验证备用路径的时间。参数对应 [当前 WebRTC RTCConfiguration](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java)。
- 仍使用 ALL、持续候选收集与蜂窝待机，TURN 从建立通话起参与 ICE。没有强制 relay-only，避免剔除可用直连或为回迁再做一次 restart；可用 relay 与蜂窝直连由原生 ICE 选择。
- 上述数值是检测/调度参数，不是已测得的端到端恢复时间。需两台真机分别测试断开 Wi-Fi、弱 Wi-Fi 离开覆盖、无蜂窝、TURN UDP/TLS 和双向切换，记录最后/首个媒体帧、候选对切换、音频缺口和 relay 使用时长。探测更频繁的功耗影响也未实测。
- 项目已有 `CellularStandby` 主动蜂窝网络请求；本次没有新增双网络保持能力，也没有绑定整个进程到蜂窝网络。

真机按第 30 节场景执行，记录双方 `RTC_NETWORK_CHANGED`、`RTC_SELECTED_CANDIDATE`、`RTC_ROUTE_RECOVERED`、`RTC_ICE_STATE`、`RTC_ICE_RESTART` 日志，并与修改前同设备、同网络的实际音视频中断对比。只有设备数据通过后才勾选第 37 节验收项。
