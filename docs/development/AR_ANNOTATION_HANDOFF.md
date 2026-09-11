# AR Annotation 实现交接

更新：2026-09-11。用户要求准备交接，停止继续扩展功能。仓库 `C:\Users\zhang\Desktop\WorkSpace\Zisee`，分支 `main`。

## 任务和分工

目标是实施 [AR_ANNOTATION_ROADMAP.md](AR_ANNOTATION_ROADMAP.md)，不是仅完成模型或本地演示。用户明确指定：UI（含 3D）与核心算法由主 agent 实现，其余交给 GPT-5.6-sol。

派发协议任务时明确指定了 GPT-5.6-sol，但该 agent 返回用量限制，协议没有落盘。验证/最小 RTC 集成代理后来报告实际模型不符（启动参数也指定了 sol），已停止它的新工作；保留已提交成果并如实报告。不要声称所有委派均由指定模型完成，不要未经用户改动分工就把 v2 生产实现转交其他模型。

## 当前可用路径

- 本地 AR 的“标点”调用 `ArVideoCapture.createLocalMarker` → `ArSessionController.createPoint`。深度/平面/特征/估计位置具有不同 evidence；Screen 结果无假 pose，TTL 后删除；返回 Screen 不作为世界标注成功，UI 提示重新点选。
- POINT 表面环使用明确 normal 投影，编号 HUD 保持屏幕朝向；无 normal 使用 billboard。估计位置显示虚线/较低透明度。source renderer 仍烧入同一帧，远端观看即可看到现场标注。
- 本地“手绘”用 `ArAnnotationInput`，每个采样读取当时 `DisplayedArFrame`，逆变换 FIT/旋转/镜像。最多 16 点/批，50 ms 触摸批次，ViewModel 单笔有界队列，取消/失败清理整笔。
- `beginLocalStroke / appendLocalStroke / endLocalStroke` 经 RtcSession/NativeRtcSession 转发，controller 用一笔一个 anchor 和 StrokeBuilder 局部坐标。begin 必须有真实世界定位；短缺口只在锁定表面上预测，超 100 ms 或 5 cm 停止；法线突变不跨面连接。
- 远端保持 v1 Create/Remove/Clear。没有 v2 协商，没有远端手绘工具。旧“箭头”“圈”是原有单点符号。

## 关键文件

生产路径均在 `android/app/src/main/java/com/lazydoglab/zisee/`：

| 文件 | 职责 |
| --- | --- |
| `ar/annotation/AnnotationModel.kt` | author、稳定编号、revision、状态正交、World/Screen 类型、ledger、预算 |
| `ar/spatial/PlacementResolver.kt` | 精确历史帧的分级定位；不替换当前 pose |
| `ar/spatial/SpatialGeometry.kt`、`PoseHistory.kt` | 保守深度邻域、normal、feature/plane snapshots |
| `ar/spatial/PoseRefiner.kt` | 表面身份/法线/距离/质量门槛、三个不同新帧确认、按时间常数平滑 |
| `ar/annotation/StrokeBuilder.kt` | Surface Lock、重采样、短缺口预算、局部坐标、Ribbon 三角形 |
| `ar/session/ArCoreBackend.kt` | 每帧至多 128 features、16 plane IDs；PointCloud use 释放；当前帧原生 Instant Placement |
| `ar/session/ArSessionController.kt` | native anchor owner、模型生命周期、POINT/Stroke API、权限与清理 |
| `ar/render/MarkerProjection.kt`、`AnnotationOverlayRenderer.kt` | 3D 表面环投影、编号 HUD、复用 VBO、Ribbon |
| `ar/session/ArVideoCapture.kt` | 同一源帧渲染与本地 API |
| `ui/ArAnnotationInput.kt`、`ActiveCall.kt`、`CallScreen.kt` | 触摸、暂态 preview、手绘工具、撤销 |
| `call/CallViewModel.kt` | 单笔有界 worker、取消/超时反馈、已确认整笔撤销 |

## 接手顺序与未完成项

1. **先审查本地闭环，再扩展协议。** 现有 preview 是约 700 ms 的独立暂态层，尚未通过 preview ID/revision 与烧入视频去重；选择状态只有 controller API，选中/删除 UI 未接。Stroke 轻平滑、完整事务状态/内容 revision 未实现。不要把现有 ledger revision 当成已完成 Stroke 权威同步。
2. **v2 必须先设计兼容和鉴权边界。** v1 拒绝未知字段/数组。拟采用独立 `zisee-ar-v2` 通道和显式能力协商，但尚无任何已实现的 v2 schema/transport。定义 begin/append/end/cancel、operation ID、seq、revision、墓碑、重入分页快照、FIELD/GUIDE 权限及背压；旧端继续 v1，不能在 v1 上试探未知消息。纳秒继续十进制字符串，单包 4 KiB/每批 16 点，并给控制消息保留预算。
3. 现有 controller 提供 `createPoint(epoch,id,request,author)`、`beginStroke(...)`、`appendStroke(epoch,id,requests,author)`、`endStroke/cancelStroke(epoch,id,author)`、`annotationSnapshot()`。这些是本地基础 API，不是网络事务处理器；v2 还需实现严格幂等、批次原子性或明确部分接受语义、重入完整内容快照。
4. 强化几何与生命周期：真机验证 Depth 对齐/法线；测试 native Instant Placement 从 approximate 到 full tracking（normal 仍不能用 native +Y）；检查 anchor 姿态旋转修正、失跟踪世界连续性与长时间绘制。历史射线估计因缺乏可验证 correspondence 不会自动升级；重新点选是有效降级。
5. 性能尚未测量：GL P95、mesh 生成/投影耗时、CPU allocations、anchor/feature/cache 峰值、通道流量、视频 FPS/持续通话。当前 VBO 复用，但 Ribbon CPU 几何会按帧生成；不能声称性能优化或真机稳定。
6. P5 真机矩阵与 P6 WARNING/ACTION、起终点 Arrow、四角 Region、跨表面分段仍待做。不要把旧 ARROW/CIRCLE 标记为这些功能已实现。

## 验证与构建

JDK：`C:/Program Files/Android/Android Studio/jbr`。从 `android` 执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:compileReleaseKotlin
```

最近一轮完整构建成功；326 JVM 测试，失败/错误 0，AR 包 77 个。模拟器 GPU（含 Ribbon）、帧池、旧 v1 通道、点击路由 smoke 通过，详见 [验证记录](AR_ANNOTATION_VALIDATION.md)。没有本次真机/双设备验收。只在 `emulator-5556` 安装过 APK，未覆盖已连接真机。

## 已有提交及工作区注意

- `b4f418e`：模型、编号、author 与 controller 权限。
- `a2aa200`：分级定位、native Instant adapter、PoseRefiner、Surface Lock Stroke 核心及测试。
- `84ffdaa`、`7fd1a88`、`af8ae1b`：模型/定位/controller 测试。
- `f7bbf59`、`ba90a0d`：本地 Stroke RTC API、release 时清除支持状态。
- UI/3D 与本文件在后续独立提交中，使用 `git log -12 --oneline` 获取最终哈希。

同一工作区还有画质/网络方向的并行开发，已有其他提交穿插本次提交；不要 reset、amend、rebase 或覆盖那些工作。原始 `docs/architecture/AR_Annotation.md` 与跨 App 标注文档原本未跟踪，未擅自纳入提交。每次提交明确列文件，必要时 `git commit --only`；所有文本 UTF-8 无 BOM。本次没有发布、push 或修改服务端媒体架构。
