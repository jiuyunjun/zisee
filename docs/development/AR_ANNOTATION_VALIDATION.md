# AR Annotation 验证记录

更新：2026-09-11。

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

## 尚未验证

- P1–P6 的定位、渲染、Stroke、v2 网络同步和性能指标。
- Android GPU/instrumentation 测试。
- Depth、无 Depth 和不支持 AR 的真机降级。
- 双设备延迟、丢包、旋转、前后台与持续 10 分钟通话。

这些项目必须在对应实现完成后补充真实结果，不能由 JVM 合成测试替代。
