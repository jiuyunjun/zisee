---
title: Zisee 开发路线图
document_id: PROD-ROADMAP-001
version: 1.3.0
status: Active
created: 2026-09-08
updated: 2026-09-10
applies_to: ">=0.1.0"
owners:
  - core
---

# ROADMAP.md

# Zisee 开发路线图

## 1. 文档目的

本文档定义 Zisee 从立项到可用产品的阶段性开发路线。

路线图遵循以下原则：

- 先把基础通话做稳，再增加高级能力。
- 先验证核心产品价值，再追求复杂技术。
- 每个阶段都应有明确退出条件。
- 不允许后续阶段功能反向阻塞前置里程碑。
- 所有“未来可能会做”的能力都不自动进入当前范围。
- 每个阶段完成后应重新评估下一阶段，而不是机械继续。

---

# 2. 总体阶段

当前路线分为：

```text
M0  项目基础
M1  1v1 P2P 通话
M2  双摄与 Show Me
M3  屏幕共享与 2D 标注
M4  基础 AR Assist
M5  高精度 AR 同步
M6  弱网与媒体智能
M7  产品化与发布
M8  后续扩展
```

优先顺序：

```text
稳定
↓
可用
↓
差异化
↓
智能化
```

---

# 2.1 当前进度速览（2026-09-10）

```text
M0  项目基础              完成
M1  1v1 P2P 通话          完成
M1.1 连接可靠性           完成（网络切换/ICE restart/回声退避已验证）
M2  双摄与 Show Me        完成（含方向适配、悬浮小窗、多画面）
M3  屏幕共享与 2D 标注    未开始（Annotation2D 数据模型已占位，无 UI/传输）
M4  基础 AR Assist        基础协作闭环代码已实现，未经真机端到端验证（见下）
M5  高精度 AR 同步        历史帧与投影基础已实现（端到端时间戳映射与精度验收待做）
M6  弱网与媒体智能        部分完成，提前于计划
M7  产品化与发布          未开始
M8  后续扩展              未开始
```

M4 现状（2026-09-10，基线 `3b0c84d`）：ARCore 会话/能力/历史帧/投影框架、独占相机接管与恢复、GPU 视频投递、H264 帧身份与展示帧闩锁、双端 DataChannel、通话内双角色入口（开启我的现场 / 加入对方标记）、Pin/Arrow/Circle 空间标记叠加、本人撤销/清除、现场方全清与暂停指导方权限均已落地并有 JVM 测试。Debug 构建新增「设置 → 调试工具 → 通话与 AR 界面预览」用合成画面在真机上检查界面与 AR 状态（不含摄像头/ARCore/网络）。

**尚未验证**：物理 ARCore 相机接管/恢复（本地回环 ICE 在收到视频帧后进入 FAILED，连续三次阻断，疑为单进程回环在采集切换时的传输存活问题，非双设备场景）、双设备空间点击落点一致、跟踪丢失真机反馈、30/60 分钟功耗与温度。作者/编号、标记列表、重连快照、清空事务代数、Pointer、请求对方开启/仅观看/交换现场等产品完善项也未实现，顺序见 [AR_INTERACTION.md](AR_INTERACTION.md) §12。

M6 提前推进的原因：AGENTS.md 明确音频优先级高于视频，AUDIO.md 定义的音频链路（AEC/DeepFilterNet AI 降噪/Opus/NetEq/弱网带宽保护）被判定为核心通话体验的一部分，因此在 M3 之前就已实现，而不是机械按 M2 → M3 → M6 顺序推进。这符合第 30 条“路线图调整规则”。

已完成能力清单：

- P2P WebRTC 通话，STUN/Cloudflare TURN fallback，WebSocket signaling，Firestore + Cloud Run 后端。
- ICE restart、Wi-Fi/蜂窝切换、trickle ICE、通话状态机、来电推送与联系人/邀请。
- 双摄同时采集（Show Me）、悬浮小窗拖拽吸边、多画面 tile、方向感知与屏幕旋转锁适配。
- 视频质量阶梯（含 1080p60 上限）、热状态降载、RTC stats 监控。
- 音频子系统：WebRTC AEC/AGC、DeepFilterNet AI 降噪（原生 Rust/JNI，带实时 deadline 与安全回退）、CallAudioManager 统一路由（含蓝牙 SCO 恢复）、弱网音频优先带宽策略（暂停辅摄→暂停全部视频保语音）。

已知未验收项（详见 [AUDIO.md](../architecture/AUDIO.md) 第 56 节）：设备矩阵（低/中/高端）、蓝牙/有线/USB 路由矩阵、真实弱网、双讲回声、风噪、长通话功耗/温度、AI 降噪主观 A/B、Debug 分级音频录制（§38，A/B 测试前提）。这些应在进入 M7.1 稳定性矩阵前补齐，不阻塞 M3 功能开发。

---

# 3. M0：项目基础

## 目标

建立可以长期维护的项目骨架。

---

## 范围

完成：

- Android 原生工程创建
- Kotlin
- Jetpack Compose
- 基础包结构
- Gradle 配置
- UTF-8 / LF 规范
- Git 规范
- CI 基础
- 文档体系
- 基础日志
- 基础依赖注入策略
- 基础配置管理

---

## 文档

至少存在：

```text
README.md
AGENTS.md
ARCHITECTURE.md
DOCS.md
PRODUCT.md
ROADMAP.md
SECURITY.md
TESTING.md
```

---

## 仓库基础文件

至少：

```text
.editorconfig
.gitattributes
.gitignore
LICENSE
```

---

## 建议技术

```text
Kotlin
Jetpack Compose
Coroutines
Flow
Gradle Version Catalog
```

---

## 退出条件

- 项目可在开发机成功构建。
- Debug APK 可安装到真实 Android 设备。
- 基础 CI 能运行构建或静态检查。
- 文档入口完整。
- Git 提交规范开始执行。

---

# 4. M1：1v1 P2P 高清通话

## 产品目标

实现：

> 两台 Android 手机可以稳定建立 1v1 音视频通话。

这是整个项目最重要的基础里程碑。

---

## 功能范围

实现：

- 用户 A 呼叫用户 B
- 用户 B 接听 / 拒绝
- 麦克风
- 前摄
- 本地预览
- 远端视频
- 挂断
- 基础通话状态
- 前后台基本处理
- 扬声器 / 听筒基础切换

---

## RTC

实现：

```text
WebRTC Native
PeerConnection
SDP Offer / Answer
ICE Candidate
STUN
P2P
TURN fallback
DataChannel 基础
```

---

## Signaling

实现：

```text
WebSocket
```

最少支持：

```text
CALL_INVITE
CALL_ACCEPT
CALL_REJECT
SDP_OFFER
SDP_ANSWER
ICE_CANDIDATE
CALL_END
```

---

## Backend

第一阶段尽量简单：

```text
Go
+
WebSocket
+
HTTPS
```

部署：

```text
Cloud Run
```

或同等级轻量服务。

---

## TURN

首选：

```text
Cloudflare TURN
```

要求：

- 短期 credential
- 不把长期密钥写入 APK
- TURN 只作为 fallback

---

## 状态机

实现：

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

---

## Telemetry

开始记录：

- Call setup time
- P2P / TURN
- ICE state
- candidate type
- RTT
- jitter
- packet loss
- bitrate
- resolution
- FPS

---

## 网络测试

至少验证：

```text
Wi-Fi ↔ Wi-Fi
Wi-Fi ↔ 5G
5G ↔ Wi-Fi
5G ↔ 5G
```

---

## 退出条件

- 两台真实 Android 设备可重复建立通话。
- 正常网络下接通成功率达到可接受水平。
- P2P 成功时不会错误走 TURN。
- P2P 不可用时 TURN 可以正常接通。
- 通话过程中不存在明显持续音视频泄漏。
- 挂断后 Camera / Mic / PeerConnection 正确释放。
- 基础 RTC stats 可查看。

---

# 5. M1.1：连接可靠性

该阶段可以与 M1 后半并行。

## 目标

解决：

```text
“能通”
```

到：

```text
“比较稳定地通”
```

---

## 功能

- ICE disconnected 处理
- ICE failed 处理
- Signaling reconnect
- 网络变化检测
- ICE restart
- Wi-Fi / Cellular 切换
- App 临时进入后台
- 远端异常退出
- 超时处理
- 通话结束一致性

---

## 退出条件

- Wi-Fi → 5G 时能够恢复连接。
- 5G → Wi-Fi 时能够恢复连接。
- Signaling 短暂断线不导致媒体立刻结束。
- 失败场景能够明确进入 Failed/Ended，而不是无限卡住。

---

# 6. M2：后摄与 Show Me

## 产品目标

验证 Zisee 第一个明显差异化能力：

> 同时看见“人”和“现场”。

---

## 功能范围

先实现：

- Front Camera
- Back Camera
- 摄像头切换

然后实现：

- Concurrent Camera capability check
- 前后摄同时采集
- 双 Video Track
- 后摄主画面
- 前摄 PiP
- 主辅切换

---

## Show Me 模式

默认：

```text
Back Camera
=
Primary

Front Camera
=
Secondary
```

远端 UI：

```text
┌──────────────────────────┐
│                          │
│      Back Camera         │
│                          │
│                ┌──────┐  │
│                │Front │  │
│                └──────┘  │
└──────────────────────────┘
```

---

## 降级策略

设备不支持 Concurrent Camera：

```text
Show Me
→
快速前后摄切换模式
```

不能因为设备不支持双摄导致功能不可用。

---

## 媒体策略

初始规则：

```text
Primary:
720p/1080p
30fps

Secondary:
360p
15fps
```

具体数值以真实设备测试为准。

---

## Bandwidth Follows Attention V1

当用户切换主画面：

```text
Primary Track
↑ quality

Secondary Track
↓ quality
```

第一版采用简单规则。

不引入复杂预测模型。

---

## 性能测试

必须关注：

- CPU
- 内存
- 电量
- 温度
- encode FPS
- camera FPS
- 两个 hardware encoder 是否可同时工作
- 不同 SoC 表现

---

## 退出条件

- 至少若干支持 Concurrent Camera 的真实设备可稳定双摄通话。
- 不支持设备能够正常降级。
- 主辅 Track 可以独立启停。
- 主辅画面切换后画质策略正确。
- 双摄不会造成不可接受的持续过热或严重掉帧。

---

# 7. M3：屏幕共享

## 产品目标

扩展：

```text
现实环境
```

到：

```text
手机内部界面
```

---

## 功能

- MediaProjection
- Foreground Service
- Screen Video Track
- Screen Share 开始
- Screen Share 停止
- Screen Share 状态同步
- 远端正确显示

---

## 组合

至少允许：

```text
Audio
+
Screen
```

推荐支持：

```text
Audio
+
Front Camera
+
Screen
```

是否同时保留：

```text
Front
+
Back
+
Screen
```

根据设备负载动态限制。

---

## 退出条件

- 屏幕共享稳定。
- 用户停止系统共享后状态正确恢复。
- App 切换前后台行为符合 Android 要求。
- 不留下 MediaProjection / Foreground Service 泄漏。

---

# 8. M3.1：2D 远程标注

## 产品目标

解决：

```text
“你说的到底是哪个？”
```

---

## 功能

实现：

- Pointer
- Click Ripple
- Circle
- Arrow
- Free Draw
- Clear
- Undo

优先级：

```text
Pointer
Circle
Arrow
```

---

## 数据传输

使用：

```text
WebRTC DataChannel
```

传递：

```text
normalized x/y
```

而不是像素坐标。

---

## 坐标系统

必须正确处理：

- 视频宽高比
- Letterbox
- Crop
- Rotation
- Screen orientation

---

## 退出条件

- 不同分辨率手机之间标记位置一致。
- 横竖屏切换后坐标正确。
- DataChannel 延迟足够满足实时 pointer。
- 标注不会显著影响视频性能。

---

# 9. M4：基础 AR Assist

## 产品目标

实现：

> 远程用户可以把标记“钉”到现实空间。

---

## 现场方

运行：

```text
ARCore
```

支持：

- Tracking
- Camera Pose
- Hit Test
- Plane
- Anchor

第一阶段 Depth 不是强制。

---

## 远端

流程：

```text
看到视频
↓
点击目标
↓
发送 x/y
↓
现场创建 Anchor
```

---

## AR 标注类型

MVP：

- Pin
- Arrow
- Circle

---

## 本阶段容忍

由于暂时没有完整 timestamp alignment：

- 现场手机快速移动时允许出现一定误差。
- 第一目标是验证 AR 交互价值。

---

## 退出条件

- 用户点击静态场景目标后可以创建 Anchor。
- 手机小范围移动后标记仍基本保持在现实位置。
- AR tracking lost 时有明确 UI 提示。
- AR 功能可随时退出而不影响基础通话。

---

# 10. M5：高精度 AR 时间同步

## 产品目标

解决 Remote AR 的核心精度问题：

```text
远端看到的是历史视频帧
```

---

## 实现

建立：

```text
Video Frame Timestamp
↕
ARCore Pose History
```

现场端维护：

```text
PoseRingBuffer
```

建议：

```text
2 ~ 5 秒
```

---

## 保存

每个记录：

```text
timestamp
cameraPose
intrinsics
trackingState
```

可选：

```text
depth
```

---

## Remote Touch

传：

```text
x
y
videoFrameTimestamp
```

---

## 现场解析

```text
timestamp
↓
Pose lookup
↓
2D ray
↓
3D
↓
Anchor
```

---

## Depth

加入：

```text
ARCore Depth API
```

用于：

- 非平面物体
- 更复杂表面
- 更准确空间点

---

## 指标

建立：

- Timestamp alignment error
- Pose lookup error
- Anchor placement error
- Tracking loss rate

---

## 退出条件

- 现场移动手机时，远程点击误差明显优于 M4。
- 历史 Pose 匹配稳定。
- Depth 支持设备正确启用。
- 不支持 Depth 的设备有 fallback。

---

# 11. M6：媒体与网络智能化

这是优化阶段，不阻塞核心产品。

---

## 11.1 Codec Capability

实现 capability matrix：

- H.264
- VP8/VP9（若需要）
- AV1 encode
- AV1 decode
- Hardware / Software

---

## 11.2 AV1

只有满足：

```text
hardware support
+
thermal acceptable
+
interop validated
```

才启用。

否则：

```text
H.264
```

---

## 11.3 Adaptive Video

动态调整：

- bitrate
- resolution
- FPS
- Track priority

输入：

- bandwidth
- RTT
- packet loss
- jitter
- encoder load
- thermal
- battery

---

## 11.4 Weak Network

目标：

```text
Audio first
```

网络变差：

```text
Secondary video quality ↓
↓
Primary video quality ↓
↓
FPS ↓
↓
Resolution ↓
```

不要先破坏音频。

---

## 11.5 FEC / Retransmission

评估：

- NACK
- RTX
- FEC

根据真实网络数据调整。

---

## 11.6 Network Intelligence V1

第一版：

```text
Rule Based
```

后期再考虑：

```text
Predictive
```

---

## 退出条件

- 网络质量明显变化时视频可主动适配。
- 双 Track 的带宽分配合理。
- Thermal 状态过高时自动降载。
- 不因为追求 AV1 导致兼容性下降。

---

# 12. M6.1：视频感知优化

非核心，可独立推进。

## 候选能力

- Face ROI
- Subject ROI
- AI Denoise
- Low-light enhancement
- GPU preprocessing
- Super Resolution

---

## 原则

任何 AI 能力必须证明至少一个：

```text
更清晰
更省带宽
更低噪声
更好低光
```

并衡量：

- latency
- CPU/GPU
- power
- thermal

不能只做 Demo 效果。

---

# 13. M7：产品化

## 目标

从：

```text
工程原型
```

进入：

```text
可以长期使用的 App
```

---

## 用户体系

根据实际使用需要决定：

- 匿名临时 ID
- 账号
- 好友
- 邀请链接
- QR code

第一版避免过重社交体系。

---

## Incoming Call

加入：

```text
FCM
```

支持：

- App 后台来电
- Incoming Call UI
- Missed Call 基础处理

---

## 权限体验

优化：

- Camera
- Microphone
- Notification
- Screen Share
- AR

全部：

```text
按需申请
```

---

## 设置

包括：

- 默认视频质量
- 是否允许双摄
- Speaker
- Debug stats（开发版本）
- 隐私设置

---

## 错误体验

用户看到的应该是：

```text
网络连接失败，正在重试
```

而不是：

```text
ICE_CONNECTION_FAILED
```

技术错误保留日志。

---

# 14. M7.1：稳定性与测试矩阵

## Device Matrix

建立：

```text
DEVICE_MATRIX.md
```

至少覆盖不同：

- Pixel
- Samsung
- Xiaomi
- Sony
- Oppo / OnePlus
- Snapdragon
- Tensor
- Exynos
- MediaTek

具体设备根据实际资源决定。

---

## Android Version

建议覆盖：

```text
minSdk
mid-range supported version
latest Android
```

---

## 网络

至少：

- 家庭 Wi-Fi
- 公司 Wi-Fi
- 4G
- 5G
- Wi-Fi ↔ Cellular 切换
- 高 RTT
- packet loss
- bandwidth limit

---

## 长时间测试

至少：

```text
30 min
60 min
```

观察：

- memory
- thermal
- battery
- camera stability
- encoder stability
- reconnect

---

# 15. M7.2：发布准备

包括：

- App icon
- Branding
- Privacy Policy
- Terms（需要时）
- Crash reporting
- Release signing
- ProGuard / R8
- Store listing
- Versioning
- Release notes

---

# 16. M8：后续扩展

以下全部属于候选，不自动进入计划。

---

## 16.1 Persistent AR

- Cloud Anchor
- Geospatial Anchor
- Visual relocalization
- Persistent markers

---

## 16.2 Object Intelligence

- Object detection
- Object tracking
- OCR
- Semantic segmentation
- Visual search

---

## 16.3 Remote Control

可能研究：

- AccessibilityService
- Device Owner
- Shizuku
- enterprise control

但：

```text
不是当前核心方向
```

---

## 16.4 多设备视频源

例如：

- USB Camera
- 第二台手机
- Action Camera
- External Camera

---

## 16.5 多人

当真实需求出现：

```text
3+
```

重新评估：

```text
SFU
```

候选：

- LiveKit
- mediasoup
- custom SFU（非常后期）

---

## 16.6 Desktop / iOS

未来：

- iOS
- Windows
- macOS
- Web

但 Android 原生体验优先。

---

# 17. 明确暂缓

以下功能当前不进入开发计划：

```text
4K
120fps
直播
公开房间
Feed
社交网络
云录制
自研 Codec
自研 TURN
自研 SFU
自研 SLAM
复杂远程控制
```

除非产品目标发生明确变化。

---

# 18. 技术债务管理

技术债务不能只存在脑中。

建议建立：

```text
docs/TECH_DEBT.md
```

记录：

```text
ID
问题
原因
影响
优先级
建议解决阶段
```

但不要把所有临时代码都立即抽象。

---

# 19. 每阶段进入条件

进入下一阶段前：

- 当前阶段核心退出条件已满足。
- 没有阻塞性 P0/P1 Bug。
- 架构文档已更新。
- 测试结果可复现。
- Git 工作区干净或明确记录。
- 关键 telemetry 已可观察。

---

# 20. Bug 优先级

## P0

例如：

- 安全问题
- 隐私泄漏
- 数据破坏
- App 无法启动

立即处理。

---

## P1

例如：

- 无法接通
- 大量掉线
- Camera 无法释放
- TURN fallback 失效
- 严重 crash

阻塞下一里程碑。

---

## P2

例如：

- 部分设备双摄失败
- AR 偶发漂移
- 网络恢复慢

根据阶段处理。

---

## P3

例如：

- UI 小问题
- 非核心体验问题

可以进入 backlog。

---

# 21. 第一轮开发建议

实际开始编码后，建议顺序：

```text
1. Android app skeleton
2. Permissions
3. Local camera preview
4. Local microphone
5. WebRTC PeerConnection
6. Local loop / two-device test
7. Signaling
8. P2P ICE
9. TURN fallback
10. Call state machine
11. RTC stats
12. Reconnect
```

不要第一周就开始 AR。

---

# 22. 第一个真正可演示版本

建议定义：

```text
Zisee 0.1.0
```

能力：

```text
Android ↔ Android
1v1
Audio
Front Camera
P2P
TURN fallback
```

---

# 23. 第一个体现产品差异化的版本

建议：

```text
Zisee 0.2.0
```

加入：

```text
Front + Back
Show Me
Dual Track
```

这是第一个真正能让人看出：

> “它和普通视频电话不一样。”

的版本。

---

# 24. 第一个完整概念验证版本

建议：

```text
Zisee 0.3.0
```

加入：

```text
Screen Share
2D Annotation
```

---

# 25. 第一个 AR 版本

建议：

```text
Zisee 0.4.0
```

加入：

```text
ARCore
Spatial Anchor
Remote AR Marker
```

---

# 26. 第一个高质量 AR 版本

建议：

```text
Zisee 0.5.0
```

加入：

```text
Pose History
Timestamp Alignment
Depth
```

---

# 27. 第一个 Beta

候选：

```text
Zisee 0.8.0
```

重点：

- 稳定性
- 网络恢复
- 设备兼容性
- 权限体验
- Push
- Crash monitoring

---

# 28. 1.0.0 标准

不要因为功能多就发布 1.0。

建议满足：

- 基础通话足够稳定
- P2P / TURN 工作可靠
- Show Me 可长期使用
- Screen Share 可用
- Annotation 可用
- AR Assist 至少基础可用
- 关键设备完成测试
- 隐私策略明确
- Release 流程稳定
- 已知严重 Bug 可控

---

# 29. 当前最高优先事项

当前项目处于：

```text
M2 完成，M6 音频/视频弱网智能部分提前完成，
M4 基础 AR 协作闭环代码已实现但从未真机端到端验证
```

下一目标：

```text
先打通 M4 AR 真机端到端验证，再继续 AR 完善项或回到 M3
```

详见 [2.1 当前进度速览](#21-当前进度速览2026-09-10)。

## 29.1 为什么优先 AR 真机验证而不是直接做 M3

M4 的相机接管、双端协作和空间标记已经写了十余个提交，但退出条件里“用户点击目标后创建 Anchor、手机移动后标记保持在现实位置、tracking lost 有提示、AR 可随时退出不影响通话”一条都没在真实两台设备上验证过。按第 31 条“每一步都必须自己成为一个可用产品增量”，这块 AR 代码目前不是可用增量——它可能在真机上根本跑不通。在未验证的基础上继续叠作者/编号、标记列表、重连快照（AR_INTERACTION.md §12 第 2 步），或者转身去做 M3 把它长期搁置，都会让这部分投入悬空。因此先用一次真机窗口把它推到一个“已验证 / 已证伪”的明确检查点。

## 29.2 AR 真机验证清单

需要两台真实 Android 设备（至少一台支持 ARCore）、双方 APK 与后端为同一版本。逐项记录结果：

1. 现场方从 FACE / BACK_ONLY / DUAL 进入 AR：相机接管成功，`CameraMode.AR` 只显示 video_back，对方收到现场视频。
2. 结束现场：恢复此前摄像头模式，无残留 notice，通话与音频不中断。
3. 启动/恢复期间挂断：不发生 ICE FAILED，资源正常释放。
4. 指导方点“加入对方标记”：无需 ARCore，工具可用。
5. 双方在同一现场放置 Pin/Arrow/Circle：两端看到同一位置的标记（现场端 Anchor 权威 + 烧入 video_back）。
6. 现场方小范围移动手机：标记基本保持在现实位置；误差量级记录下来（M4 容忍一定误差，M5 才收紧）。
7. Tracking lost：两端都有明确 UI 提示；恢复后标记回到原位或明确失效。
8. 本人撤销 / 清除本人标记 / 现场方全清 / 暂停指导方权限：双端一致生效。
9. 两端同时开启现场：主叫端保留自己的现场，另一端收敛并恢复摄像头。
10. 连续 30 / 60 分钟 AR 协作：内存、温度、电量、编码器与相机稳定性。

单进程本地回环 ICE 在采集切换后进入 FAILED 的问题：先判断它是回环特有（真机双设备传输在 AR 接管时不重协商 ICES，应当存活）还是真实缺陷。若真机复现则升级为 P1 阻塞 M4 退出。

## 29.3 验证之后

- **AR 通过**：按 AR_INTERACTION.md §12 第 2 步推进一致性与权限（作者/编号、标记列表、跟踪状态同步、清空代数、加入/重连快照），完成后 M4 可视为达到退出条件，再评估 M5。
- **AR 证伪或代价过高**：把 AR 降回“框架保留、产品搁置”，转入 M3（MediaProjection 屏幕共享 + DataChannel 2D 标注），复用已占位的 `Annotation2D` / `VideoFrameReference` 坐标语义（`ar/annotation/Annotation.kt`）。

## 29.4 当前不要优先投入

- AV1 硬编解码矩阵
- RNNoise 二级 AI 降噪、Music Profile（AUDIO.md §44/§23，已明确标为后续）
- Cloud Anchor / Geospatial Anchor
- SFU / 多人
- Remote Control

另外应在不阻塞上述 AR 验证的前提下，安排真机窗口时一并补齐 AUDIO.md §56 遗留的验收项（设备矩阵、弱网、蓝牙路由、A/B），避免拖到 M7.1 才发现降噪效果或功耗不达标。

---

# 30. 路线图调整规则

ROADMAP 不是不可修改的承诺。

如果验证发现：

```text
某功能价值低
```

可以删除。

如果发现：

```text
新的阻塞问题
```

可以插入新 milestone。

但有意义修改必须：

- 更新版本号
- 更新 updated 日期
- 更新 Changelog
- Git commit

---

# 31. 成功路线

推荐保持：

```text
M0
工程基础

↓
M1
可靠的视频电话

↓
M2
双摄 Show Me

↓
M3
视觉指示

↓
M4/M5
现实空间协作

↓
M6
智能优化

↓
M7
产品化
```

核心原则：

> 每一步都必须自己成为一个可用产品增量。

不要构建一个只有全部功能同时完成才有价值的系统。

---

# Changelog

## 1.3.0 - 2026-09-10

- 更新「2.1 当前进度速览」至 2026-09-10：M4 从「框架已实现」改为「基础协作闭环代码已实现，未经真机端到端验证」，列出已落地能力、Debug 界面预览工具和未验证项。
- 重写第 29 条当前最高优先事项：下一目标由「直接做 M3」改为「先打通 M4 AR 真机端到端验证」；新增 29.1 理由、29.2 真机验证清单、29.3 验证后的两条分支（AR 通过则推进一致性/权限，证伪则降级 AR 转入 M3）、29.4 当前不投入项。
- 记录本地回环 ICE 在采集切换后 FAILED 的问题，待真机判定是否升级为 P1。

## 1.2.0 - 2026-09-09

- 按本次确认范围提前完成 AR 框架，详见 [AR_FRAMEWORK.md](../architecture/AR_FRAMEWORK.md)。
- M4/M5 仅标记框架进度；通话交互、媒体接入、真实时间戳映射及真机退出条件仍未完成，M3 范围不变。

## 1.1.0 - 2026-09-09

- 补充「2.1 当前进度速览」：标记 M0/M1/M1.1/M2 完成，M6 音频与弱网带宽策略提前完成，M3 起未开始。
- 更新第 29 条当前最高优先事项：从 M0→M1 更新为 M2 完成后进入 M3（屏幕共享 + 2D 标注），并指出可复用已占位的 `Annotation2D` 数据模型。
- 记录 AUDIO.md §56 遗留验收项（设备矩阵、弱网、蓝牙路由、AI 降噪 A/B、Debug 分级录音）应在 M7.1 前补齐。

## 1.0.0 - 2026-09-08

- 建立 Zisee 开发路线图。
- 定义 M0 至 M8 阶段。
- 明确各阶段目标、范围和退出条件。
- 定义首个可演示版本和产品差异化版本。
- 明确当前最高优先事项为 M0 → M1。
