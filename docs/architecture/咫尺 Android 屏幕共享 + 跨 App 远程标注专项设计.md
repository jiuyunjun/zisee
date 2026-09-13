# 咫尺 Android 屏幕共享 + 跨 App 远程标注专项设计

版本：v1.1
平台：Android  
适用场景：视频通话、远程协助、屏幕共享、跨 App 标注

> 2026-09-12 可行性评估与实现：普通应用的全屏共享 + 跨 App 标注可实现；已接入本文 P0 主流程。复用现有 `CallForegroundService`、进程持有的 `CallViewModel` / `NativeRtcSession` 和 `ScreenShareSession`，不再创建竞争的第二个投屏服务。代码和验收边界见 [屏幕指导交接](../development/SCREEN_GUIDANCE_HANDOFF.md)。
>
> 平台约束修正：第三方 App 的 `FLAG_SECURE` / `HIDE_OVERLAY_WINDOWS` 没有供本应用可靠查询的通用接口，不能根据黑屏推断其原因或保证显示本文第 31/32 节的精确提示。本版在权限说明中告知限制，不绕过安全窗口。全屏投影也没有可依赖的公共 API 来排除自身 Overlay；第 33 节“纯内容视频”是目标而非当前保证，ACK 只代表逻辑接受，不代表标注已出现在某一视频帧。
>
> 当前协议使用有界二进制 `screen-guidance-v1`（可靠有序 DataChannel id=6）。字段语义对应本文示例，复用现有 `VideoPoint` / `AnnotationAuthor`；屏幕归一化坐标与 AR 世界坐标继续分离。PUT 合并 ADD/UPDATE，手绘每 40ms 最多更新一次当前路径，单笔最多 128 点、总计 32 笔。只在首次同步、状态/几何变化、重连或丢失增量时发送完整快照。尺寸/旋转变更清空旧标注；输入携带开始绘制时所属会话和几何版本。
>
> 指导方默认“浏览”，选择工具后绘制；默认只显示即时绘制预览，避免全屏视频中的标注被永久重复叠加。若设备未回传 Overlay，可手动开启“本地叠加”。横屏展开菜单可滚动，具备拖动、边缘吸附和安全边距；自动 IME 避让及按网络质量细分颜色仍待设备 UX 完善。视频与 DataChannel 无逐帧几何绑定，当前采用宽高比校验及几何稳定等待，不能宣称消除了旋转瞬间所有旧视频帧的误标风险。

> 2026-09-13 v1.1 设计更新：新增“UI 语义高亮”能力。通过 `AccessibilityService` 读取 Accessibility Tree（不是原始 View Tree），将指导方在共享画面上的点击解析为 `AccessibilityNodeInfo` 语义目标，并在被指导方当前窗口上动态高亮。默认不上传完整 UI Tree；优先在被指导端本地解析目标，仅同步目标 ID、几何、能力与必要的最小语义元数据。无法获取节点时回退到 OCR/Vision，再回退到普通坐标标注。该能力只负责识别/高亮，不默认执行远程点击。
>
> 2026-09-13 v1.1 第一阶段实现：已接入 Accessibility 授权入口、当前 active window 命中、可点击祖先提升、`TYPE_ACCESSIBILITY_OVERLAY` 边框高亮、滚动/窗口变化后的 60ms 防抖重定位、`UI_TARGET_*` 协议、敏感文本过滤及普通 Pointer 回退。P0 包保持原 wire version，旧端仍可使用普通标注。OCR/Vision、窗口绑定 SurfaceControl 高亮、远程操作仍未实现。

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
   - 点击/悬停某个真实 UI 元素并让指针自动吸附到该元素
   - 将目标 UI 以边框、聚光灯、箭头或编号方式持续高亮
5. 标注与 UI Target 事件通过 DataChannel 发送给被指导方。
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
│  │ Annotation State / Undo / Lifetime        │  │
│  │ Coordinate Mapping                       │  │
│  └───────────────┬────────────────────────────┘  │
│                  │                               │
│  ┌───────────────▼────────────────────────────┐  │
│  │ UiSemanticEngine                          │  │
│  │ Accessibility Tree → Target Resolver     │  │
│  │ Node Tracking / Snap / Highlight         │  │
│  └───────────────┬────────────────────────────┘  │
│                  │                               │
│      ┌───────────┼──────────────┐                │
│      ▼           ▼              ▼                │
│ Annotation   SemanticHighlight  ControlOverlay   │
│ Overlay      Overlay            小型交互控制层    │
│ 全屏标注层    UI 语义高亮层                        │
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

| Button | 功能          |
| ------ | ------------- |
| ✎      | 本地画笔      |
| ↶      | 撤销          |
| ⌫      | 清除          |
| ◉      | 显示/隐藏标注 |
| ▌▌     | 暂停发送画面  |
| ■      | 停止共享      |

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

# 23. UI 语义高亮（Accessibility Tree）

这是 v1.1 新增的核心能力。

目标不是继续把所有指导动作都理解为：

```text
“在屏幕坐标 (x, y) 画一个圈”
```

而是尽量升级为：

```text
“指导方指的是当前窗口中的某个真实 UI 元素”
```

例如：

```text
指导方点击共享画面中的「Wi‑Fi」
        ↓
被指导端收到 normalized point
        ↓
UiSemanticEngine 查询 Accessibility Tree
        ↓
找到 AccessibilityNodeInfo
        ↓
解析出目标 bounds / windowId / class / viewId / actions
        ↓
高亮整个 Wi‑Fi 行，而不是只显示一个像素坐标圆点
```

必须明确：Accessibility Tree **不是原始 View Tree**，节点与真实 View 层级不保证 1:1；窗口内容也会随时变化，因此 `AccessibilityNodeInfo` 只能视为某一时刻的语义快照，不能长期持有并假定永远有效。

---

## 23.1 能力边界

UI 语义高亮依赖：

```text
AccessibilityService
+
canRetrieveWindowContent=true
```

并建议启用：

```text
FLAG_RETRIEVE_INTERACTIVE_WINDOWS
FLAG_REPORT_VIEW_IDS
```

用途分别为：

```text
FLAG_RETRIEVE_INTERACTIVE_WINDOWS
→ 获取当前可交互窗口集合

FLAG_REPORT_VIEW_IDS
→ 尽可能获取 package:id/name 形式的 viewIdResourceName
```

服务可从：

```text
rootInActiveWindow
getWindows()
AccessibilityEvent.getSource()
```

获取节点树或变化来源。

不要把该能力描述成：

> “读取任何 App 的完整内部 UI。”

更准确的定义是：

> “读取目标 App 向 Android Accessibility 框架暴露的可访问性语义结构。”

---

## 23.2 默认隐私模型：本地解析，不上传整棵 UI Tree

第一版不要持续把完整 Accessibility Tree 发送给指导方。

推荐：

```text
指导方
点击共享视频中的某一点
      ↓
UI_TARGET_REQUEST
      ↓
被指导方本地 UiTargetResolver
      ↓
Accessibility Tree
      ↓
找到目标节点
      ↓
只返回目标结果
```

因此默认网络侧不需要出现：

```text
整页 text
整页 contentDescription
所有输入框内容
完整 hierarchy dump
```

这样可以显著降低：

```text
隐私暴露
协议体积
敏感信息泄漏
Tree 版本同步复杂度
```

只有未来确实需要“指导端语义浏览器”时，才设计显式的 `SEMANTIC_TREE_SNAPSHOT`，并单独加入用户授权与字段过滤。

---

## 23.3 AccessibilityService 配置

建议独立：

```text
ZiseeGuidanceAccessibilityService
```

职责只包含：

```text
读取当前可交互窗口语义
目标节点解析
目标跟踪
Accessibility Overlay 高亮
```

不要把 MediaProjection、WebRTC 或通话生命周期塞进 AccessibilityService。

示意配置：

```xml
<accessibility-service
    android:canRetrieveWindowContent="true"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="40" />
```

运行时根据需要设置：

```kotlin
serviceInfo = serviceInfo.apply {
    flags = flags or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
}
```

事件范围必须控制，不要订阅所有事件后无差别 dump 整棵树。

---

## 23.4 指导方点击如何解析成真实 UI

指导方仍然只操作共享视频。

第一步继续复用现有坐标系统：

```text
RemoteScreenView touch
      ↓
contentRect
      ↓
normalized screen coordinate
      ↓
UI_TARGET_REQUEST
```

协议示例：

```json
{
  "type": "UI_TARGET_REQUEST",
  "requestId": "req-97",
  "geometryRevision": 52,
  "x": 0.5372,
  "y": 0.2811,
  "mode": "SNAP"
}
```

被指导方：

```text
normalized point
      ↓
ProjectionGeometryManager
      ↓
screen coordinate
      ↓
UiTargetResolver
      ↓
当前 Accessibility Window
      ↓
命中候选 AccessibilityNodeInfo
```

不要让指导端自己根据旧 Tree 猜目标。

**目标解析权放在被指导端**，因为只有被指导端最接近当前真实窗口状态。

---

## 23.5 Node 命中算法

给定屏幕点：

```text
P(x, y)
```

先收集：

```text
bounds.contains(P)
```

的候选节点。

过滤：

```text
visibleToUser
bounds 非空
bounds 与当前可交互 window 相交
不是纯装饰性巨大根节点
```

评分建议：

```text
score =
    + clickable / checkable / focusable
    + importantForAccessibility
    + 有 viewIdResourceName
    + 有 text / contentDescription / role
    + point 靠近节点中心
    + bounds 面积较小且合理
    - 覆盖整个窗口的大容器
    - 不可见
    - disabled
```

优先选择：

```text
最具体、可交互、可解释的节点
```

而不是简单选择 Tree 最深节点。

例如：

```text
RecyclerView Row        clickable=true
 ├─ Icon                clickable=false
 └─ Text "Wi‑Fi"        clickable=false
```

指导方点到文字时，最终目标更可能应该提升为：

```text
RecyclerView Row
```

因为它才是真正可点击区域。

---

## 23.6 SemanticTarget 数据模型

不要把 `AccessibilityNodeInfo` 本体跨线程、跨进程或跨网络持久化。

统一转换为：

```kotlin
data class SemanticTarget(
    val targetId: String,
    val packageName: String?,
    val windowId: Int,
    val className: String?,
    val viewIdResourceName: String?,
    val role: UiRole,
    val bounds: NormalizedRect,
    val clickable: Boolean,
    val enabled: Boolean,
    val actionMask: Long,
    val locator: NodeLocator,
    val confidence: Float,
    val treeRevision: Long,
)
```

其中 `locator` 用于后续重新寻找节点，而不是保存旧 Node 对象。

推荐：

```kotlin
data class NodeLocator(
    val windowId: Int,
    val viewIdResourceName: String?,
    val className: String?,
    val textHash: String?,
    val contentDescriptionHash: String?,
    val hierarchyHint: IntArray?,
    val lastBounds: NormalizedRect,
)
```

注意：

```text
textHash / contentDescriptionHash
```

只用于本地重定位匹配时可以保留明文；如果要发往远端，默认优先 hash 或省略原文。

密码字段、敏感输入框默认永远不传其文本内容。

---

## 23.7 UI 高亮协议

建议新增：

```text
UI_TARGET_REQUEST
UI_TARGET_RESOLVED
UI_TARGET_UPDATE
UI_TARGET_LOST
UI_HIGHLIGHT_CLEAR
```

成功解析：

```json
{
  "type": "UI_TARGET_RESOLVED",
  "requestId": "req-97",
  "targetId": "ui-183",
  "treeRevision": 911,
  "windowId": 42,
  "role": "BUTTON",
  "bounds": [0.041, 0.263, 0.962, 0.331],
  "clickable": true,
  "confidence": 0.94
}
```

指导方收到后：

```text
原始 Pointer Preview
      ↓
吸附动画
      ↓
整个 UI bounds 高亮
```

如果没有找到：

```json
{
  "type": "UI_TARGET_LOST",
  "requestId": "req-97",
  "reason": "NO_ACCESSIBILITY_NODE"
}
```

指导方立即回退普通 Pointer / Circle，不阻断远程指导。

---

## 23.8 高亮渲染

语义高亮建议与普通 Annotation 分离：

```text
Annotation
→ 用户创建的指导内容

SemanticHighlight
→ 系统根据真实 UI 节点自动维护的瞬时/跟踪内容
```

推荐视觉：

```text
┌─────────────────────────────┐
│                             │
│  ╔═══════════════════════╗  │
│  ║        Wi‑Fi          ║  │
│  ╚═══════════════════════╝  │
│              ↑              │
│           请点这里          │
└─────────────────────────────┘
```

支持：

```text
OUTLINE       边框
SPOTLIGHT     周围压暗
ARROW         箭头
NUMBER        步骤编号
PULSE         轻微脉冲
```

默认推荐：

```text
OUTLINE + PULSE
```

避免大面积不透明遮罩影响底层 App 操作。

---

## 23.9 Accessibility Overlay 策略

当 AccessibilityService 已启用时，语义高亮优先使用：

```text
TYPE_ACCESSIBILITY_OVERLAY
```

而不是继续把所有语义高亮都塞进普通 `TYPE_APPLICATION_OVERLAY`。

API 34+ 优先：

```text
AccessibilityNodeInfo.getBoundsInWindow()
+
AccessibilityService.attachAccessibilityOverlayToWindow()
```

这样高亮与目标 window 建立更直接的坐标关系。

窗口：

```text
移动
分屏 resize
freeform resize
```

时更容易保持一致。

API < 34：

```text
AccessibilityNodeInfo.getBoundsInScreen()
+
TYPE_ACCESSIBILITY_OVERLAY
```

按屏幕坐标布局。

如果 AccessibilityService 未启用：

```text
无法做 Semantic Highlight
↓
退化为现有 AnnotationOverlayWindow + normalized coordinate
```

Accessibility Overlay 不能被当作规避 `FLAG_SECURE`、安全窗口或其他系统保护策略的手段。

---

## 23.10 节点跟踪

高亮 UI 不能只解析一次。

例如：

```text
目标：Wi‑Fi
用户滚动
↓
Wi‑Fi bounds 改变
```

需要监听：

```text
TYPE_WINDOW_CONTENT_CHANGED
TYPE_VIEW_SCROLLED
TYPE_WINDOWS_CHANGED
TYPE_WINDOW_STATE_CHANGED
```

当存在 active SemanticTarget 时：

```text
AccessibilityEvent
      ↓
UiTargetTracker
      ↓
根据 locator 重新 resolve
      ↓
新 bounds
      ↓
UI_TARGET_UPDATE
      ↓
Overlay 平滑移动
```

不要在每个 AccessibilityEvent 后无脑完整遍历全树。

策略：

```text
优先 event.source / affected subtree
找不到再查 root
40~80ms debounce
同一 target bounds 未变化则不发网络包
```

---

## 23.11 NodeLocator 重定位优先级

建议：

```text
1. windowId + viewIdResourceName
2. class + semantic hash + hierarchyHint
3. class + near(lastBounds)
4. 当前点附近重新 hit-test
5. Lost
```

不要只使用：

```text
text == "Wi‑Fi"
```

因为同一页面可能存在重复文本，也可能因为语言切换而改变。

不要只依赖：

```text
hierarchy child index path
```

列表插入一项后 path 就可能全部改变。

---

## 23.12 指导方“元素吸附” UX

指导方默认仍然是 Pointer。

手指点击：

```text
立即：显示本地 Pointer Prediction
      ↓
几十毫秒后：收到 UI_TARGET_RESOLVED
      ↓
Pointer 吸附成目标矩形
```

效果：

```text
       ·
       ↓
  ┌─────────────┐
  │   Bluetooth │
  └─────────────┘
       ↓
  ╔═════════════╗
  ║   Bluetooth ║
  ╚═════════════╝
```

拖动 Pointer 时可以做：

```text
SNAP_PREVIEW
```

但不要以 60/120Hz 向对端请求 Tree 命中。

建议：

```text
pointer move local = display refresh rate
semantic resolve request = 10~20Hz max
```

最终点击时再强制进行一次最新 resolve。

---

## 23.13 能力回退链

UI Target 统一采用三层解析：

```text
L1 Accessibility Semantic
        ↓ fail
L2 OCR / Vision UI Detection
        ↓ fail
L3 Raw Coordinate Annotation
```

即：

```text
                 UiTargetResolver
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
 Accessibility      Vision         Raw
    Tree             OCR/CV      Coordinate
          │            │            │
          └────────────┴────────────┘
                       │
                 SemanticTarget
```

典型覆盖：

| UI 类型                       | Accessibility | 建议    |
| ----------------------------- | ------------: | ------- |
| 原生 View                     |            高 | L1      |
| Compose（正确提供 semantics） |        高～中 | L1      |
| WebView / HTML                |            中 | L1 → L2 |
| 自绘 Canvas                   |            低 | L2      |
| Unity / OpenGL / 游戏         |          很低 | L2 → L3 |
| 视频内容                      |    无语义节点 | L2 / L3 |

因此“高亮 UI”不能写成 100% 保证能力。

---

## 23.14 敏感节点处理

UI Tree 本身可能包含敏感信息。

必须本地过滤：

```text
node.isPassword == true
→ 永不传 text

editable text
→ 默认不传内容，只传 role / bounds / capability

OTP / PIN / password / payment
→ 不建立可远端读取的文本语义
```

默认协议返回：

```text
bounds
role
clickable
enabled
action mask
confidence
```

而不是：

```text
所有 text / contentDescription
```

“共享屏幕已经能看到像素”并不等于应该额外结构化并上传所有可访问性文本，两者的隐私风险并不相同。

---

## 23.15 与远程点击严格解耦

本节能力只定义：

```text
识别目标
吸附目标
跟踪目标
高亮目标
```

不要因为已经拿到：

```text
AccessibilityNodeInfo.ACTION_CLICK
```

就默认执行：

```text
node.performAction(ACTION_CLICK)
```

如果未来加入远程操作，必须单独定义：

```text
Remote Control Permission
Session Grant
User Confirmation
Dangerous Action Gate
Audit / Revoke
```

也就是说：

```text
Semantic Highlight ≠ Remote Control
```

这是安全边界。

---

## 23.16 状态机扩展

Semantic Highlight 不需要新增顶层 ScreenShare State。

在 `SHARING` 内增加子状态：

```text
SHARING
 │
 ├─ SEMANTIC_OFF
 │
 ├─ SEMANTIC_READY
 │      │
 │      ├─ TARGET_RESOLVING
 │      ├─ TARGET_TRACKING
 │      └─ TARGET_LOST
 │
 └─ DRAWING
```

Accessibility 权限被用户关闭时：

```text
SEMANTIC_READY
      ↓
SEMANTIC_OFF
      ↓
立即清除 SemanticHighlight
      ↓
普通 Annotation 继续工作
```

绝对不能因此停止整个屏幕共享 Session。

---

## 23.17 推荐类设计扩展

增加：

```text
ZiseeGuidanceAccessibilityService
│
├── AccessibilityWindowRepository
├── UiTargetResolver
├── UiTargetTracker
├── NodeLocatorMatcher
├── SensitiveNodeFilter
└── AccessibilityHighlightHost
```

业务层：

```text
UiSemanticEngine
│
├── requestTarget(normalizedPoint)
├── track(targetId)
├── clear(targetId)
└── observeTargetUpdates()
```

网络层：

```text
SemanticTargetTransport
└── WebRTC DataChannel id=6
```

仍然复用当前可靠有序 guidance channel，不为 UI 高亮单独建立新的 PeerConnection。

---

## 23.18 第一阶段实现范围

UI 语义高亮的第一版只做：

```text
✓ AccessibilityService 授权状态
✓ 当前 active window Tree
✓ normalized point → node hit-test
✓ clickable ancestor promotion
✓ bounds 高亮
✓ UI_TARGET_RESOLVED / UPDATE / LOST
✓ scroll / content change 后重新定位
✓ 无节点时回退普通 Pointer
✓ password / editable text 脱敏
```

暂不做：

```text
× 上传整棵 UI Tree
× AI 自动阅读整页 UI
× 自动 performAction
× 自动输入文字
× 批量扫描后台窗口
```

这样已经可以把屏幕指导体验从：

```text
“点这个位置”
```

升级为：

```text
“点这个真实 UI 元素”
```

---

# 24. 标注生命周期

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

# 25. 推荐悬浮菜单 UX

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

# 26. Overlay Window 结构

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

# 27. 推荐类设计

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
├── UiSemanticEngine
│   ├── UiTargetResolver
│   ├── UiTargetTracker
│   ├── NodeLocatorMatcher
│   └── SensitiveNodeFilter
│
├── ProjectionGeometryManager
│
└── SessionStateMachine
```

---

# 28. State Machine

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

# 29. Stop Sharing

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

# 30. MediaProjection.onStop()

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

# 31. 特殊 App 限制

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

# 32. Secure Screen

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

# 33. Remote Annotation 与视频回传重复问题

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

# 34. Annotation ACK

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

# 35. 网络恢复

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

# 36. 模块关系

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

# 37. 与 AR 标注系统统一

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

# 38. P0 实现范围

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

# 39. P1

第二阶段优先增加 UI 语义高亮：

```text
AccessibilityService
Accessibility Tree 本地解析
UI 元素吸附
Semantic Highlight
目标滚动/窗口变化跟踪
UI_TARGET_RESOLVED / UPDATE / LOST
敏感节点过滤
```

同时补齐：

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

# 40. P2

最后再进入视觉与 AI 回退能力：

```text
Screen → AR 转换

Object Tracking
Feature Tracking
OCR Anchor
Vision UI Element Detection
Accessibility 缺失时的 OCR/Vision Target Fallback
跨版本 UI 语义匹配
AI 辅助步骤识别
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

# 41. 最终推荐交互

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

# 42. 最终架构结论

这套功能不要理解为：

> MediaProjection 上面画几个 Canvas。

它应该成为一个独立的：

```text
Remote Guidance System
```

核心由五个部分组成：

```text
① MediaProjection
   负责“看见”

② WebRTC DataChannel
   负责“传递指导动作与 UI Target”

③ AnnotationEngine
   负责“理解和同步普通标注”

④ UiSemanticEngine + AccessibilityService
   负责“把屏幕坐标解析为真实 UI 语义目标并持续跟踪”

⑤ Overlay Renderer
   TYPE_APPLICATION_OVERLAY / TYPE_ACCESSIBILITY_OVERLAY
   负责“把指导与语义高亮真正显示在对方当前手机界面”
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

这套结构既能满足当前的屏幕共享远程指导，也能直接作为以后 **Accessibility UI 语义高亮、ARCore 3D 标注、OCR/Vision 回退、经授权的远程操作指导** 的基础 Remote Guidance Framework。
