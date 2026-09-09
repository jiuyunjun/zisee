---
title: AR Assist 框架与接入契约
document_id: ARCH-AR-001
version: 1.0.0
status: Active
created: 2026-09-09
updated: 2026-09-09
owners:
  - android
---

# AR Assist 框架

## 本次范围

实现 `ARCHITECTURE.md` 第 18–22 节的现场端框架：ARCore 能力/安装检查、独占相机会话、历史帧缓存、历史深度/平面投影、本地锚点及生命周期、Pin/Arrow/Circle 类型、归一化坐标变换和版本化协议。框架通过接口接入媒体层，不在普通通话期间启动 AR。

本次不包含通话 AR 按钮、叠加渲染、AR 视频编码、双端 DataChannel 接线、M3 屏幕共享或完整的 M5 视频时间戳传递。因此 M4/M5 的产品退出条件仍未达到。`design/ARAssist.dc.html` 与 `docs/product/DESIGN.md` 的 scanning/tracking lost/标记工具栏属于后续 UI 接入。

## 模块

| 实现（android/app/src/main/java/com/lazydoglab/zisee 下） | 职责 |
| --- | --- |
| ar/session/ArCoreAvailability.kt | 异步检查支持度；用户触发后检查权限并请求安装 |
| ar/session/ArCoreBackend.kt | Session、OES 纹理、深度/平面快照、原生 Anchor、相机租约 |
| ar/session/ArSessionController.kt | 状态、历史帧、Marker 操作、失败清理和线程约束 |
| ar/spatial/PoseHistory.kt | 有界精确时间戳查找，不保存 Android Image/Frame |
| ar/spatial/SpatialGeometry.kt | 米制局部世界坐标、内参、四元数、不可变深度/平面快照 |
| ar/spatial/SpatialResolver.kt | 历史深度优先；无深度时求交历史平面多边形 |
| ar/annotation/VideoPointMapper.kt | 逆变换 FIT/FILL、旋转和显示镜像，拒绝黑边触摸 |
| ar/annotation/ArProtocol.kt | zisee-ar-v1 消息编解码及输入限制 |

## 会话与相机资源

现场方每次进入 AR 创建新 UUID 和新 `ArSessionController`。指导方只处理视频与交互，不要求安装 ARCore。

状态为 IDLE → STARTING → SCANNING → TRACKING ↔ TRACKING_LOST。活动会话可 PAUSED，恢复后重新 SCANNING；native failure → FAILED，exit/hangup → CLOSED。

接入顺序：

1. UI 异步调用 `ArCoreAvailability.check`。不支持时隐藏 AR；未知/暂不可用时可重试，不能假定支持。
2. 用户主动启用后，在主线程调用 `prepare(activity, userRequestedInstall)`；权限不足由 UI 延迟请求。安装返回后重新检查，使用 false 防止拒绝后反复弹窗。AR Optional 也需创建前检查安装状态。[官方启用说明](https://developers.google.com/ar/develop/java/enable-arcore)
3. 媒体 owner 停止并释放当前 CameraX/WebRTC 摄像头，授予 `ArCameraLease`；租约的 close 恢复先前模式。不能只传“已经停止”的布尔值，也不能因为支持双摄就同时打开 CameraX 和 ARCore。
4. 在拥有 current EGL context 的单一 GL 工作线程创建 controller；factory 调用 `ArCoreBackend.create(context, lease)`，设置真实 `setDisplayGeometry`，然后 start。factory 配置失败也关闭 Session、纹理和租约。GL/EGL 属于外部媒体 owner。
5. 每个 GL tick 调用 capture；只有非空快照才能发布对应 AR 帧。UI 观察只读 StateFlow。DataChannel 命令排队切到同一 GL 线程，不能从 WebRTC 回调直接访问 Session。
6. 进入后台/暂离 AR 前停止新命令与渲染，在 GL 线程 pause；这会 detach 标记、清空历史帧。恢复后先扫描，拒绝重复旧时间戳。租约保留到 close；若需恢复普通相机则退出并 close。
7. 退出/挂断时停止回调，在 GL 线程 close，完成后才释放 EGL。逐项尝试 detach → pause → Session.close → 删除纹理 → 归还租约。原生关闭可能耗时，不能同步阻塞 UI。[Session 生命周期参考](https://developers.google.com/ar/reference/java/com/google/ar/core/Session)

controller 检查 owner 线程，backend 额外禁止主线程。native capture/resume 失败进入 FAILED 并清理；单个 anchor 创建失败返回 NATIVE_FAILURE，不结束基础 RTC 通话。`ArEvent` 只含固定事件类型；集成时接到 AppLogger，不打印 pose、深度、请求体或异常消息，回调不得抛异常。状态/失败原因由上层转成用户提示。

## 帧、坐标与投影

`VideoFrameReference.timestampNs` 是**现场 ARCore 源帧时间戳**，不是墙钟、远端解码时间或任意 VideoFrame.timestampNs。远端原样回传其实际展示帧的来源引用。本次不假设 WebRTC 自动保留 AR 时间戳。

后续必须建立“实际 AR 纹理帧 → 编码/RTP 帧 → 实际展示帧 → 原始引用”的可靠映射。只在 DataChannel 发最新时间戳，无法解决重排、丢帧、队列积压和同步；无法确认时禁用空间点击，不能猜测或使用当前姿态。ARCore GPU 纹理与 CPU 图像的 UV 变换也必须正确处理，不能把纹理直接当作完整未裁切 CPU 图像。[Frame 坐标变换参考](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)

VideoPoint 表示未旋转 CPU 图像的归一化坐标。VideoPointMapper 先去掉 FIT 黑边/恢复 FILL 中心裁切，再反镜像、逆转 0/90/180/270°。geometry 必须来自被点击的帧。2D 标注仍用 Annotation2D，不会自动转为空间标记。

缓存默认 3 秒且最多 180 帧，两种限制同时生效。重复/倒序帧不覆盖已发布元数据，查找只接受精确时间戳与后摄 Track。暂停清空后 controller/backend 仍保留时间戳水位，防止再次接受旧帧。

快照包括 pose、CPU image intrinsics、tracking、可选 depth 和最多 16 个平面，每面最多 128 个顶点。平面保存当时中心姿态和凸多边形，求交时不查询当前 Frame.hitTest。ARCore polygon 使用平面局部 X/Z 坐标。[Plane 参考](https://developers.google.com/ar/reference/java/com/google/ar/core/Plane)

深度按 row/pixel stride 读取，下采样至最多 160×120，以 unsigned 16-bit 毫米保存；每帧样本最多 38,400 字节，180 帧约 6.6 MiB，另有元数据开销。Image 在本次调用内关闭，快照防御性复制。仅 image.timestamp 与 frame.timestamp 相等才保存；预热缺失或旧深度降级到历史平面。

投影公式为 `[(u*width-cx)/fx, -(v*height-cy)/fy, -1] * depthMetres`，再乘历史 camera pose。深度沿相机 Z 轴计量，不是射线长度；AR 相机向 -Z 看、Y 向上。[Depth 指南](https://developers.google.com/ar/develop/java/depth/developer-guide)

无有效深度时对历史平面多边形求射线交点，选前方最近交点；平行、面后、面外均拒绝。两条路径默认最大距离 8 米。历史 tracking 非 TRACKING、当前尚未跟踪、缺帧或无表面均返回明确 rejection。绝不回退当前 pose/hit-test。

限制：没有姿态插值、深度置信度/边缘滤波、世界地图重定位补偿或真实端到端同步误差估计。ARCore 后续世界坐标修正和移动物体仍可能带来偏差，不能宣称高精度测量或 M5 完成。

## 标记与协议

仅创建会话内本地 Anchor，支持 Pin/Arrow/Circle 类型、删除、清空和读取最新 pose/tracking。类型不代表已实现图形渲染或箭头朝向交互。

默认同时最多 32 个 Anchor；每会话最多接受 512 个不同 marker ID，删除后仍记住 ID，阻止重放 create 复活已删除标记。达预算后需退出重新进入 AR。pause 清空标记但不清除这些 ID；旧 sessionId 不得用于新 controller。

计划使用独立、可靠、有序的 `zisee-ar-v1` DataChannel；本次仅 codec，不改变现有 camera-state channel 或服务端 signaling。仅已鉴权的当前通话对端、已协商 AR 的现场 UUID 能收发；ready 不是授权机制。

| type | 字段（另含 v=1、sessionId） | 含义 |
| --- | --- | --- |
| ready | depth: boolean | 现场公布会话和能力 |
| create | id, kind, track, timestampNs, x, y | PIN/ARROW/CIRCLE 请求，track 必须 video_back |
| remove | id | 删除标记 |
| clear | 无 | 清空会话标记 |
| result | id, rejection | create 结果；null 成功，否则 SpatialRejection 枚举 |

UUID 用标准小写字符串；timestampNs 用正整数十进制**字符串**保留 Long 精度；x/y 为有限 [0,1] 数值。上限 4096 字节、严格 UTF-8，拒绝未知字段/类型、非法范围、未来协议版本、嵌套对象和尾随数据。不传世界坐标/深度，不持久化数据。

接入时 create 映射到 controller.createMarker 并返回 result；remove/clear 映射对应操作，可靠有序通道避免重排。DUPLICATE_ID 不能视作新建成功。外部层还需缓冲区背压、命令速率限制、挂断丢弃、AR ready/退出协商与 2D 降级 UI；当前没有把这些网络行为伪装成可用。

## SDK 与兼容性

仅新增 Google Maven `com.google.ar:core:1.56.0`，不引入额外渲染框架。项目 minSdk 26 高于 AR 运行要求 24；manifest 声明 AR Optional，不加必需 AR 特性或新权限。Depth 运行时检测，不支持仍可用平面。

核查 AAR 为 356,212 字节，含四种 ABI 的 JNI/SDK stub；arm64 两库共 168,872 字节，x86_64 共 168,832 字节（未压缩，不等于 APK 增量）。AR 运行时由 Google Play Services for AR 提供。[SDK 与运行时说明](https://developers.google.com/ar/develop/java/enable-arcore)

POM 标注 ARCore Additional Terms of Service，不能把示例代码 Apache 2.0 等同 SDK 许可。不开启 Cloud Anchor/Geospatial。后续开放 AR 入口时补齐 Google ARCore 用户说明、条款/隐私链接与相机/传感器处理披露；当前普通通话不调用该 SDK。[ARCore 条款](https://developers.google.com/ar/develop/terms)、[用户隐私要求](https://developers.google.com/ar/develop/privacy-requirements)

## 验证

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

JVM 覆盖缓存容量/时间边界、精确匹配、重复/倒序帧、历史 pose/旋转投影、轴向深度、无 Depth/空洞平面降级、面外/面后/平行、超距、不可变快照、FIT/FILL/所有旋转/镜像、协议往返及非法输入、会话隔离、tracking lost、暂停恢复、删除重放、锚点上限、native 失败清理和跨线程拒绝。

2026-09-09 本机结果：126 项 JVM 测试全部通过（AR 相关 30 项，其中新增 29 项）；assembleDebug、lintDebug 通过，lint 为 0 错误、38 警告，AR 源码无 lint 报告。18 个改动文本文件均验证为 UTF-8 无 BOM。

真机仍待通话接入后验收：支持/不支持 ARCore、未安装/拒装、权限拒绝、Depth 有无、相机交接、EGL 重建、前后台/挂断、实际深度图对齐、移动时锚点稳定性、双端历史帧点击与音视频不中断。JVM fake backend 和成功构建不能替代设备结论。
