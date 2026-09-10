---
title: Zisee 产品定义
document_id: PROD-001
version: 1.1.0
status: Active
created: 2026-09-08
updated: 2026-09-10
applies_to: ">=0.1.0"
owners:
  - core
---

# PRODUCT.md

# Zisee 产品定义

## 1. 产品名称

中文名称：

```text
咫尺
```

英文名称：

```text
Zisee
```

Slogan：

```text
See closer, even from afar.
```

核心表达：

> 即使相隔很远，也像近在咫尺一样，看见对方所看见的东西。

---

# 2. 产品一句话定义

Zisee 是一个 Android 原生的高质量实时视觉通信应用。

它不只解决：

```text
“看见对方”
```

更希望解决：

```text
“看见对方正在看什么，并一起理解那个现场”
```

核心能力围绕：

- 高清实时视频
- P2P 优先连接
- 前后摄同时传输
- 屏幕共享
- 远程视觉标注
- AR 空间锚点
- 弱网适应
- 低服务器依赖
- 隐私优先

展开。

---

# 3. 产品愿景

传统视频通话主要围绕：

```text
Face-to-Face
```

Zisee 希望扩展为：

```text
Face-to-Face
+
See What I See
+
Point Where I Mean
```

也就是：

```text
看见我
+
看见我看到的世界
+
准确指出我说的是哪里
```

长期目标不是做一个功能更多的视频聊天 App，而是做一个：

> 以“共享视野”为中心设计的实时通信工具。

---

# 4. 产品核心价值

Zisee 的核心价值分为四层。

## 4.1 更清楚地看见

目标：

- 视频清晰
- 延迟低
- 弱网下尽量不断
- 主画面重点区域保持清晰
- 网络状态变化时快速恢复

不是单纯追求：

```text
4K
高码率
高参数
```

而是追求：

```text
真正可用的高清
```

---

## 4.2 同时看见“人”和“现场”

传统视频通话通常只能：

```text
前摄
or
后摄
```

Zisee 希望做到：

```text
前摄
+
后摄
```

同时存在。

典型效果：

```text
后摄：
现场主画面

前摄：
对方本人 PiP
```

用户可以同时看到：

- 对方表情
- 对方正在面对的环境

这是 Zisee 的核心差异化之一。

---

## 4.3 让远程沟通变得“可指”

语音表达：

```text
“左边那个”
“上面一点”
“不是那个，是旁边那个”
```

非常低效。

Zisee 希望让用户可以直接：

- 点
- 圈
- 画箭头
- 放 Pin
- 放文字
- 指向现实空间

让远程沟通从：

```text
口头描述
```

变成：

```text
视觉指示
```

---

## 4.4 让标记属于现实世界

普通视频标注：

```text
固定在屏幕像素
```

Zisee 的 AR 模式：

```text
固定在现实空间
```

例如：

```text
某颗螺丝
某个插口
某个按钮
桌面上的某个位置
墙上的某个位置
```

用户移动手机后，标记仍尽量停留在原现实位置。

---

# 5. 目标用户

Zisee 第一阶段不限定为企业工具。

目标用户优先是：

```text
普通个人用户
+
技术型用户
+
需要远程指导的人
```

---

# 6. 核心用户场景

## 6.1 远程看东西

例如：

- “帮我看看这个接口是不是坏了。”
- “这个零件应该装哪？”
- “你看看这个商品怎么样。”
- “这房间实际长什么样？”
- “你帮我看一下这个警告灯是什么意思。”

---

## 6.2 远程维修 / 技术指导

例如：

- 修电脑
- 修路由器
- 修摩托
- 修汽车
- 装家电
- 接线
- 组装家具
- 调试设备

远程指导方可以：

```text
直接指出现实位置
```

而不是只靠语言。

---

## 6.3 日常生活指导

例如：

- 做饭
- 化妆
- 穿搭
- 家务
- 收纳
- 操作设备
- 教长辈使用电子产品

---

## 6.4 购物协作

例如：

现场用户进入商店。

远程用户：

```text
看商品
+
看现场用户
+
让对方靠近某件商品
+
指出具体商品
```

---

## 6.5 旅行与现场共享

例如：

```text
“你看我现在看到的景色。”
```

同时：

- 前摄保留本人
- 后摄展示环境
- 远端可以指出某个景物

---

## 6.6 看房 / 看现场

例如：

- 房屋
- 店铺
- 工地
- 仓库
- 设备现场

远端用户可以要求：

- 看左边
- 看天花板
- 靠近某区域
- 标记问题位置

---

## 6.7 远程技术支持

未来可以覆盖：

- IT Support
- Field Service
- After-sales Support
- Industrial Remote Assistance

但企业场景不是第一阶段唯一定位。

---

# 7. 核心产品模式

## 7.1 Face Call

普通高清视频通话。

默认：

```text
Front Camera
+
Microphone
```

目标：

- 接通快
- 延迟低
- 稳定
- 清晰

这是所有高级能力的基础。

---

## 7.2 Show Me

核心特色模式。

默认：

```text
Back Camera
=
主画面

Front Camera
=
PiP
```

远端用户同时看到：

```text
你
+
你看到的东西
```

---

## 7.3 Dual View

将前后摄以更平等的形式展示。

例如：

```text
┌──────────┬──────────┐
│ Front    │ Back     │
└──────────┴──────────┘
```

适合：

- 展示
- 对比
- 双视角协作

---

## 7.4 Screen Share

共享 Android 屏幕。M3 同时建立后台持续通话、应用内迷你通话和系统画中画；用户操作其他 App 时可以继续看对方。Show Me、共享、AR 的双端布局、互斥切换与生命周期，以及共享中的定格讲解，统一见 [M3 通话多任务专项](CALL_MULTITASKING.md)。这是待实施目标，当前 APK 仍会在后台结束通话。

适合：

- App 操作指导
- 设置指导
- 游戏
- 内容展示
- 技术支持

---

## 7.5 Annotation

普通实时标注。

支持方向：

- Pointer
- Circle
- Arrow
- Drawing
- Text

该模式不需要 AR。

---

## 7.6 AR Assist

在现实摄像头场景中进行空间标注。

现场方运行：

```text
ARCore
```

远端可以创建：

- Pin
- Arrow
- Circle
- Label
- Measure

目标：

```text
标记尽量固定到现实空间
```

---

# 8. 产品核心特性

第一阶段产品核心能力：

```text
1v1
P2P
HD Video
Audio
Front Camera
Back Camera
Dual Camera
TURN fallback
Screen Share
Annotation
AR Assist
```

---

# 9. P2P 是产品特性之一

P2P 不只是底层技术选择。

它带来的用户价值：

- 更低延迟
- 更少中心化媒体中转
- 更低基础设施成本
- 更自然的 1v1 架构
- 更容易允许较高 bitrate

因此：

```text
P2P First
```

属于 Zisee 产品原则。

但：

> P2P 不能以牺牲接通率为代价。

如果无法直连：

```text
TURN fallback
```

必须自动完成。

---

# 10. 高清的定义

Zisee 不把“高清”简单定义为：

```text
1080p
```

产品层面高清包括：

- 清晰度
- 稳定性
- 帧率
- 低延迟
- 低卡顿
- 主体可辨识
- 弱网恢复能力

例如：

```text
稳定 720p
```

可能优于：

```text
频繁卡顿的 1080p
```

因此产品策略：

> 连续性优先于分辨率数字。

---

# 11. 双摄产品原则

双摄不是：

```text
为了证明技术能做到
```

而是用于解决：

```text
“我想看你，同时也想看你面前的东西。”
```

---

## 11.1 主辅画面

默认：

```text
Back
=
Primary

Front
=
Secondary
```

但用户可以切换。

---

## 11.2 Bandwidth Follows Attention

用户关注哪个画面：

```text
哪个画面获得更多带宽
```

例如：

```text
Back
1080p30

Front
360p15
```

用户放大 Front 后：

```text
Front
1080p30

Back
360p15
```

这属于重要长期产品体验。

---

# 12. AR 产品原则

AR 只是 Zisee 的一个高级协作能力。

产品不应变成：

```text
“只有开启 AR 才有意义”
```

AR 需要：

```text
随时进入
+
随时退出
```

基础视频通话必须完全独立可用。

---

# 13. 用户体验原则

## 13.1 进入通话必须简单

不要因为技术能力多，导致：

```text
接电话前先选择十个模式
```

默认：

```text
接通
→
正常视频
```

高级能力按需开启。

---

## 13.2 功能渐进暴露

理想体验：

```text
Face Call
    ↓
Show Me
    ↓
Annotation
    ↓
AR
```

用户不需要理解：

```text
Track
ICE
TURN
ARCore
Anchor
Pose
```

这些都属于实现细节。

---

## 13.3 自动优于手动

例如：

```text
P2P / TURN
```

应该自动选择。

用户不应该看到：

```text
请选择 relay candidate
```

---

# 14. 产品可靠性优先级

用户体验优先级：

```text
1. 能接通
2. 不掉线
3. 声音清楚
4. 延迟低
5. 主画面清楚
6. 网络变化能恢复
7. 双摄好用
8. AR 标记准确
9. 功耗合理
10. 更高分辨率
```

---

# 15. MVP 定义

MVP 不等于实现所有产品构想。

第一版目标：

> 验证 P2P 高清视频基础能力，并建立后续多视角能力所需架构。

---

## MVP Phase 1

必须实现：

- Android 原生
- 1v1
- Audio
- Front Camera
- WebRTC
- P2P
- STUN
- TURN fallback
- 基础 Signaling
- Call state
- 基础 RTC stats
- 基础网络恢复

成功条件：

```text
两台真实 Android 手机可以稳定进行 1v1 通话
```

---

## Phase 2

重点实现：

- 后摄
- 摄像头切换
- 前后摄同时采集
- 两路独立 Video Track
- Show Me
- 主辅画面
- 动态质量策略初版

成功条件：

> 用户能够同时看到对方本人和对方现场。

---

## Phase 3

实现：

- Screen Share
- Remote Pointer
- 2D Annotation
- Circle
- Arrow

成功条件：

> 用户能够在远程画面中快速准确地指出目标。

---

## Phase 4

实现：

- ARCore
- Spatial Anchor
- Remote click
- Pin
- Arrow
- 基础现实空间标记

成功条件：

> 手机移动后，标记仍能基本保持在现实位置。

---

## Phase 5

提升 AR 精度：

- Timestamp alignment
- Pose history
- Depth
- Intrinsics
- 更准确 2D → 3D

成功条件：

> 移动镜头情况下远程标记仍具有较高可用性。

---

# 16. MVP 明确不做

为了避免 scope explosion，第一阶段不做：

- 多人会议
- 自研 codec
- 自研 WebRTC
- 自研 TURN
- 自研 SLAM
- 自研 SFU
- 4K
- 120fps
- Cloud Recording
- AI 实时翻译
- AI 视频生成
- AI 超分辨率
- Object Detection
- Persistent AR Map
- Cloud Anchor
- Remote Android Control
- Windows/macOS 客户端
- iOS 客户端

这些可以成为未来方向，但不能阻塞 MVP。

---

# 17. 后续产品能力

长期可能增加：

- AV1
- SVC
- AI Denoise
- Low Light Enhancement
- ROI Encoding
- Super Resolution
- Object Detection
- Object Tracking
- Persistent Spatial Anchor
- AR Measurement
- OCR
- Visual Search
- USB Camera
- External Camera
- Multi-device Camera
- Desktop Client
- iOS
- 3+ participants
- SFU

---

# 18. 产品差异化

Zisee 不应该只和：

```text
Zoom
FaceTime
WhatsApp
LINE
```

比较。

也不应该只和：

```text
TeamViewer Assist AR
Zoho Lens
```

比较。

Zisee 希望处于两类产品之间：

```text
Consumer Video Call
            +
Remote Visual Assistance
```

---

# 19. 与普通视频通话的差异

普通视频通话：

```text
看人
```

Zisee：

```text
看人
+
看现场
+
共同指认
+
共同理解
```

---

# 20. 与企业远程协助工具的差异

传统 Remote Assistance：

```text
Expert
→
Technician
```

Zisee 不限制角色。

可以是：

```text
朋友 ↔ 朋友
情侣 ↔ 情侣
家人 ↔ 家人
专家 ↔ 普通用户
工程师 ↔ 工程师
```

产品体验优先保持消费级。

---

# 21. 隐私原则

用户应默认认为：

> 我的通话内容不是中心服务器的产品资产。

原则：

- P2P 优先
- TURN 仅转发
- 媒体默认不保存
- 服务端不主动分析媒体
- 新增 Recording / AI server processing 必须明确告知

---

# 22. 权限原则

不要一次性申请：

- Camera
- Microphone
- Screen Capture
- AR
- Notification

所有权限：

```text
按需申请
```

例如：

用户点击：

```text
Share Screen
```

才触发屏幕捕获授权。

---

# 23. 产品成功指标

第一阶段不重点看：

```text
DAU
Revenue
```

而是先验证技术和体验。

---

## 23.1 连接指标

重点关注：

- Call setup success rate
- Call setup time
- P2P success rate
- TURN fallback rate
- ICE reconnect success rate
- Drop rate

---

## 23.2 视频指标

关注：

- 实际分辨率
- FPS
- bitrate
- frame drop
- freeze duration
- encode latency
- decode latency

---

## 23.3 用户体验指标

关注：

- 首次成功通话时间
- 双摄启用成功率
- Show Me 使用率
- Screen Share 使用率
- Annotation 使用率
- AR Assist 使用率

---

## 23.4 AR 指标

关注：

- Anchor creation success
- Tracking loss
- Marker stability
- Remote click error
- Timestamp alignment error

---

# 24. 关键产品假设

当前产品基于以下假设：

## 假设 1

普通用户存在：

```text
“你帮我看看这个”
```

类型的远程视觉沟通需求。

---

## 假设 2

前后摄同时传输：

```text
比频繁切摄像头更自然
```

---

## 假设 3

远程 Pointer / Annotation：

```text
能显著降低语言描述成本
```

---

## 假设 4

空间 AR 标记：

```text
在部分现实指导场景中明显优于 2D 标注
```

---

## 假设 5

P2P First：

```text
对 1v1 高频视觉通信有实际价值
```

---

# 25. 需要验证的产品问题

开发过程中必须验证，而不是直接假定：

- 用户是否真的经常需要双摄？
- 双摄带来的功耗是否可以接受？
- 用户是否理解 Show Me？
- 用户更常用 Pointer 还是 Arrow？
- AR Assist 的启用门槛是否太高？
- 多数真实网络下 P2P 成功率是多少？
- TURN fallback 是否足够快速？
- 1080p 是否真的比稳定 720p 带来明显体验提升？
- 用户是否愿意让后摄长期保持开启？
- 用户是否在乎 P2P / 隐私这个卖点？

---

# 26. 设计决策原则

遇到产品取舍时：

优先问：

```text
这是否让用户更快、更清楚地理解远方正在发生什么？
```

如果不能：

```text
应该谨慎增加。
```

---

# 27. Feature Gate 原则

高级能力应允许 capability-based feature gate。

例如：

```text
Concurrent Camera unsupported
→ 隐藏双摄入口

ARCore unsupported
→ 隐藏 AR Assist

Depth unsupported
→ AR 使用基础模式
```

不要展示：

```text
一个用户永远无法使用的按钮
```

---

# 28. 产品模式与技术能力映射

```text
Face Call
│
├── Microphone
├── Front Camera
└── WebRTC

Show Me
│
├── Front Camera
├── Back Camera
├── Concurrent Camera
└── Multi Track

Screen Share
│
├── MediaProjection
└── Video Track

Annotation
│
├── DataChannel
└── Overlay

AR Assist
│
├── ARCore
├── Depth
├── Pose
├── Anchor
└── DataChannel
```

---

# 29. 当前产品边界

当前 Zisee 是：

```text
实时通信产品
```

不是：

```text
社交网络
```

第一阶段不重点设计：

- Feed
- Public profile
- Followers
- Likes
- Public rooms
- Content discovery
- Creator ecosystem

避免项目失焦。

---

# 30. 当前非目标

明确非目标：

```text
成为 Zoom 替代品
成为 Discord 替代品
成为 TeamViewer 完整替代品
成为直播平台
成为社交媒体
成为监控摄像头平台
```

Zisee 的核心仍然是：

> 1v1 高质量视觉沟通与现场协作。

---

# 31. 品牌方向

品牌关键词：

- Near
- See
- Together
- Presence
- Visual
- Close
- Spatial
- Clear

中文：

```text
咫尺
```

表达：

```text
远而如近
```

英文：

```text
Zisee
```

品牌标语：

```text
See closer, even from afar.
```

---

# 32. 产品判断标准

未来任何 Feature Proposal 都应至少回答：

1. 它解决什么真实问题？
2. 哪个用户会使用？
3. 为什么视频通话现有能力不够？
4. 它是否增加接通复杂度？
5. 它是否增加显著功耗？
6. 它是否增加隐私风险？
7. 它是否增加服务器成本？
8. 它是否必须进入 MVP？
9. 如果不做，用户是否仍能完成核心任务？

---

# 33. 产品优先级

优先级从高到低：

```text
Reliability
↓
Latency
↓
Audio
↓
Main Video Quality
↓
Multi-view
↓
Annotation
↓
AR
↓
Advanced AI
```

任何高级功能不能破坏基础通话稳定性。

---

# 34. 成功的 Zisee

成功的 Zisee 不应该让用户想到：

```text
“这个 App 技术好多。”
```

而应该让用户觉得：

```text
“我现在终于知道你说的是哪个了。”
```

或者：

```text
“你不用过来，我直接给你看。”
```

或者：

```text
“就像你站在我旁边一样。”
```

这就是“咫尺”的产品价值。

---

# Changelog

## 1.1.0 - 2026-09-10

- 补充 M3 后台通话、画中画与跨功能协作专项入口，区分目标行为与现有实现。

## 1.0.0 - 2026-09-08

- 建立 Zisee 产品定义。
- 明确品牌、愿景与核心价值。
- 定义 Face Call、Show Me、Screen Share、Annotation、AR Assist。
- 定义目标用户与核心场景。
- 定义 MVP 与非目标。
- 定义产品成功指标。
- 定义关键假设和待验证问题。
