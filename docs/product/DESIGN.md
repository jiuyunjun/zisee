---
title: Zisee UI/UX 设计规范
document_id: DESIGN-001
version: 1.0.1
status: Active
created: 2026-09-08
updated: 2026-09-10
applies_to: ">=0.1.0"
owners:
  - core
  - design
---

# DESIGN.md

# Zisee UI/UX 设计规范

## 1. 文档目的

本文档用于指导 Zisee 的 UI/UX 设计。

目标是让设计师、Claude Design、其他设计模型或前端开发者能够快速理解：

- Zisee 是什么
- 用户核心任务是什么
- 产品应该呈现什么气质
- 页面和流程如何组织
- 哪些功能是核心
- 哪些交互必须简单
- 哪些设计方向应该避免

本文件是：

```text
产品视觉与交互设计入口
```

不是：

```text
技术架构文档
```

技术实现请参考：

```text
ARCHITECTURE.md
PRODUCT.md
ROADMAP.md
```

---

# 2. 品牌

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

品牌核心含义：

> 即使相隔很远，也能像近在咫尺一样，看见对方、看见对方所见，并一起理解现实现场。

---

# 3. 产品定位

Zisee 是一个：

```text
高质量 1v1 视频通信
+
共享视野
+
远程视觉协作
```

产品核心不是传统：

```text
“看见对方”
```

而是：

```text
“看见对方正在看什么”
```

以及：

```text
“直接指出我说的是哪里”
```

---

# 4. 设计目标

Zisee 的 UI 必须优先满足：

1. 接通快
2. 操作直觉
3. 视频内容占主导
4. 高级功能不干扰基础通话
5. 从普通通话自然过渡到 Show Me / 标注 / AR
6. 尽量少解释技术概念
7. 强调“临场感”
8. 强调“看见”
9. 强调“靠近”
10. 让 UI 在使用中逐渐消失

---

# 5. 设计核心原则

## 5.1 Content First

视频画面永远是第一视觉中心。

界面不应该抢夺注意力。

错误方向：

```text
大量工具栏
大面积卡片
复杂控制面板
过多状态数字
```

正确方向：

```text
画面占主导
控制层轻量
需要时出现
不用时隐藏
```

---

## 5.2 Progressive Disclosure

不要一次展示所有能力。

用户进入通话后：

```text
Face Call
```

然后逐步出现：

```text
Show Me
Screen Share
Annotation
AR Assist
```

高级功能按需展开。

---

## 5.3 One-Hand Friendly

移动端操作尽量支持单手。

核心操作：

- 接听
- 挂断
- 切换摄像头
- Show Me
- 静音
- 扬声器
- 标记

尽量放在自然触达区域。

---

## 5.4 Calm UI

Zisee 不应该像：

```text
监控软件
工业控制台
电竞面板
企业远程桌面
```

也不应该像：

```text
社交短视频 App
```

整体气质：

```text
安静
现代
温和
高级
克制
自然
```

---

# 6. 视觉关键词

设计关键词：

```text
Near
Clear
Presence
Soft
Spatial
Quiet
Intimate
Modern
Minimal
```

中文感觉：

```text
近
静
清
透
柔
空间感
临场感
```

---

# 7. 视觉方向

推荐：

- 大面积视频内容
- 深色通话界面
- 半透明控制层
- 柔和圆角
- 少量 blur
- 强调空间层级
- 图标简洁
- 不做大量高饱和色
- 不做重阴影
- 不做拟物
- 不做复杂渐变背景

---

# 8. 品牌色原则

不要在 DESIGN.md 中硬编码最终品牌色。

设计模型可以先探索：

```text
冷灰
深蓝灰
雾白
轻微青色/蓝色 accent
```

但要求：

- 品牌色只作为操作强调
- 不大面积刷满背景
- 视频画面优先
- AR 标注颜色要与品牌色区分

---

# 9. 字体

Android：

优先系统字体体系。

中文：

```text
Noto Sans CJK / 系统中文字体
```

英文：

```text
Roboto / 系统字体
```

不要为品牌感强行使用复杂 Display Font。

标题应简洁、干净。

---

# 10. 圆角

整体使用中等圆角。

建议：

```text
Button:
20~28dp

Card:
20~28dp

PiP:
18~24dp
```

避免：

```text
极端胶囊化
所有元素都圆成 pill
```

---

# 11. 动效原则

动效要表达：

- 状态变化
- 空间关系
- 画面切换
- 主辅视角变化

不要为了“高级感”增加无意义动画。

推荐：

- 200~300ms
- Ease out
- 柔和缩放
- 淡入淡出
- PiP 平滑移动

避免：

- Bounce
- 夸张弹簧
- 大幅旋转
- 复杂粒子

---

# 12. 核心导航

第一阶段不要复杂 Bottom Navigation。

推荐：

```text
Home
↓
Call
↓
In Call
```

必要页面：

```text
Home
Recent Calls
Contact / Invite
Incoming Call
Outgoing Call
In Call
Settings
Permissions
Diagnostics（开发版）
```

---

# 13. Home

首页目标：

> 用户能够非常快地发起通话。

推荐内容：

```text
Logo / Zisee
一句简短状态
主 CTA
最近联系人 / 最近通话
```

主按钮：

```text
开始通话
```

或：

```text
呼叫
```

如果未来有联系人体系：

```text
最近
联系人
邀请
```

---

# 14. 首页视觉

首页不需要复杂 Dashboard。

不要展示：

- 网络统计
- codec
- TURN
- ICE
- ARCore
- 技术指标

这些都不属于普通用户。

---

# 15. Incoming Call

来电界面必须极简。

显示：

- 对方名称
- 头像
- 来电状态
- 接听
- 拒绝

可选：

```text
Video Call
```

不要显示：

```text
P2P
TURN
HD
AV1
```

除非未来产品明确需要。

---

# 16. Outgoing Call

拨号中显示：

- 对方
- 呼叫中
- 本地预览
- 挂断

连接状态可用自然语言：

```text
正在连接…
```

不要显示：

```text
ICE gathering
```

---

# 17. In Call

这是最重要的页面。

布局原则：

```text
远端视频
=
全屏主画面
```

本地视频：

```text
PiP
```

控制层：

```text
底部浮层
```

---

# 18. In Call 默认控制

默认显示：

- 麦克风
- 摄像头
- 切换摄像头 / Show Me
- 更多
- 挂断

可选：

- Speaker

不要默认展示：

- Screen Share
- AR
- Pointer
- Draw
- Measure
- Codec
- Quality selector

这些放入二级控制。

---

# 19. 控制层行为

推荐：

```text
用户点击画面
→ 显示控制

数秒无操作
→ 自动隐藏
```

不要永久遮挡视频。

---

# 20. Face Call

默认状态。

布局：

```text
Remote Front Camera
=
Full Screen

Local Front Camera
=
PiP
```

用户不需要理解：

```text
video_front
```

UI 只表达：

```text
正常视频通话
```

---

# 21. Show Me

这是 Zisee 最重要的特色模式之一。

入口应该明显但不侵入。

按钮文案候选：

```text
给你看
Show Me
看现场
```

不推荐：

```text
Dual Camera Mode
Concurrent Camera
```

---

# 22. Show Me 布局

默认：

```text
Remote Back Camera
=
Main

Remote Front Camera
=
PiP
```

示意：

```text
┌──────────────────────────┐
│                          │
│      现场主画面           │
│                          │
│                ┌──────┐  │
│                │ 对方 │  │
│                └──────┘  │
└──────────────────────────┘
```

---

# 23. Show Me 主辅切换

用户点击 PiP：

```text
Front
↔
Back
```

主辅切换必须：

- 平滑
- 快
- 不闪黑
- 不产生复杂中间状态

---

# 24. Dual View

可选高级布局。

```text
┌─────────────┬─────────────┐
│             │             │
│    Front    │    Back     │
│             │             │
└─────────────┴─────────────┘
```

不建议默认使用。

因为移动屏幕空间有限。

---

# 25. Unsupported Device

如果设备不支持 Concurrent Camera：

不要显示错误：

```text
ERROR_CONCURRENT_CAMERA_UNSUPPORTED
```

应降级：

```text
该设备不支持同时使用前后摄像头。
你仍可快速切换摄像头。
```

并自动进入普通切换模式。

---

# 26. Screen Share

入口建议放在：

```text
更多
```

或：

```text
协作
```

面板。

启动后远端主画面切换为：

```text
Screen
```

本地/远端摄像头可以保留 PiP。

---

# 27. Collaboration Panel

推荐设计一个统一：

```text
协作
```

面板。

包含：

- 屏幕共享
- 指针
- 圈选
- 箭头
- AR 标记

而不是每个功能散落在不同位置。

---

# 28. 2D Pointer

Remote Pointer 是高频工具。

建议：

- 点击进入
- 手指移动显示 pointer
- 松开后自动消失
- 对方看到同步位置

视觉：

- 小圆点
- 轻微外圈
- 不遮挡目标

---

# 29. Click Ripple

当指导方轻点某处：

现场方看到：

```text
短暂 ripple
```

持续：

```text
~1 秒
```

用于：

```text
“这里”
```

这是比画箭头更低成本的交互。

---

# 30. 2D Annotation

工具：

```text
Pointer
Circle
Arrow
Pen
Undo
Clear
```

第一版 UI 优先：

```text
Pointer
Circle
Arrow
```

Pen 后置。

---

# 31. Annotation Toolbar

建议在进入标注模式后：

```text
底部轻量工具栏
```

不要常驻。

退出标注后完全收起。

---

# 32. AR Assist

入口、双方视角、同时标记、清除权限及功能互斥的细化提案见 [AR 现场协作交互专项设计](AR_INTERACTION.md)（Draft）。下列章节保留基础视觉方向；专项中的分期与现有实现差异需一并阅读，不代表所有交互已经实现。

AR 不应该是一个单独复杂产品。

它应该像：

```text
普通 Show Me
↓
打开 AR
↓
增强现场画面
```

---

# 33. AR 入口

文案建议：

```text
空间标记
```

或：

```text
AR 标记
```

避免：

```text
Spatial Anchor Mode
```

---

# 34. AR Tracking 状态

用户必须知道：

```text
当前是否能够稳定放置标记
```

但提示要轻量。

例如：

```text
正在识别环境…
```

成功：

```text
可放置空间标记
```

Tracking Lost：

```text
请缓慢移动手机以恢复定位
```

---

# 35. AR Marker

基础类型：

```text
Pin
Arrow
Circle
```

视觉应：

- 高对比
- 不遮挡目标
- 有空间感
- 可感知深度
- 不夸张

---

# 36. AR Marker 深度表达

可以使用：

- 轻微阴影
- 透视缩放
- 地面接触提示
- 方向箭头
- 距离变化

但不要做成游戏 UI。

---

# 37. AR 创建流程

远端用户：

```text
进入 AR
↓
点击现实目标
↓
短暂反馈
↓
出现 Anchor
```

不要弹：

```text
请选择 hit test 类型
```

---

# 38. AR 创建失败

例如无有效表面。

不要：

```text
HitTestResult.EMPTY
```

应该：

```text
暂时无法定位这里。
请让对方稍微移动手机后再试。
```

---

# 39. AR Marker 管理

长按或点击 marker：

可以：

- 删除
- 改文字
- 改类型

第一版不需要复杂属性面板。

---

# 40. Measure

AR Measure 后期支持。

设计应：

```text
点 A
↓
点 B
↓
显示距离
```

不要设计成专业 CAD 工具。

---

# 41. More Sheet

“更多”建议使用 Bottom Sheet。

内容候选：

- Show Me
- 屏幕共享
- 协作
- 扬声器
- 视频质量
- 设备
- 通话信息

普通用户第一层只展示常用项。

---

# 42. Video Quality

默认：

```text
自动
```

手动选项可后置：

```text
自动
省流
高清
```

不要让用户选：

```text
H.264
AV1
SVC Layer 2
```

---

# 43. Connection Status

只有连接异常时才强调。

正常情况下：

```text
不显示
```

弱网：

```text
网络较弱
```

重连：

```text
正在重新连接…
```

恢复：

```text
连接已恢复
```

---

# 44. P2P

P2P 是产品能力，但不是主界面技术标签。

可以在：

```text
Call Info
```

里显示：

```text
Direct
Relay
```

不需要日常大字展示。

---

# 45. Call Info

可作为高级信息页。

显示：

```text
连接方式
分辨率
帧率
网络延迟
```

开发版可以增加：

```text
Codec
RTT
Jitter
Packet Loss
Candidate Type
Bitrate
```

---

# 46. Permissions

权限必须上下文触发。

例如：

用户点击视频：

```text
需要摄像头权限
```

用户点击 Screen Share：

```text
需要屏幕共享授权
```

用户进入 AR：

```text
需要摄像头 / AR 能力
```

---

# 47. Permission 文案

禁止：

```text
为了更好的体验，请授予权限
```

应该具体：

```text
Zisee 需要使用摄像头，
才能让对方看到你和你周围的环境。
```

---

# 48. Settings

第一阶段保持简单。

建议：

```text
通话
隐私
通知
关于
```

---

# 49. Call Settings

候选：

- 默认扬声器
- 默认画质
- 双摄默认行为
- 弱网省流
- 镜像本地前摄

---

# 50. Privacy Settings

可展示：

```text
P2P 优先
媒体不保存
```

未来：

- Read receipts
- Diagnostics sharing
- Crash data

---

# 51. Developer Diagnostics

Debug Build 可加入：

```text
Diagnostics
```

普通 Release 不默认暴露。

可以显示：

- ICE State
- Candidate Pair
- Codec
- FPS
- Bitrate
- Packet Loss
- Jitter
- RTT
- Camera capability
- ARCore capability

---

# 52. Empty State

首页无最近联系人时：

不要大量说明。

可以：

```text
还没有最近通话

开始第一次 Zisee 通话
```

---

# 53. Onboarding

第一版不要复杂 5 页 onboarding。

最多：

```text
1~2 屏
```

核心说明：

```text
看见对方
也看见对方所见
```

---

# 54. 首次 Show Me 教学

第一次进入 Show Me 可以轻量提示：

```text
前后摄像头会同时开启。
点击小窗即可切换主视角。
```

一次即可。

---

# 55. 首次 AR 教学

简单三步：

```text
1. 让对方缓慢移动手机
2. 点击你想指出的位置
3. 标记会留在现实空间中
```

不要做长教程。

---

# 56. Accessibility

设计必须考虑：

- 44dp 以上触控区域
- 高对比
- TalkBack
- 图标不能只靠颜色区分
- Mic off / Camera off 必须有明确状态
- 动效可被系统减少动画设置影响

---

# 57. 横屏

视频通话必须支持横屏。

横屏时：

- 视频仍占主导
- 控制移到侧边或底部
- PiP 不遮挡主体
- Show Me 仍然清晰

---

# 58. Foldable / Tablet

不是 MVP 核心。

但布局应避免写死：

```text
phone-only fixed width
```

后续可以：

```text
Main video
+
Side controls
```

---

# 59. 小屏幕

320dp 宽度时：

- 不横向溢出
- Bottom controls 可换行或收纳
- PiP 不宜过大
- 工具栏尽量图标化

---

# 60. 状态反馈

所有关键操作都要立即反馈。

例如：

```text
Mic Off
Camera Off
Show Me On
Screen Share On
AR On
Reconnecting
```

---

# 61. Loading

不要出现全屏 spinner 阻塞通话。

尽量：

```text
局部 loading
```

例如：

```text
正在开启后摄…
```

主通话继续。

---

# 62. Error UX

错误分层：

## 用户可恢复

例如：

```text
摄像头暂时不可用
```

提供：

```text
重试
```

---

## 网络恢复中

例如：

```text
连接不稳定，正在恢复…
```

不要立刻结束通话。

---

## 不可恢复

例如：

```text
通话已结束
```

回到结果页。

---

# 63. 通话结束页

保持简单。

可显示：

```text
通话结束
12:34
```

可选：

```text
再次呼叫
```

不要强制评分。

---

# 64. Design Token 建议

建议建立：

```text
Color
Typography
Spacing
Radius
Elevation
Motion
Icon
```

统一 token。

避免页面各自定义。

---

# 65. Spacing

推荐基于：

```text
4dp / 8dp
```

体系。

常用：

```text
8
12
16
20
24
32
```

---

# 66. Icon

优先：

```text
Material Symbols
```

或统一自定义 icon set。

禁止：

```text
同一界面混用多套不同风格图标
```

---

# 67. Dark Mode

通话页面默认偏深色。

原因：

- 不干扰视频
- 夜间舒适
- 更有沉浸感
- 控件更容易浮在画面上

App 其他页面应支持：

```text
Light
Dark
System
```

---

# 68. 视觉品牌建议

品牌视觉可以围绕：

```text
两个视野靠近
两束视线连接
两个空间叠合
距离被压缩
```

不要直接使用：

```text
摄像机图标
视频通话图标
电话听筒
```

作为唯一品牌核心。

---

# 69. Logo 方向

可以探索：

### 方向 A

两个圆形视野靠近。

### 方向 B

两个取景框重叠。

### 方向 C

抽象 Z + Eye。

### 方向 D

两个空间点通过一条极短距离连接。

要求：

- 图标尺寸小也可识别
- 不复杂
- 不强依赖文字
- 适合 Android adaptive icon

---

# 70. 设计禁区

禁止把产品设计成：

```text
TeamViewer clone
Zoom clone
Discord clone
工业控制台
监控摄像头
AR 游戏
社交短视频
```

---

# 71. 不要过度强调技术

UI 中不要频繁出现：

```text
P2P
ICE
TURN
AV1
WebRTC
ARCore
Pose
Depth
Track
```

用户看到：

```text
直连
正在恢复
空间标记
共享屏幕
```

就够了。

---

# 72. 不要过度功能化

不要做：

```text
首页 20 个功能入口
```

核心任务始终：

```text
发起通话
```

高级功能都在通话过程中自然出现。

---

# 73. 主流程

## 发起通话

```text
Home
↓
选择联系人 / 输入邀请
↓
Outgoing
↓
Connected
↓
Face Call
```

---

## Show Me

```text
Connected
↓
Show Me
↓
Front + Back
↓
Remote sees both
```

---

## Annotation

```text
Connected
↓
Collaboration
↓
Pointer / Circle / Arrow
↓
Remote marker
```

---

## AR

```text
Show Me
↓
AR Assist
↓
现场环境识别
↓
Remote taps
↓
Spatial marker
```

---

# 74. Claude Design 任务要求

当使用 Claude Design 或其他 AI 设计工具时，应让其输出至少：

```text
1. Design concept
2. Visual direction
3. Color system
4. Typography
5. Main components
6. Home
7. Incoming Call
8. In Call
9. Show Me
10. Screen Share
11. Annotation mode
12. AR Assist mode
13. Settings
14. Dark mode
15. Interaction notes
```

---

# 75. Claude Design 不应自行改变产品结构

允许：

- 优化布局
- 优化视觉
- 优化信息层级
- 提出交互建议
- 提出替代方案

不允许在没有明确理由时自行增加：

- Feed
- Stories
- Discover
- Public Room
- Social Profile
- Followers
- Marketplace
- AI Chat
- 社交社区

---

# 76. 设计交付期望

理想交付：

```text
Design System
+
Core Screens
+
Interaction Flow
+
Component States
+
Responsive Notes
```

---

# 77. 首轮设计重点

第一轮不要一次设计所有功能。

优先：

```text
Home
Incoming
Outgoing
Face Call
Show Me
More Sheet
```

第二轮：

```text
Screen Share
Annotation
```

第三轮：

```text
AR Assist
```

---

# 78. MVP UI

首版必须至少完成：

```text
Home
Incoming Call
Outgoing Call
In Call
Settings
```

以及：

```text
Face Call
```

---

# 79. 0.2 UI

加入：

```text
Show Me
Dual Camera
Primary / Secondary
```

---

# 80. 0.3 UI

加入：

```text
Screen Share
Pointer
Circle
Arrow
```

---

# 81. 0.4 UI

加入：

```text
AR Assist
Spatial Marker
Tracking status
```

---

# 82. 设计成功标准

成功的 UI 应让用户：

```text
几乎不用学习就会打电话
```

然后自然发现：

```text
“原来还能同时给你看前后两个视角。”
```

以及：

```text
“原来你可以直接指出我面前的东西。”
```

而不是：

```text
“这个 App 到底这么多按钮都是干什么的？”
```

---

# 83. 核心设计判断

任何设计决策都先问：

> 这个设计是否让用户更容易“看清、指出、理解”远方正在发生的事情？

如果答案不是：

```text
是
```

则应谨慎增加。

---

# 84. 当前设计基线

Zisee UI 当前默认基线：

```text
Android Native
Jetpack Compose
Dark-first Call UI
Video-first
Minimal Controls
Progressive Disclosure
Show Me as Core Differentiator
AR as Optional Enhancement
```

---

# 85. 推荐给 Claude Design 的简短设计 Brief

可直接使用以下摘要：

```text
为 Android 原生 App “Zisee / 咫尺”设计一套现代、克制、以视频内容为核心的 UI。

Zisee 是一个 1v1 高清视频与远程视觉协作应用。它不仅让用户看到对方，还强调“看到对方正在看到的世界”。

核心模式包括：
1. Face Call：普通高清前摄视频通话。
2. Show Me：前后摄同时传输，后摄作为现场主画面，前摄以 PiP 显示。
3. Screen Share：共享 Android 屏幕。
4. Annotation：远端可以通过 pointer、circle、arrow 指出画面位置。
5. AR Assist：远端标记可以通过 ARCore 固定在现实空间中。

设计要求：
- 视频内容优先。
- 通话界面以深色为主。
- 控件轻量、半透明、可自动隐藏。
- 不做工业控制台风格。
- 不做 Zoom / TeamViewer clone。
- 不过度强调 P2P、TURN、WebRTC、ARCore 等技术术语。
- 高级功能通过渐进式交互暴露。
- Show Me 是最重要的产品差异化能力。
- AR 是增强功能，不应压过基础视频通话。
- 整体气质：安静、现代、柔和、清晰、有空间感。
- 品牌：Zisee / 咫尺。
- Slogan：See closer, even from afar.
```

---

# Changelog

## 1.0.1 - 2026-09-10

- 增加 AR 交互专项提案链接，区分基础视觉方向与双端协作的详细设计及实现边界。

## 1.0.0 - 2026-09-08

- 建立 Zisee UI/UX 设计规范。
- 定义品牌与视觉方向。
- 定义核心页面和通话模式。
- 定义 Face Call、Show Me、Screen Share、Annotation、AR Assist 的设计原则。
- 定义权限、错误、状态、横竖屏与可访问性要求。
- 增加 Claude Design 可直接使用的设计 Brief。
