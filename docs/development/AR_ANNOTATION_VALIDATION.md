# AR Annotation 验证记录

更新：2026-09-11。

## 标记可读性修正

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
- v2 网络同步、远端 Stroke 与性能指标。
- Depth、无 Depth 和不支持 AR 的真机降级。
- 双设备延迟、丢包、旋转、前后台与持续 10 分钟通话。

这些项目必须在对应实现完成后补充真实结果，不能由 JVM 合成测试替代。
