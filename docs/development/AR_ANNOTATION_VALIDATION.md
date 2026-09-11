# AR Annotation 验证记录

更新：2026-09-11。

## 标记可读性修正

后续交互修正：数字使用缓存的系统 sans-serif-medium 字形，移除七段数码管造型；标记工具统一为 48 dp 图标按钮，单行排列，窄屏水平滚动，保留无障碍名称/选中状态。手绘和点击使用不同 pointerInput 模式 key，切换工具会正确重建手势处理；append 中断时结束并保留有效轨迹，只有明确取消/异常才清理未完成笔画。CallArTapSmoke 增加“点击手绘后拖动必须进入 stroke handler”的回归断言；预览无源帧时仍不伪造空间定位。

数字 HUD 在源视频合成时抵消输出旋转，显示在目标上方并用立杆连接；字号、外圈和箭头描边加粗。只有 STABILIZING 使用虚线，ANCHORED 改为连续实线。同一 plane ID 从 LOCAL_SURFACE 边缘带进入 PLANE 多边形后允许质量提升；无对应证据的历史距离估计仍保留虚线，不能仅靠等待伪装成确定位置。现场标记工具栏新增“停止 AR”。

回归：`AnnotationHudTest` 覆盖四个旋转方向；`PlacementResolverTest` 覆盖同一表面边缘带变为确定多边形。Debug 构建和 Release Kotlin 编译通过；尚需真机查看竖屏/横屏的最终观感。

本文记录 [AR Annotation Roadmap](AR_ANNOTATION_ROADMAP.md) 各阶段已经实际执行的自动化、构建和设备验证。未列出的项目不视为通过；真机结果必须写明设备与场景。

## P0 契约和模型

状态：JVM 契约测试与阶段集成构建通过。

自动化覆盖：

- `AnnotationModelTest`：删除与 clear 后 UUID 和显示编号不复用；FIELD/GUIDE 删除权限；selected 与 LOST/SCREEN_LOCKED 状态正交；屏幕坐标与世界 pose 类型不可混用；annotation、stroke、批次、数据包、TTL 与预测预算；ledger owner 线程约束。
- `ArSessionControllerTest`：旧 `createMarker`/`markers` API 的显示编号兼容；GUIDE 不能删除 FIELD 标注、FIELD 可删除 GUIDE 标注；pause/close 同时释放 native anchor 与 ledger，pause/resume 后编号不复用。
- `ArSessionControllerTest` Stroke 回归：一笔只创建一个 native anchor；整笔删除/取消释放 anchor 与几何；批次点数上限；author 匹配；失跟踪仅创建 SCREEN_LOCKED point 且不能开始世界 Stroke。
- 既有 AR JVM 回归：`com.lazydoglab.zisee.ar.*`，覆盖旧 v1 协议、frame identity、空间解析、投影、协作和会话生命周期。
- `PlacementResolverTest`：深度连续性与孔洞拒绝、单位法线；Depth/Plane/Feature/Historical Ray Estimate/Screen 结果分支；精确历史帧与 tracking；PoseRefiner 的 surface identity、质量、位移、世界连续性、三次确认与按时间平滑。

验证命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.lazydoglab.zisee.ar.*"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileReleaseKotlin
```

2026-09-11 在 Windows/JDK（Android Studio JBR）执行结果：两条命令均 `BUILD SUCCESSFUL`。第一条运行整组 AR JVM 回归；第二条运行全部 Debug JVM 测试、生成 Debug APK、执行 Debug lint，并编译 Release Kotlin。该结果不代表 Android instrumentation 或真机验证。

## 本地 POINT / Stroke 检查点

2026-09-11 最终运行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:compileReleaseKotlin`，结果 `BUILD SUCCESSFUL`。XML 结果共 326 个 JVM 测试，失败/错误 0，其中 AR 包 77 个。测试数量只代表该工作区快照，不表示 roadmap 完成率。

新增核心回归包含 `StrokeGeometryTest` 的重采样/源帧、拐角、短缺口时限、跨法线停止、anchor 修正后的局部坐标和退化 Ribbon。PoseRefiner 回归补充“同一帧重复不能算三次确认”和“首次 lerp 后继续收敛”，修复首次纠偏就提升 confidence 导致后续被质量门槛阻挡的问题。

在 `emulator-5556` 安装 Debug 与 AndroidTest APK，实际执行：

```powershell
adb -s emulator-5556 shell am instrument -w -e arCameraRender true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e arFramePool true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e arChannel true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e arTap true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
```

四项均返回 PASS。GPU smoke 新增 Ribbon 覆盖像素断言；首次运行发现 32×32 小视口徽章遮挡中心，修复后复测通过。点击 smoke 的预览场景没有有效 AR frame，证明的是图层挂载与触摸路由，不是成功落锚，也不是拖绘全链路验收。截图已查看，临时产物位于 `android/app/build/ar-annotation-ui.png`（忽略目录，不提交）。

## 尚未验证

- 原生 Instant Placement、历史 Depth 实际对齐、法线精度、特征覆盖率与 ARCore anchor 纠偏效果。
- 真实视频上的本地拖绘全链路、取消/前后台竞争、丢失跟踪恢复、编号与视觉去重。
- v2 指导方 Stroke 已在 emulator-5554 通过真实 SCTP/DTLS 双 PeerConnection 回环；仍未验证真实双设备弱网、重连或性能指标。
- Depth、无 Depth 和不支持 AR 的真机降级。
- 双设备延迟、丢包、旋转、前后台与持续 10 分钟通话。

这些项目必须在对应实现完成后补充真实结果，不能由 JVM 合成测试替代。

## 指导方手绘检查点（2026-09-12）

- JVM 覆盖 v2 所有消息往返、64 位帧时间戳、批次边界、非法字段/坐标/序号、能力门控、未加入请求、乱序批次和结果相关。
- 完整任务 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:compileReleaseKotlin` 通过。
- XML 结果共 344 个 JVM 测试，失败/错误 0；基础 instrumentation 也通过原生摄像头、ICE、编解码、发送端上限、ICE 重连与释放检查。
- `emulator-5554 -e arChannel true` PASS，使用两个真实 PeerConnection/DataChannel 验证 v1 与 v2 并存及指导方 Stroke 往返；现场端为合成 endpoint，不代表 ARCore 落锚通过。
- `emulator-5554 -e arTap true` PASS，新增 `ar-remote-guide` 场景确认指导方主画面出现手绘工具并切换到 Stroke 触摸层；预览没有 AR frame，只验证 UI 路由。
