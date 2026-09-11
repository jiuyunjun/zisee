# 咫尺 Android 屏幕共享 + 跨 App 远程标注专项设计

版本：v1.0  
平台：Android  
适用场景：视频通话、远程协助、屏幕共享、跨 App 标注

---

## 1. 目标

实现以下完整远程指导体验：

1. 被指导方开启屏幕共享。
2. 被指导方可以离开咫尺，进入：
   - Chrome
   - 系统设置
   - 相册
   - 第三方 App
   - 其他普通 Android 应用
3. 指导方实时看到被指导方共享的屏幕。
4. 指导方可以在共享画面上：
   - 手绘
   - 圈选
   - 箭头标记
   - 编号标记
   - 指针指示
5. 标注通过 DataChannel 发送给被指导方。
6. 被指导方通过系统 Overlay，将这些标注真正显示在当前 App 上方。
7. 即使咫尺 Activity 已经进入后台，标注与控制菜单仍然存在。
8. 被指导方拥有一个持续可见的悬浮控制菜单，可执行：
   - 画笔
   - 撤销
   - 清除
   - 隐藏/显示标注
   - 暂停共享
   - 停止共享
   - 返回咫尺
9. 普通指导状态下，标注不能妨碍被指导方操作当前 App。

核心效果：

```text
指导方                                被指导方

┌──────────────┐                    ┌──────────────┐
│ Chrome画面   │                    │ Chrome       │
│      ↓       │                    │              │
│     ①        │                    │      ↓       │
│    ○按钮     │                    │     ①        │
│              │                    │    ○按钮     │
└──────────────┘                    │              │
      │                             │       ●      │
      │ AnnotationEvent             │     控制菜单  │
      └────────────────────────────>│              │
                                    └──────────────┘
```

---

# 2. 核心技术架构

整体架构：

```text
                         ┌───────────────────────┐
                         │       指导方          │
                         │                       │
Screen Video ───────────>│ RemoteScreenView      │
                         │ AnnotationController  │
                         └──────────┬────────────┘
                                    │
                             WebRTC DataChannel
                                    │
                          AnnotationEvent
                                    │
                                    ▼
┌──────────────────────────────────────────────────┐
│                 被指导方 Android                 │
│                                                  │
│  ┌────────────────────────────────────────────┐  │
│  │ ScreenShareService                         │  │
│  │                                            │  │
│  │ MediaProjection → WebRTC VideoTrack       │  │
│  └────────────────────────────────────────────┘  │
│                                                  │
│  ┌────────────────────────────────────────────┐  │
│  │ AnnotationEngine                           │  │
│  │                                            │  │
│  │ Annotation State                          │  │
│  │ Undo / Clear / Lifetime                   │  │
│  │ Coordinate Mapping                       │  │
│  └───────────────┬────────────────────────────┘  │
│                  │                               │
│         ┌────────┴─────────┐                     │
│         ▼                  ▼                     │
│ AnnotationOverlay    ControlOverlay              │
│ 全屏标注层           小型交互控制层               │
│                                                  │
└──────────────────────────────────────────────────┘
```

不要采用：

```text
一个 FullScreen Overlay
    ├─ 标注
    ├─ 画笔
    ├─ 菜单
    └─ 所有触摸
```

而采用：

```text
Window A
AnnotationOverlayWindow
全屏
只负责显示标注

Window B
ControlOverlayWindow
很小
只负责菜单交互

Window C
DrawingInputWindow
仅“本地画笔模式”临时出现
负责捕获画笔 Touch
```

这是整个设计最重要的架构决策之一。

---

# 3. 为什么必须拆成多个悬浮 Window

Android 12+ 对 `TYPE_APPLICATION_OVERLAY` 的触摸穿透有安全限制。

`TYPE_APPLICATION_OVERLAY` 不是 trusted window；即使使用 `FLAG_NOT_TOUCHABLE`，覆盖在其他 App 上面的窗口也不能无条件假设触摸一定能穿过去。

系统对 Overlay obscuring opacity 有限制，Android 默认阈值为 `0.8`。

因此不能简单设计：

```text
alpha = 1
全屏 Overlay
FLAG_NOT_TOUCHABLE

↓
假设所有 Touch 都能稳定穿透
```

应设计：

### AnnotationOverlayWindow

职责：

- 显示远端标注
- 不接受触摸
- 不获取焦点
- 尽可能让触摸穿透至下方 App

例如：

```text
TYPE_APPLICATION_OVERLAY

FLAG_NOT_FOCUSABLE
FLAG_NOT_TOUCHABLE
FLAG_LAYOUT_IN_SCREEN
```

Android 12+ 应根据：

```kotlin
InputManager.getMaximumObscuringOpacityForTouch()
```

控制 Window opacity，而不是硬编码假设。

### ControlOverlayWindow

只占：

```text
56dp × 56dp

或者展开：

300dp × 56dp
```

而不是 MATCH_PARENT。

因此只有菜单区域会消费触摸。

其他区域：

```text
手指
 ↓
Annotation Overlay
 ↓
当前 App
```

仍然可以正常操作。

### DrawingInputWindow

只在被指导方主动点击：

> 画笔

之后出现。

这时：

```text
手指
 ↓
DrawingInputWindow
 ↓
画线
```

底层 Chrome / 设置暂时不能操作，这是**有意行为**。

用户点击：

> 完成

后 DrawingInputWindow 立即移除，再恢复触摸穿透。

---

# 4. Overlay 权限

核心 Window：

```text
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
```

需要：

```xml
<uses-permission
    android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
```

`TYPE_APPLICATION_OVERLAY` 从 API 26 起用于普通应用的跨 App Overlay，显示层级高于普通 Activity，但低于部分关键系统窗口。

启动共享之前检查：

```kotlin
Settings.canDrawOverlays(context)
```

没有权限则引导用户进入系统授权。Android API 23+ 需要此能力才能在其他 App 上绘制。

产品层不要把它描述为：

> 悬浮窗权限

建议描述：

> **在其他应用上显示远程指导标记**

解释用户为什么需要它。

---

# 5. 屏幕共享模式

建议完整远程协助优先：

> 整个屏幕共享

而不是：

> 单个 App 共享

因为目标场景是：

```text
设置
 ↓
Chrome
 ↓
相册
 ↓
银行 App
 ↓
系统页面
```

用户会不断切 App。

Android 14 开始，系统默认允许用户在：

```text
整个屏幕
      或
单个 App
```

之间选择。

Android 14 QPR2 的单 App 分享只发送所选应用内容，并排除状态栏、导航栏、通知及其他系统 UI。

因此：

```text
远程指导模式

推荐：
[✓] 共享整个屏幕

不推荐：
[ ] 仅共享某个 App
```

但是不能假定应用能够永久强迫用户选择整个屏幕。系统出于隐私原因保留用户控制权。

---

# 6. ScreenShareService

屏幕共享生命周期不能依赖 Activity。

采用：

```text
ScreenShareService
ForegroundService
```

负责：

```text
MediaProjection
VirtualDisplay
WebRTC ScreenCapturer
OverlayController
AnnotationChannel
SessionState
```

Android 对 MediaProjection 要求使用：

```text
foregroundServiceType="mediaProjection"
```

并声明相应 foreground service 权限。

逻辑：

```text
ZiseeActivity

用户点击：
开始共享
    ↓
MediaProjection permission
    ↓
ScreenShareService.start()
    ↓
Activity 可以进入后台
    ↓
Service 持续：
    ├─ Screen Capture
    ├─ WebRTC
    ├─ Annotation
    └─ Overlay
```

所以：

```text
打开咫尺
 ↓
开始共享
 ↓
Home
 ↓
打开系统设置
 ↓
咫尺 Activity STOPPED

ScreenShareService
仍然 Running
```

---

# 7. Android 14 MediaProjection 生命周期

Android 14+ 不能缓存一次共享授权然后无限重用。

针对 targetSdk 34+：

**每次新的 MediaProjection 捕获 Session 都需要用户重新同意。**

同一个 `MediaProjection` 实例也不能反复调用 `createVirtualDisplay()` 创建新的捕获 Session。

因此不要：

```text
第一次授权
 ↓
缓存 Intent
 ↓
以后静默重新共享
```

而应该：

```text
Session 1
用户授权
 ↓
共享
 ↓
Stop

Session 2
重新请求授权
```

屏幕旋转时也不要重新创建新的 MediaProjection Session。

应该：

```text
VirtualDisplay.resize()

+

VirtualDisplay.setSurface()
```

处理方向变化。

---

# 8. Annotation Engine

所有标注统一抽象：

```kotlin
sealed interface Annotation {
    val id: String
    val actorId: String
    val timestamp: Long
}
```

类型：

```text
FreehandStroke
Circle
Arrow
NumberMarker
Pointer
Text
Rectangle
```

第一期建议实现：

```text
P0

✓ Pointer
✓ Freehand
✓ Circle
✓ Arrow
✓ Number Marker
```

其中 Number Marker 就使用之前设计：

```text
     ↓
    ③
```

或者：

```text
      ↓
    ┌───┐
    │ 3 │
    └───┘
```

递增：

```text
① → ② → ③ → ④
```

用于远程指导步骤非常有效。

---

# 9. 坐标系统

绝对不能发送：

```json
{
  "x": 723,
  "y": 1388
}
```

因为指导方看到的画面可能：

```text
720 × 1280

1080 × 2400

1344 × 2992

横屏

折叠屏

Letterbox
```

统一使用：

```text
Normalized Coordinates

x ∈ [0,1]
y ∈ [0,1]
```

例如：

```json
{
  "x": 0.532,
  "y": 0.287
}
```

实际映射：

```text
screenX = contentLeft + x × contentWidth
screenY = contentTop  + y × contentHeight
```

---

# 10. ContentRect

仅仅 normalized coordinate 还不够。

指导方的视频可能这样：

```text
┌───────────────────┐
│                   │
│ ┌───────────────┐ │
│ │               │ │
│ │ Remote Screen │ │
│ │               │ │
│ └───────────────┘ │
│                   │
└───────────────────┘
```

存在：

```text
FIT_CENTER
Letterbox
Rotation
Crop
```

因此指导方触摸：

```text
View coordinate
 ↓
Video contentRect
 ↓
Normalized Screen Coordinate
 ↓
AnnotationEvent
```

不能按整个 `RemoteVideoView` 计算。

---

# 11. 屏幕旋转

必须处理：

```text
Portrait
 ↓
Landscape
 ↓
Portrait
```

Android 14 提供：

```text
MediaProjection.Callback
.onCapturedContentResize()
```

用于知道捕获区域尺寸变化。

设计：

```text
CapturedContentResize
      ↓
ProjectionGeometryManager
      ↓
更新：
captureWidth
captureHeight
rotation
contentRect
      ↓
通知指导方
```

Annotation Event 应携带：

```json
{
  "geometryRevision": 37
}
```

防止：

```text
指导方仍按照旧 Portrait 坐标画
↓
被指导方已经 Landscape
↓
标记错位
```

---

# 12. Annotation Event

推荐 DataChannel：

```text
WebRTC VideoTrack
→ 屏幕像素

WebRTC DataChannel
→ AnnotationEvent
```

不要把远程标注直接烧进视频。

例如：

```json
{
  "type": "ANNOTATION_ADD",
  "annotation": {
    "id": "a83d...",
    "kind": "ARROW",
    "actorId": "guide-01",
    "geometryRevision": 31,
    "x": 0.5321,
    "y": 0.2788,
    "direction": "DOWN"
  }
}
```

手绘：

```json
{
  "type": "STROKE_APPEND",
  "id": "stroke-382",
  "points": [
    [0.31, 0.43],
    [0.32, 0.44],
    [0.33, 0.45]
  ]
}
```

不要一个 MotionEvent 发一个网络包。

应该：

```text
Touch Sampling
120Hz

↓ aggregation

20～30Hz DataChannel batch
```

接收端插值重建。

---

# 13. Annotation 状态模型

采用 Operation 模式：

```text
ADD
UPDATE
REMOVE
UNDO
CLEAR
```

不要单纯每次：

```text
传整个 AnnotationList
```

基本状态：

```kotlin
AnnotationState(
    revision,
    annotations,
    localHistory,
    remoteHistory
)
```

---

# 14. Undo 的语义

这个地方很容易设计错。

指导方：

```text
画 A
画 B
```

被指导方：

```text
画 C
```

这时被指导方点击：

> 撤销

不应该删除指导方的 B。

推荐：

> Undo = 撤销当前操作者自己的最后一步操作

即：

```text
Guide Undo
→ Undo Guide

Client Undo
→ Undo Client
```

数据：

```text
actorId
```

决定自己的历史栈。

---

# 15. Clear 的语义

“清除”比 Undo 危险，所以不要按钮点一下直接全删。

点击：

```text
清除
```

弹出小菜单：

```text
┌──────────────────┐
│ 清除我的标注     │
│ 清除全部标注     │
│ 取消             │
└──────────────────┘
```

其中：

### 清除我的标注

只删：

```text
actorId == self
```

### 清除全部

发送：

```json
{
  "type": "CLEAR_ALL",
  "revision": 42
}
```

然后双方全部清除。

---

# 16. 悬浮控制菜单

这是被指导方后台操作的核心 UI。

## 收起状态

默认不要一直显示一排工具。

建议：

```text
                         ┌────┐
                         │ ●  │
                         └────┘
```

大小：

```text
48～56dp
```

表示：

```text
绿色 ●
正在共享

黄色 ●
连接较差

红色 ●
共享异常
```

可以拖拽。

松手以后自动吸附：

```text
左边缘
或
右边缘
```

类似 Messenger Bubble。

---

# 17. 展开状态

点击悬浮按钮：

```text
┌─────────────────────────────────────┐
│ ✎   ↶   ⌫   ◉   ▌▌   ■            │
│画笔 撤销 清除 标注 暂停 停止共享    │
└─────────────────────────────────────┘
```

第一版建议：

| Button | 功能 |
|---|---|
| ✎ | 本地画笔 |
| ↶ | 撤销 |
| ⌫ | 清除 |
| ◉ | 显示/隐藏标注 |
| ▌▌ | 暂停发送画面 |
| ■ | 停止共享 |

另加：

```text
⋮
```

进入次级菜单：

```text
返回咫尺
共享状态
网络状态
画质
结束通话
```

---

# 18. 为什么“停止共享”和“结束通话”要分开

不要把：

> Stop Share

等同：

> Hang Up

用户可能只是：

```text
停止屏幕共享
↓
继续语音/视频聊天
```

状态应该独立：

```text
Call
├─ Audio
├─ Camera
└─ ScreenShare
```

停止共享：

```text
MediaProjection.stop()

VideoCall
仍然 Running
```

结束通话才：

```text
PeerConnection.close()
```

---

# 19. 暂停共享

建议增加：

> 暂停

而不是只有 Stop。

暂停后：

```text
MediaProjection Session
尽量不销毁

↓
停止向 Encoder 提交新画面

↓
指导方显示

“对方已暂停屏幕共享”
```

这样恢复时不需要重新走完整业务流程。

不过 Android 系统如果真正终止 `MediaProjection`，Android 14+ 再启动新的捕获 Session 就需要重新获取用户许可。

因此定义：

```text
Pause ≠ MediaProjection.stop()

Stop = MediaProjection.stop()
```

---

# 20. 本地画笔模式

用户点击：

```text
✎
```

进入：

```text
DRAWING
```

UI：

```text
┌──────────────────────────┐
│                          │
│         Chrome           │
│                          │
│     用户手指画           │
│        ╲                 │
│         ╲                │
│                          │
│   ┌──────────────────┐   │
│   │ ●  ━  ↶   ✓     │   │
│   │颜色 粗细 撤销 完成│   │
│   └──────────────────┘   │
└──────────────────────────┘
```

此时启用：

```text
DrawingInputWindow
MATCH_PARENT
Touchable
```

底层 App 暂时禁止触摸。

顶部/底部显示明显提示：

> **正在标注 · 完成后可继续操作手机**

避免用户疑惑：

> 为什么 Chrome 点不了了？

---

# 21. 画笔菜单

点击画笔后显示二级 Palette：

```text
┌──────────────────────────────┐
│ ●  ●  ●  ● │ ━ ━ ━ │ 橡皮 │
└──────────────────────────────┘
```

建议：

颜色：

```text
Red
Yellow
Green
Blue
White
```

粗细：

```text
3dp
6dp
10dp
```

但发送数据不要发送 dp：

```text
widthNormalized
```

例如：

```text
0.004
```

避免不同 DPI 显示粗细差距巨大。

---

# 22. 指导方工具栏

双方 Annotation Engine 应共享。

指导方 UI 推荐：

```text
Pointer
Pen
Circle
Arrow
Number
Undo
Clear
```

其中默认：

```text
Pointer
```

点击一下产生短生命周期视觉反馈：

```text
      ◎
    ◎   ◎
      ◎

300ms → 800ms → fade
```

这种比永久画一堆圈更适合：

> 点这里。

---

# 23. 标注生命周期

每个 Annotation 增加：

```text
PERSISTENT
TEMPORARY
```

### Pointer

```text
TTL ≈ 1～2 秒
```

自动消失。

### Arrow

默认：

```text
Persistent
```

直到：

```text
Undo
Clear
```

### Number Marker

Persistent。

### Freehand

Persistent。

这样不会让每一次点击都永久污染屏幕。

---

# 24. 推荐悬浮菜单 UX

最终建议设计成：

```text
收起：

                    ●

点击：

         ┌──────────────────────────┐
         │ ✎  ↶  ⌫  ◉  ▌▌  ■  ⋮ │
         └──────────────────────────┘
```

三秒无操作：

```text
自动收起
```

但是：

```text
正在 DRAWING
```

时不自动收起。

菜单支持：

```text
Drag
Edge Snap
Safe Area
Landscape reposition
IME avoidance
```

---

# 25. Overlay Window 结构

最终 Window 层级：

```text
System critical UI
━━━━━━━━━━━━━━━━━━━━━━━━━━

ControlOverlayWindow
TYPE_APPLICATION_OVERLAY
Touchable
只占菜单区域

DrawingInputWindow
TYPE_APPLICATION_OVERLAY
Touchable
只有 DRAW 模式存在

AnnotationOverlayWindow
TYPE_APPLICATION_OVERLAY
NOT_TOUCHABLE
全屏

━━━━━━━━━━━━━━━━━━━━━━━━━━

Chrome / Settings / Gallery
普通 Activity
```

---

# 26. 推荐类设计

```text
ScreenShareService
│
├── MediaProjectionController
│   ├── start()
│   ├── pause()
│   ├── resume()
│   └── stop()
│
├── OverlayController
│   ├── AnnotationOverlayWindow
│   ├── ControlOverlayWindow
│   └── DrawingInputWindow
│
├── AnnotationEngine
│   ├── AnnotationRepository
│   ├── AnnotationHistory
│   ├── CoordinateMapper
│   └── AnnotationRenderer
│
├── AnnotationTransport
│   └── WebRTCDataChannel
│
├── ProjectionGeometryManager
│
└── SessionStateMachine
```

---

# 27. State Machine

建议统一：

```text
IDLE
 │
 ▼
REQUESTING_PERMISSION
 │
 ▼
SHARING
 │
 ├────> DRAWING
 │        │
 │        └────> SHARING
 │
 ├────> PAUSED
 │        │
 │        └────> SHARING
 │
 └────> STOPPING
          │
          ▼
         IDLE
```

Overlay 生命周期必须跟 Session，而不是 Activity：

```text
SHARING
→ Overlay visible

DRAWING
→ Overlay + DrawingInput visible

PAUSED
→ Control visible
→ Annotation optional

IDLE
→ remove all Overlay
```

---

# 28. Stop Sharing

这是危险操作，按钮应该醒目但避免误触。

用户点击：

```text
■
```

显示：

```text
┌────────────────────────┐
│ 停止屏幕共享？         │
│                        │
│ 通话不会结束。         │
│                        │
│ [取消]   [停止共享]    │
└────────────────────────┘
```

确认后顺序：

```text
1. AnnotationTransport
   send SCREEN_SHARE_STOPPING

2. remove DrawingInputWindow

3. remove AnnotationOverlayWindow

4. remove ControlOverlayWindow

5. VirtualDisplay.release()

6. MediaProjection.stop()

7. Screen video track stop

8. update call state

9. 指导方：
   显示“对方已停止共享”
```

---

# 29. MediaProjection.onStop()

必须实现：

```text
MediaProjection.Callback.onStop()
```

因为共享并不一定是你自己的 Stop 按钮结束。

用户可能通过：

```text
系统状态栏
系统隐私面板
其他机制
```

结束共享。

回调中必须：

```text
release VirtualDisplay
release Surface
stop VideoTrack
remove Overlay
update SessionState
notify Remote
```

Android 官方也要求应用正确处理该 callback 并释放相关资源。

---

# 30. 特殊 App 限制

这里必须明确：

> “可以显示在其他 App 上”不等于“100% 所有 App”。

Android 12+ 允许某些 App 使用：

```text
HIDE_OVERLAY_WINDOWS
```

阻止非系统 Overlay 出现在自己的窗口上。

例如某些：

```text
银行
支付
密码
安全认证
敏感确认
```

界面可能主动隐藏咫尺 Overlay。

这不是 Bug。

此时 UI 应提示：

> 当前应用出于安全原因禁止显示指导标记。

不要尝试用 AccessibilityService 等方式规避这种安全策略。

---

# 31. Secure Screen

另外某些 App 使用：

```text
FLAG_SECURE
```

这种内容可以阻止截图或出现在非安全 Display / Screen Share 中。

因此可能出现：

```text
被指导方：
银行 App 正常显示

指导方：
黑屏 / 安全占位区域
```

同样属于 Android 安全机制。

产品上应显示：

> 当前页面受到系统安全保护，无法共享。

而不是显示：

> 网络异常

---

# 32. Remote Annotation 与视频回传重复问题

如果整个 Display capture 最终把 Overlay 也捕获回来，指导方可能看到：

```text
视频里的 Annotation
+
指导方本地 Annotation Renderer
```

造成：

```text
双重箭头
双重圈
```

因此指导方渲染策略必须明确。

推荐：

### 指导方

自己绘制中的实时 Stroke：

```text
Local Prediction Renderer
```

用于低延迟反馈。

### 收到被指导方 ACK 后

如果确认共享视频已经包含 Annotation：

```text
避免再次永久叠加
```

但是 Overlay 是否进入不同 Android/OEM/共享模式的 capture path 不应该成为核心协议依赖。

更稳的方案是：

```text
视频 = Screen Content

Annotation = 独立逻辑层
```

指导方和被指导方都通过 AnnotationEngine 渲染。

即：

```text
业务真值：

AnnotationState

而不是：

“视频像素里有没有那个红圈”
```

---

# 33. Annotation ACK

推荐：

```text
Guide
 │
 │ ADD #123
 ▼
Client
 │
 │ ACK #123
 ▼
Guide
```

延迟反馈：

```text
发送
→ local immediate preview

ACK
→ confirmed

timeout
→ network warning
```

可实现非常顺滑的指导体验。

---

# 34. 网络恢复

DataChannel 断线重新连接后不能只继续发送新事件。

必须：

```text
ANNOTATION_STATE_SYNC
```

例如：

```json
{
  "type": "STATE_SYNC",
  "revision": 837,
  "annotations": [...]
}
```

恢复：

```text
Wi-Fi
 ↓
5G
 ↓
WebRTC reconnect
 ↓
AnnotationState resync
```

否则可能双方看到完全不同的标注。

---

# 35. 模块关系

推荐最终模块：

```text
              ZiseeCallSession
                     │
          ┌──────────┴──────────┐
          │                     │
       WebRTC                ScreenShare
          │                     │
          │              MediaProjection
          │                     │
          └──────┬──────────────┘
                 │
          AnnotationSession
                 │
       ┌─────────┼─────────┐
       │         │         │
    Engine    Transport   Overlay
       │                   │
       │          ┌────────┼────────┐
       │          │        │        │
       │       Renderer   Menu     Input
       │
       └── CoordinateMapper
```

这样将来 Camera AR 标注也可以直接复用：

```text
AnnotationEngine
      │
      ├─ Screen Overlay Renderer
      ├─ Remote Video Renderer
      └─ ARCore Renderer
```

这点非常值得提前统一。

---

# 36. 与 AR 标注系统统一

之前 ARCore 已经需要：

```text
Arrow
Circle
Number
Freehand
```

所以不要另外造一套 Screen Annotation。

统一 Domain：

```text
Annotation
├── SCREEN_2D
└── WORLD_3D
```

Screen：

```text
NormalizedCoordinate(x,y)
```

AR：

```text
WorldPose
Anchor
```

Renderer 决定：

```text
ScreenAnnotationRenderer

或者

ARAnnotationRenderer
```

未来指导方可以选择：

```text
2D 标记
→ 固定在屏幕位置

AR 标记
→ 固定在现实世界物体位置
```

而 UX 和 Annotation 类型保持一致。

---

# 37. P0 实现范围

第一阶段不要一次做太复杂。

建议：

### Screen Share

- MediaProjection
- ForegroundService
- WebRTC ScreenTrack
- 横竖屏

### Overlay

- AnnotationOverlayWindow
- ControlOverlayWindow
- DrawingInputWindow

### Annotation

- Pointer
- Pen
- Circle
- Arrow
- Number

### Control Menu

```text
✎ 画笔
↶ 撤销
⌫ 清除
◉ 显示/隐藏
▌▌ 暂停
■ 停止
```

### Protocol

- ADD
- REMOVE
- UNDO
- CLEAR
- ACK
- STATE_SYNC

这已经能够形成完整产品体验。

---

# 38. P1

第二阶段再增加：

```text
文字标注
橡皮擦
颜色
线宽
多人 Annotation
激光笔
自动 Fade
截图
标注历史
网络断线恢复
```

---

# 39. P2

最后再进入：

```text
Screen → AR 转换

Object Tracking
Feature Tracking
OCR Anchor
UI Element Detection
```

例如指导方圈：

```text
“设置”
```

系统自动 OCR / Vision 检测 UI：

```text
设置按钮
```

即使屏幕轻微滚动：

```text
箭头仍然跟随目标
```

这可以作为咫尺之后非常强的差异化功能。

---

# 40. 最终推荐交互

我建议最后做成这种体验：

```text
被指导方开启共享

                    ●
                 屏幕共享中
```

指导方：

```text
“点这个设置按钮”
        ↓
       ①
      ○
```

被指导方手机上立即：

```text
设置 App

Wi-Fi
Bluetooth
────────────
隐私      ← ○
             ↓
             ①


                    ●
```

被指导方可以直接点击：

```text
隐私
```

因为默认 Annotation Layer：

```text
Touch-through
```

如果被指导方自己想标：

```text
●
↓
展开
↓
✎
```

进入：

```text
DRAW MODE
```

画完：

```text
✓ 完成
```

立即恢复正常手机操作。

---

# 41. 最终架构结论

这套功能不要理解为：

> MediaProjection 上面画几个 Canvas。

它应该成为一个独立的：

```text
Remote Guidance System
```

核心由四个部分组成：

```text
① MediaProjection
   负责“看见”

② WebRTC DataChannel
   负责“传递指导动作”

③ AnnotationEngine
   负责“理解和同步标注”

④ TYPE_APPLICATION_OVERLAY
   负责“把指导真正显示在对方当前手机界面”
```

其中被指导方 Overlay 最终采用：

```text
AnnotationOverlayWindow
        +
ControlOverlayWindow
        +
DrawingInputWindow
```

而不是一个大悬浮窗。

控制菜单第一版确定为：

```text
┌───────────────────────────────┐
│ ✎    ↶    ⌫    ◉    ▌▌    ■ │
│画笔  撤销  清除  标注  暂停  停止│
└───────────────────────────────┘
```

默认收起为：

```text
●
```

这套结构既能满足当前的屏幕共享远程指导，也能直接作为以后 **ARCore 3D 标注、OCR UI 识别、远程操作指导** 的基础 Annotation Framework。