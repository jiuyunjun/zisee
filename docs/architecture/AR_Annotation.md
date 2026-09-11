# AR Annotation Design

## 1. 目标

咫尺的 AR Annotation 系统用于视频通话中的远程指导。

系统需要支持：

- 点标记 Marker
- 手绘 Stroke
- 箭头 Arrow
- 区域框选 Region
- 警告 / 操作类语义标记
- 远端用户在视频画面中创建标注
- 被指导端将二维输入稳定吸附到真实三维场景
- AR Tracking 暂时不稳定时依然尽量保证“能标”
- 后续随着 Depth / Plane / Tracking 质量提升自动纠偏

设计核心：

> 优先保证“用户随时可以标”，再逐步提升“标得多准”。

因此不能把 Annotation 是否能够创建完全绑定到 ARCore Plane。

---

# 2. 核心设计原则

## 2.1 Depth First

优先使用：

```text
Depth
  ↓
Plane
  ↓
Feature Point
  ↓
Instant Placement
  ↓
Screen-space Fallback
```

Plane 只是 Geometry Source 之一。

不应：

```text
必须 Plane
↓
否则禁止打标
```

而应：

```text
用户输入
↓
尽可能获得三维位置
↓
立即显示
↓
之后 Refinement
```

---

## 2.2 Immediate Feedback

用户点击或开始手绘之后必须立即看到反馈。

禁止：

```text
点击
↓
没有检测到 Plane
↓
什么也不发生
```

推荐：

```text
点击
↓
Preview Marker
↓
定位中
↓
稳定吸附
```

---

## 2.3 Progressive Refinement

Annotation 的 Pose 不是一次性决策。

允许：

```text
Estimated Pose
      ↓
Depth Pose
      ↓
Stable Surface Pose
      ↓
Anchor
```

通过平滑纠偏完成：

```text
oldPose
  ↓
lerp / slerp
  ↓
newPose
```

禁止突然跳跃。

---

## 2.4 Surface 与 HUD 分离

一个 Annotation 可以同时包含：

```text
3D Surface Content
+
2D / Billboard Information
```

例如 Marker：

```text
Number Badge / Pointer
        ↓
Billboard

Target Ring
        ↓
Surface Attached
```

不要把所有 UI 当成一个 3D 模型直接贴在表面。

---

# 3. 总体架构

```text
                    Annotation Engine
                           │
         ┌─────────────────┼──────────────────┐
         │                 │                  │
       Marker            Stroke              Shape
         │                 │                  │
         └─────────────────┼──────────────────┘
                           │
                    Placement Engine
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
      Depth              Plane             Feature
        │                  │                  │
        └──────────────────┼──────────────────┘
                           │
                  Instant Placement
                           │
                  Screen-space Fallback
                           │
                    Pose Refinement
                           │
                     Anchor Manager
                           │
                     Render Engine
```

逻辑模块建议：

```text
AnnotationManager
PlacementResolver
SurfaceResolver
AnchorManager
PoseRefiner
MarkerRenderer
StrokeRenderer
ShapeRenderer
AnnotationSyncManager
FrameHistoryManager
```

---

# 4. Placement Resolver

## 4.1 目标

PlacementResolver 负责：

```text
Screen Point
      ↓
3D Placement Candidate
```

输入：

```kotlin
data class PlacementRequest(
    val screenX: Float,
    val screenY: Float,
    val timestampNs: Long,
    val preferredSurface: SurfaceContext? = null
)
```

输出：

```kotlin
data class PlacementResult(
    val pose: Pose,
    val normal: Vector3?,
    val method: PlacementMethod,
    val confidence: Float,
    val trackableId: String? = null
)
```

---

# 5. Placement 优先级

推荐：

```text
1. Depth Hit
2. Plane Hit
3. Feature Point
4. Local Surface Projection
5. Instant Placement
6. Screen-space Fallback
```

粗略 Confidence 可定义：

```text
Depth + Stable Normal      1.00
PlaneWithinPolygon         0.90
Depth unstable             0.80
Feature Point              0.60
Local Surface Estimate     0.55
Instant Placement          0.35
Screen-space               0.10
```

实际实现不需要严格按上述数值，可用于候选排序。

---

# 6. Depth Placement

优先检查 Depth。

原因：

Depth 可以处理：

- 墙面
- 桌面
- 家具
- 设备
- 汽车零件
- 非规则物体

不要求完整 Plane 已经生成。

ARCore 配置：

```kotlin
if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
    config.depthMode = Config.DepthMode.AUTOMATIC
}
```

---

# 7. Plane Placement

Plane 作为高可靠 fallback。

候选需检查：

```kotlin
trackable is Plane &&
trackable.isPoseInPolygon(hitPose)
```

适合：

- 桌面
- 地板
- 墙
- 大型规则平面

但不能作为唯一方式。

---

# 8. Feature Point

如果 Depth / Plane 都没有：

允许 Feature Point。

适用于：

- 小型物体
- 平面尚未成熟
- 有明显视觉特征的表面

Feature Point 的 Confidence 应低于稳定 Depth / Plane。

---

# 9. Instant Placement

如果真实 Surface 暂时没有建立：

```text
Touch
↓
Estimated Distance
↓
Temporary Pose
```

先创建 Annotation。

之后：

```text
Instant
↓
Tracking / Depth Improvement
↓
Real Pose
```

通过 Refinement 自动收敛。

---

# 10. Screen-space Fallback

最差情况：

```text
Tracking unavailable
Depth unavailable
Plane unavailable
Feature unavailable
```

也不应该完全拒绝操作。

先保存：

```kotlin
data class ScreenAnnotationPoint(
    val normalizedX: Float,
    val normalizedY: Float,
    val timestampNs: Long
)
```

状态：

```text
SCREEN_LOCKED
```

后续：

```text
Tracking Recovery
↓
Depth / Surface available
↓
Resolve to World
↓
Anchor
```

---

# 11. Annotation 状态

统一定义：

```kotlin
enum class AnnotationState {
    PREVIEW,
    STABILIZING,
    ANCHORED,
    SELECTED,
    LOST,
    SCREEN_LOCKED
}
```

状态流程：

```text
PREVIEW
   ↓
STABILIZING
   ↓
ANCHORED
```

异常：

```text
ANCHORED
↓
Tracking Lost
↓
LOST
↓
Tracking Recovery
↓
STABILIZING
↓
ANCHORED
```

---

# 12. Pose Refinement

Annotation 创建后仍持续检查更可靠几何。

例如：

```text
Frame 1
Instant Placement

Frame 10
Depth available

Frame 20
Depth normal stable
```

最终：

```text
estimatedPose
     ↓
stablePose
```

更新：

```kotlin
position = lerp(oldPosition, targetPosition, alpha)
rotation = slerp(oldRotation, targetRotation, alpha)
```

建议：

```text
alpha ≈ 0.08 ~ 0.2
```

根据帧率调节。

---

# 13. Pose 跳变限制

不能无限接受新 Surface。

需要限制：

```text
距离变化
法线变化
速度
Confidence
```

例如：

```text
distance(old, candidate) < threshold
```

以及：

```text
dot(oldNormal, newNormal) > normalThreshold
```

否则 Marker 可能突然从墙吸到桌子上。

---

# 14. Marker

默认 Marker：

```text
         ③
         │
        ╲│╱
         ▼

      ╭─   ─╮
     │   ·   │
      ╰─   ─╯
```

组成：

```text
Number Badge
Pointer
Target Ring
Anchor Dot
```

---

# 15. Marker 视觉语义

```text
编号
↓
识别哪个 Marker

箭头
↓
注意力方向

Ring
↓
目标区域

Dot
↓
精确 Anchor
```

---

# 16. Target Ring

不使用：

```text
●
```

因为遮挡目标。

使用：

```text
◯
```

进一步建议：

```text
╭─   ─╮
│     │
╰─   ─╯
```

分段式 Ring。

优点：

- 中心可见
- 强定位语义
- 容易显示 Tracking 动画
- 不像普通 UI 圆圈

---

# 17. Number Badge

自动递增：

```text
①
②
③
④
```

删除后不重新编号。

例如：

```text
① ② ③ ④
```

删除 ②：

```text
① ③ ④
```

下一个：

```text
⑤
```

因为语音可能已经引用：

```text
“看③”
```

重新编号会产生歧义。

---

# 18. Marker HUD / Surface 分层

```text
Marker
├── HUD
│   ├── Number
│   └── Pointer
│
└── Surface
    ├── Ring
    └── Anchor Dot
```

HUD：

```text
Billboard to camera
```

Surface：

```text
Follow Surface Normal
```

---

# 19. Marker Surface Offset

避免 Z-fighting：

```text
markerPosition =
surfacePosition +
surfaceNormal * offset
```

建议：

```text
offset ≈ 3 ~ 5 mm
```

复杂环境可以适当增加。

---

# 20. Marker Screen-size Clamp

不建议纯固定世界尺寸。

推荐：

```text
3D Position
+
Screen Size Clamp
```

例如：

```text
Target Ring
约 40 ~ 60 dp
```

近处不巨大，远处也不会消失。

---

# 21. Marker 动画

## PREVIEW

```text
◌
```

半透明。

---

## STABILIZING

```text
◌
```

分段 Ring 缓慢旋转。

---

## ANCHORED

```text
◯
```

停止旋转。

---

## SELECTED

```text
◎
```

播放一次：

```text
Pulse
200 ~ 400 ms
```

用途：

> 我现在说的是这个 Marker。

---

## LOST

```text
◌
```

降低透明度。

不要立即删除。

---

# 22. Marker 类型

V1 建议支持四种。

---

## POINT

默认。

```text
①
▼
◯
```

语义：

> 看这里。

---

## WARNING

```text
②
▼
△
```

语义：

> 注意 / 危险 / 不要操作。

---

## ACTION

```text
③
▼
⊕
```

语义：

> 在这里操作。

适合：

- 按
- 插
- 拧
- 拆
- 调整

---

## REGION

```text
④

╭─────────╮
│         │
╰─────────╯
```

语义：

> 关注整个区域。

---

# 23. MarkerType 数据模型

```kotlin
enum class MarkerType {
    POINT,
    WARNING,
    ACTION,
    REGION
}
```

---

# 24. Marker 数据模型

```kotlin
data class ArMarker(
    val id: String,
    val displayNumber: Int,
    val ownerId: String,

    val type: MarkerType,

    val position: Vector3,
    val normal: Vector3?,

    val method: PlacementMethod,
    val confidence: Float,

    val state: AnnotationState,

    val createdAt: Long
)
```

---

# 25. 手绘 Stroke

手绘不能按照：

```text
Touch Point
↓
必须 Plane Hit
↓
否则断线
```

实现。

正确逻辑：

```text
2D Touch Trajectory
↓
Resampling
↓
Surface Projection
↓
Surface Lock
↓
Miss Recovery
↓
Smoothing
↓
3D Stroke Mesh
```

---

# 26. 手绘输入

首先保存屏幕空间轨迹：

```kotlin
data class StrokeSample(
    val x: Float,
    val y: Float,
    val timestampNs: Long
)
```

不要直接每个 MotionEvent 创建 Mesh Vertex。

---

# 27. Stroke 重采样

Android Touch Event：

```text
• ••       •          ••
```

需要重采样：

```text
•---•---•---•---•---•
```

可以按屏幕距离。

初始建议：

```text
4 ~ 8 px
```

根据：

- 屏幕 DPI
- Stroke Width
- 视频分辨率

自适应。

---

# 28. Stroke Surface Lock

一笔开始时确定 SurfaceContext。

```kotlin
data class StrokeSurfaceContext(
    val origin: Vector3,
    val normal: Vector3,
    val trackableId: String?,
    val confidence: Float
)
```

之后优先保持同一表面。

---

# 29. 为什么需要 Surface Lock

例如：

```text
墙
│
│  Stroke → → →
│
└──────────── 桌面
```

如果每一点重新自由 HitTest：

```text
墙
│   Stroke
│      \
│       \
└────────\────
           桌面
```

很容易突然吸到桌子。

所以检查：

```text
dot(newNormal, strokeNormal) > threshold
```

例如：

```text
threshold ≈ 0.8 ~ 0.9
```

---

# 30. Stroke Hit 优先级

每个采样点：

```text
Depth
↓
Same Surface Plane
↓
Feature Point
↓
Local Surface Projection
↓
Trajectory Estimate
```

和 Marker 不同：

手绘应优先保证：

```text
连续性
```

而不只是单点精度。

---

# 31. Local Surface Projection

如果某几个 Stroke Point 突然没有 Depth：

不要断线。

利用最近稳定：

```text
P
Normal N
```

建立局部平面。

从摄像机发射：

```text
Ray(screenX, screenY)
```

求：

```text
Ray ∩ LocalPlane
```

得到：

```text
Estimated 3D Point
```

继续绘制。

---

# 32. Stroke Miss Recovery

例如：

```text
P0
P1
P2
P3
?
?
P6
```

可以：

```text
P4 P5
```

暂时使用 Local Surface。

当 P6 Depth 恢复后：

```text
P4/P5
↓
Refine
↓
Surface corrected
```

从而避免：

```text
线突然断掉
```

---

# 33. Stroke Trajectory Estimate

如果 Local Surface 也不可用：

利用：

```text
direction = normalize(Pn - Pn-1)
```

短距离预测：

```text
estimatedPn+1 =
Pn + direction * expectedDistance
```

但只允许：

```text
非常短时间
非常短距离
```

否则误差会快速累积。

---

# 34. Stroke Refinement

Stroke 点可以具有状态：

```kotlin
enum class StrokePointQuality {
    DEPTH,
    PLANE,
    LOCAL_SURFACE,
    ESTIMATED
}
```

后续 Depth 恢复后：

```text
ESTIMATED
↓
DEPTH
```

平滑纠偏。

---

# 35. Stroke Mesh

不建议：

```text
●●●●●●●●
```

即大量小球。

建议：

```text
Ribbon Mesh
```

或高质量情况下：

```text
Tube Mesh
```

V1 推荐 Ribbon。

---

# 36. Billboard Ribbon

每个中心点：

```text
Pi
```

定义：

```text
strokeDir
cameraDir
```

得到：

```text
side = normalize(
    cross(strokeDir, cameraDir)
)
```

生成：

```text
Left
Right
```

构成 Triangle Strip。

优点：

```text
任何角度都比较清晰
```

---

# 37. Surface Ribbon

如果希望手绘真正贴在物体上：

使用：

```text
surfaceNormal
strokeDirection
```

计算：

```text
side =
normalize(
    cross(surfaceNormal, strokeDirection)
)
```

然后：

```text
left  = p - side * width/2
right = p + side * width/2
```

---

# 38. Stroke Surface Offset

和 Marker 一样：

```text
P =
surfacePoint +
normal * offset
```

建议：

```text
2 ~ 5 mm
```

避免 Z-fighting。

---

# 39. Stroke Smooth

原始 Stroke：

```text
/\/\_/\/\
```

可经过：

```text
Spatial Filter
+
Curve Smoothing
```

但不能过度平滑。

否则：

```text
用户画的尖角
```

会被变成：

```text
圆弧
```

尤其箭头、数字、符号会失真。

建议：

```text
轻度平滑
```

而不是 Bezier 强制拟合全部轨迹。

---

# 40. Stroke Width

建议 Stroke Width 也使用：

```text
World Size
+
Screen Clamp
```

否则：

```text
远距离看不见
近距离过粗
```

视觉宽度保持稳定。

---

# 41. 手绘状态

定义：

```text
DRAWING
REFINING
ANCHORED
LOST
```

流程：

```text
DRAWING
↓
Touch Up
↓
REFINING
↓
ANCHORED
```

Tracking Lost：

```text
ANCHORED
↓
LOST
```

---

# 42. 手绘与 Marker 的统一

所有 Annotation：

```text
Marker
Stroke
Arrow
Region
```

共享：

```kotlin
data class AnnotationStyle(
    val opacity: Float,
    val strokeWidth: Float,
    val selected: Boolean,
    val ownerId: String
)
```

以及：

```text
tracking state
confidence
createdAt
```

---

# 43. Arrow Annotation

Arrow 本质上可以复用 Stroke Engine。

结构：

```text
Stroke Body
+
Arrow Head
```

因此：

```text
ArrowRenderer
```

不需要完全独立一套 Surface Projection。

流程：

```text
Drag
↓
Stroke Projection
↓
End Point
↓
Generate Arrow Head
```

---

# 44. Region Annotation

Region 可以有两种：

## Screen Rectangle

适合快速视频指导：

```text
二维框选
```

然后投影到目标 Surface。

---

## Surface Region

根据：

```text
4 Corners
```

投影到局部 Surface。

如果 Surface 不规则：

优先保持视觉区域稳定，而不是强行贴合所有 Depth。

---

# 45. 远程 Annotation

核心场景：

```text
A：指导方
B：被指导方
```

A 看到的是：

```text
B 的视频
```

A 的 Touch Coordinate 并不是 B 当前实时 AR Frame Coordinate。

---

# 46. 不应该发送 World XYZ

指导方没有被指导方的 World Coordinate。

因此不要：

```text
A 计算 XYZ
↓
发送给 B
```

正确：

```text
A
Screen Coordinate
+
Video Timestamp
↓
B
Historical Camera Pose
+
Depth
↓
Resolve World Position
```

---

# 47. Marker 网络数据

例如：

```json
{
  "annotationId": "marker_003",
  "type": "POINT",
  "displayNumber": 3,
  "x": 0.482,
  "y": 0.613,
  "videoTimestamp": 18433822193
}
```

---

# 48. Stroke 网络数据

发送：

```json
{
  "annotationId": "stroke_71",
  "action": "append",
  "points": [
    {
      "x": 0.423,
      "y": 0.611,
      "videoTimestamp": 18433822193
    },
    {
      "x": 0.431,
      "y": 0.615,
      "videoTimestamp": 18433833112
    }
  ]
}
```

坐标采用：

```text
normalized coordinates
0.0 ~ 1.0
```

减少：

```text
分辨率差异
Rotation 差异
编码分辨率变化
```

问题。

---

# 49. Frame History

这是远程 AR Annotation 稳定性的关键。

假设：

```text
视频延迟 300 ms
```

指导方点的是：

```text
300 ms 前的画面
```

如果被指导端使用：

```text
当前 Camera Pose
```

进行 HitTest：

Marker 会产生明显偏移。

所以保存：

```text
Frame History
Camera Pose History
Depth History
```

---

# 50. Frame History Buffer

建议：

```text
0.5 ~ 2 s
```

初始可使用：

```text
2 s Ring Buffer
```

每个记录：

```kotlin
data class ArFrameSnapshot(
    val timestampNs: Long,
    val cameraPose: Pose,
    val intrinsics: CameraIntrinsics,
    val depthRef: DepthFrameReference?
)
```

---

# 51. Remote Timestamp Alignment

远程请求：

```text
videoTimestamp = T
```

B：

```text
find nearest Frame
timestamp ≈ T
```

然后：

```text
screen point
↓
historical intrinsics
↓
ray
↓
historical depth / surface
↓
world coordinate
```

---

# 52. 视频 Crop / Scale 坐标转换

必须处理：

```text
Camera Image
↓
Encoder Crop
↓
Video Frame
↓
Remote View Scaling
↓
Touch
```

不能直接：

```text
remote x/y
=
camera x/y
```

需要维护：

```text
VideoTransform
```

包括：

```text
rotation
crop
mirror
aspectFit / aspectFill
encoder resolution
display resolution
```

---

# 53. 坐标管线

完整：

```text
Remote Touch
↓
Remote View Coordinate
↓
Normalized Video Coordinate
↓
Encoded Frame Coordinate
↓
Camera Image Coordinate
↓
Historical Camera Ray
↓
Depth / Surface Resolve
↓
World Coordinate
```

这是远程 Annotation 最容易产生严重误差的地方之一。

---

# 54. Annotation Ownership

每个 Annotation：

```text
annotationId
ownerId
sessionId
```

多人情况下：

```text
ownerId + localNumber
```

可内部形成：

```text
A-001
B-001
```

UI 仍显示：

```text
①
```

即可。

---

# 55. AnnotationManager

建议：

```kotlin
class AnnotationManager {

    fun createMarker(...)
    fun beginStroke(...)
    fun appendStroke(...)
    fun endStroke(...)

    fun select(...)
    fun remove(...)
    fun clearAll(...)

    fun onTrackingChanged(...)
    fun update(...)
}
```

AnnotationManager 不直接调用 ARCore HitTest。

调用：

```text
PlacementResolver
```

---

# 56. Renderer 解耦

```text
AnnotationRenderer
├── MarkerRenderer
├── StrokeRenderer
├── ArrowRenderer
└── RegionRenderer
```

Renderer 输入：

```text
Annotation Model
+
Pose
+
Camera
+
State
```

不关心：

```text
Depth API
Plane
Feature Point
```

---

# 57. PlacementMethod

统一：

```kotlin
enum class PlacementMethod {
    DEPTH,
    PLANE,
    FEATURE_POINT,
    LOCAL_SURFACE,
    INSTANT_PLACEMENT,
    SCREEN_FALLBACK
}
```

---

# 58. Annotation 基类

可以设计：

```kotlin
sealed class Annotation {

    abstract val id: String
    abstract val ownerId: String

    abstract var state: AnnotationState

    abstract val createdAt: Long
}
```

子类型：

```kotlin
MarkerAnnotation
StrokeAnnotation
ArrowAnnotation
RegionAnnotation
```

---

# 59. Tracking Quality

建议在系统内部统一维护：

```kotlin
enum class TrackingQuality {
    HIGH,
    MEDIUM,
    LOW,
    UNAVAILABLE
}
```

综合：

```text
ARCore TrackingState
Depth availability
Depth variance
Feature quality
Pose stability
```

---

# 60. Quality 对 Annotation 的影响

HIGH：

```text
正常创建
正常 Anchor
```

MEDIUM：

```text
创建
+
Refinement
```

LOW：

```text
Preview / Instant Placement
```

UNAVAILABLE：

```text
Screen-space Fallback
```

---

# 61. Interaction Feedback

用户应该始终知道 Annotation 当前状态。

不要额外弹大量 Toast。

通过 Annotation 自己表达：

```text
旋转 Ring
透明度
Pulse
Dashed Stroke
```

等。

---

# 62. 性能设计

不能：

```text
每个 Stroke Point
=
Anchor
```

Anchor 数量会迅速膨胀。

正确方式：

```text
一个 Stroke
↓
一个或少量 Anchor / Local Frame
↓
大量 Local Vertex
```

---

# 63. Stroke Local Coordinate

Stroke 开始：

```text
Stroke Origin Anchor
```

后续所有点：

```text
World Point
↓
Transform into Stroke Local Space
↓
Mesh Vertex
```

减少：

```text
大量 Anchor
```

---

# 64. Anchor 策略

Marker：

```text
1 Marker ≈ 1 Anchor
```

Stroke：

```text
1 Stroke ≈ 1 Anchor
```

长距离 Stroke 如果跨越多个 Surface：

可以：

```text
Stroke Segment
```

拆成多个局部 Anchor。

---

# 65. Stroke Segment

例如：

```text
墙
↓
桌面
```

如果检测到 Surface Normal 大幅变化：

```text
Segment A
↓
Segment B
```

而不是一个巨大扭曲 Mesh。

---

# 66. 自动断段条件

例如：

```text
normal delta > threshold
```

或：

```text
depth discontinuity > threshold
```

或：

```text
position jump > threshold
```

则：

```text
new StrokeSegment
```

视觉上仍保持为同一笔。

---

# 67. Annotation 生命周期

```text
CREATE
↓
PREVIEW
↓
STABILIZING
↓
ANCHORED
↓
ACTIVE
↓
DELETE
```

Tracking 异常：

```text
ACTIVE
↓
LOST
↓
RECOVER
↓
STABILIZING
```

---

# 68. 删除策略

删除：

```text
Visual Fade
↓
Detach Anchor
↓
Remove Mesh
↓
Remove Network State
```

网络：

```text
annotation.delete
```

必须使用：

```text
annotationId
```

而不是 displayNumber。

---

# 69. Undo / Redo

手绘强烈建议支持：

```text
Undo Last Stroke
```

而不是按 Point Undo。

因为用户认知：

```text
一次落笔 = 一个操作
```

所以：

```text
Stroke = Transaction
```

---

# 70. V1 推荐范围

第一版本不要一次实现所有高级功能。

建议：

### 必须

```text
POINT Marker
Stroke
Depth-first Placement
Plane Fallback
Instant Placement
Screen Fallback
Surface Lock
Pose Refinement
Remote Timestamp Alignment
Undo
Delete
```

---

### 第二阶段

```text
WARNING
ACTION
Arrow
Region
Stroke Surface Segmentation
多人 Annotation
```

---

### 后续

```text
Semantic Surface
Scene Mesh
Cloud Anchor
3D Hand Pointer
Object Tracking
OCR / AI Suggested Annotation
```

---

# 71. 推荐开发顺序

## Phase 1

实现：

```text
PlacementResolver
```

验证：

```text
Depth
Plane
Feature
Instant
```

---

## Phase 2

实现：

```text
Point Marker
```

完成：

```text
编号
Target Ring
Billboard
状态动画
Anchor
```

---

## Phase 3

实现：

```text
PoseRefiner
```

解决：

```text
Marker 漂移
跳变
Temporary → Stable
```

---

## Phase 4

实现：

```text
Stroke
```

完成：

```text
Touch Sampling
Resampling
Surface Lock
Local Plane Fallback
Ribbon Mesh
```

---

## Phase 5

实现远端：

```text
Video Timestamp
Frame History
Coordinate Transform
Remote Placement
```

---

## Phase 6

扩展：

```text
Arrow
Warning
Action
Region
```

---

# 72. 推荐模块结构

```text
ar/
└── annotation/
    ├── AnnotationManager.kt
    │
    ├── model/
    │   ├── Annotation.kt
    │   ├── MarkerAnnotation.kt
    │   ├── StrokeAnnotation.kt
    │   ├── AnnotationState.kt
    │   └── AnnotationStyle.kt
    │
    ├── placement/
    │   ├── PlacementResolver.kt
    │   ├── DepthResolver.kt
    │   ├── PlaneResolver.kt
    │   ├── FeatureResolver.kt
    │   ├── InstantResolver.kt
    │   └── SurfaceContext.kt
    │
    ├── refinement/
    │   ├── PoseRefiner.kt
    │   └── SurfaceTracker.kt
    │
    ├── stroke/
    │   ├── StrokeSampler.kt
    │   ├── StrokeProjector.kt
    │   ├── StrokeSmoother.kt
    │   └── StrokeMeshBuilder.kt
    │
    ├── render/
    │   ├── AnnotationRenderer.kt
    │   ├── MarkerRenderer.kt
    │   ├── StrokeRenderer.kt
    │   ├── ArrowRenderer.kt
    │   └── RegionRenderer.kt
    │
    ├── anchor/
    │   └── AnchorManager.kt
    │
    └── sync/
        ├── AnnotationSyncManager.kt
        ├── FrameHistoryManager.kt
        └── VideoCoordinateMapper.kt
```

---

# 73. 核心数据流

本地 Marker：

```text
Touch
↓
PlacementResolver
↓
PlacementResult
↓
AnnotationManager
↓
Marker
↓
PoseRefiner
↓
Anchor
↓
Renderer
```

本地 Stroke：

```text
Touch Stream
↓
StrokeSampler
↓
Resample
↓
Surface Lock
↓
StrokeProjector
↓
Stroke Mesh
↓
Refinement
↓
Renderer
```

远端 Marker：

```text
Remote Touch
↓
Video Timestamp
↓
Frame History
↓
VideoCoordinateMapper
↓
Historical Ray
↓
PlacementResolver
↓
World Marker
```

远端 Stroke：

```text
Remote Touch Stream
↓
Timestamp Mapping
↓
Historical Frames
↓
Surface Projection
↓
Stroke Surface Lock
↓
Mesh
```

---

# 74. 最终设计思想

咫尺的 AR Annotation 系统不应该建立在：

```text
Plane Detection
```

之上。

而应该建立在：

```text
Annotation Intent
        ↓
Best Available Surface Information
        ↓
Immediate Placement
        ↓
Progressive Refinement
        ↓
Stable Spatial Annotation
```

之上。

Marker 注重：

```text
指得准
看得清
说得明白
```

Stroke 注重：

```text
连续
不跳
不轻易断
贴住同一表面
```

Placement 注重：

```text
Depth First
Graceful Fallback
Progressive Refinement
```

远程同步注重：

```text
Timestamp Correctness
Coordinate Transform Correctness
```

而不是仅仅发送二维坐标。

最终用户体验应该是：

> 用户只负责“点这里、圈这里、画这里”。

系统负责：

> 找表面、估深度、纠偏、稳定、吸附和同步。

这是整个 AR Annotation 子系统最重要的设计边界。