# AR Annotation 实施评估与 Roadmap

更新：2026-09-11。评估代码基线：`ec67ee0`。

需求来源：[AR Annotation Design](../architecture/AR_Annotation.md)。本文完成实现前的改动量分析和分期设计；不代表下述功能已实现，也不代表真机验收通过。

## 1. 结论与范围

这是现有 AR 子系统的中大型扩展，不是从零开发，也不是只替换标记样式。已有相机、GL、历史帧、通信和基本锚点基础；主要工作集中在标注模型、几何质量、手绘、同步和呈现。

按设计第 70 节划分：V1 做 POINT、Stroke、分级定位、即时反馈、Surface Lock、Refinement、历史帧对齐、Undo/Delete；V2 做 WARNING/ACTION、方向箭头、Region 和跨表面分段。多人、Cloud Anchor、语义理解、OCR/AI 单独立项，不纳入本次估算。

建议单名熟悉项目的 Android/RTC 工程师：V1 约 **33–51 人日**，V2 再 **10–17 人日**；预留 25% 集成与机型风险后，总计约 **54–85 人日**。这是工程估算，不是已测工时或交付承诺；设备等待、多人协议和通用跨帧视觉追踪不包含在内。

## 2. 现有实现与增量

代码路径以下均相对 `android/app/src/main/java/com/lazydoglab/zisee/`。目前 `ar/` 下有 22 个生产 Kotlin 文件和 11 个对应 JVM 测试文件；数量不代表功能完成率。

| 模块 / 证据 | 已有基础 | 本次增量 |
| --- | --- | --- |
| `ar/session/ArCoreBackend.kt`、`ArVideoCapture.kt` | ARCore、独占相机租约、GL owner、深度/平面快照、纹理池、关闭 | 特征快照、法线/质量、Instant Placement 适配、预算 |
| `ar/spatial/PoseHistory.kt`、`SpatialGeometry.kt` | 精确源帧查找；默认 3 秒/180 帧；pose、内参、深度、平面 | 有界特征与表面身份；纠偏所需证据；保持旧帧不可变 |
| `ar/spatial/SpatialResolver.kt` | 历史深度优先、平面求交；多边形失败后允许 0.25 m 边缘带 | normal/confidence、Feature、Local Surface、候选分级；边缘带不能算严格 polygon 命中 |
| `ar/session/ArSessionController.kt` | 32 anchors、512 已用 ID、防重放、删除/清空、tracking/生命周期 | 当前非 TRACKING 直接拒绝；改为独立标注意图与可选空间结果、状态与锚点 owner |
| `ar/annotation/Annotation.kt` | 2D 点、源帧引用、空间请求 | 标注聚合模型、作者、编号、样式、选中、Stroke 事务 |
| `ar/render/MarkerProjection.kt`、`ArCameraRenderer.kt` | PIN/ARROW/CIRCLE 点精灵烧入同一源帧；失跟踪不投影 | Surface/HUD 分层、编号、状态动画、Ribbon、透明度、尺寸限制、VBO 复用 |
| `ar/render/ArFrameIdentity.kt`、`ArVideoCodecs.kt` | H264 SEI 关联源帧；不可确认则无空间引用 | 复用；手绘每个采样绑定实际展示帧；不以最新时间戳替代 |
| `ar/annotation/VideoPointMapper.kt` | FIT/FILL、旋转、镜像逆变换 | 连续手势逐帧 geometry；屏幕空间 fallback 的坐标域与有效期 |
| `ar/annotation/ArProtocol.kt`、`ar/collaboration/` | v1 严格平面 JSON、可靠有序通道、会话加入、回执与限流 | 能力/版本迁移、批量 Stroke、幂等、revision、权威状态同步、作者权限 |
| `call/CallViewModel.kt`、`ui/ActiveCall.kt`、`ui/CallScreen.kt` | 点击、类型选择、撤销/清空入口 | 拖绘、即时 preview、超时/拒绝反馈、选中删除、按整笔撤销 |

现有 ARROW/CIRCLE 是单点图形，不等于带起终点的 Arrow 或四角 Region。现有撤销入口也不等于权威同步的 Stroke 事务。

## 3. 实现前必须固定的契约

1. **保持精确源帧匹配。** 设计的 nearest-frame 示例不替代当前可靠身份链路。缺帧可以显示明确的 2D 意图，但不能猜一个历史 pose，更不能使用当前 pose。普通 codec/I420 等丢失身份时保留视频与 2D 能力。
2. **SCREEN_LOCKED 不意味着可以无条件升级。** 无历史 pose 的屏幕点在相机移动后不能确定原目标；只有可验证的跨帧对应或有效世界候选才允许升级。否则保留有有效期的 2D 反馈并要求重新点选。V1 不承诺任意失跟踪输入都能自动变成锚点。
3. **Instant Placement 区分当前帧与历史请求。** ARCore 原生 API 面向当前 Frame，且需要 tracking 和足够特征。远端历史请求先使用历史射线的近似距离候选（独立命名为估计方法），不能声称它等价于原生 InstantPlacementPoint；未经对应验证不能对当前 Frame 重放旧 x/y。参见 [Instant Placement 指南](https://developers.google.com/ar/develop/java/instant-placement/developer-guide) 与 [Frame API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)。
4. **法线是额外数据。** 现有单深度像素只能给位置；需要局部深度邻域拟合法线并检测边缘/空洞，或使用历史平面法线。Feature 不一定有可靠表面朝向；原生 Instant Placement 的 +Y 是重力方向，也不能当任意表面法线。Depth hit-test 与历史深度重建不能混同，参见 [Depth 指南](https://developers.google.com/ar/develop/java/depth/developer-guide)。
5. **状态正交。** placement 状态与 selected 分开；选中不能覆盖 LOST/SCREEN_LOCKED。屏幕坐标与世界 pose 用不同结果类型，不给 2D fallback 填假 pose。
6. **Refinement 需要证据。** 固定历史快照不会自己变好；已有候选重投影至新帧后，检查表面身份、距离、法线、tracking 与质量滞回，再平滑视觉局部偏移。按时间常数计算 lerp/slerp 权重；不要逐帧 detach/create anchor。丢失世界连续性时失效旧候选。
7. **协议不能直接添加示例数组。** v1 拒绝嵌套对象/数组和未知字段，错误可关闭 AR 通道。先确定两端兼容策略，再引入有界 v2；旧端继续 v1 或明确禁用新工具，不能发送试探性未知消息。纳秒继续用十进制字符串。
8. **现场端权威。** 从已鉴权通话角色推导 owner；现场分配稳定 displayNumber 和 revision；删除后不复用编号；UUID 用于操作与墓碑。指导端 preview 与权威确认分开。提供短期 preview ID/revision 关联，处理已烧入视频的结果与本地预览重复显示。
9. **沿用源端渲染。** V1 继续把世界标注合成进 AR 视频，远端只做即时反馈层。由同一现场坐标系渲染，避免新增双端世界模型。40–60 dp 不能直接换成编码像素；按实际视频视口映射，承认源端无法同时满足所有远端显示密度。
10. **不扩大媒体架构。** 不改 signaling 服务或引入 SFU；不把相机增强管线接入 AR 源；先复用 Kotlin/OpenGL ES，不新增大型 3D 引擎。

## 4. Roadmap 与验收

各阶段按依赖顺序推进；每阶段可以拆成多个独立 commit。人日包含实现、相关自动化和文档；最终设备回归单列 P5。

| 阶段 | 范围与产出 | 验收门槛 | 人日 |
| --- | --- | --- | --- |
| P0 契约和模型 | 固定上述边界；Annotation、PlacementResult、状态/作者/编号；v2 兼容方案与预算 | 2D/3D 不混淆；编号不复用；旧 v1 回归；明确 scope | 3–4 |
| P1 定位与降级 | 扩展现有 resolver；历史法线/特征；局部表面；当前帧原生 Instant；历史近似候选；SCREEN_LOCKED | 深度/平面/特征/估计/2D 各分支；旧帧、无 tracking、孔洞、超距和错误法线测试 | 6–9 |
| P2 POINT 完整闭环 | AnnotationManager、可选 anchor、PoseRefiner；编号/分段 ring/HUD；本地和远端 preview、结果状态、删除 | 无表面仍有即时反馈；纠偏不跨面；失跟踪/恢复；超时与拒绝无残留；资源释放 | 6–9 |
| P3 本地 Stroke | 距离重采样、逐点帧引用、Surface Lock、局部求交、短缺口恢复、轻平滑、局部坐标 Ribbon | 空洞短缺口连续；超预测预算转 2D/停止外推；尖角保持；每笔少量 anchor；整笔撤销 | 6–10 |
| P4 远端 Stroke | begin/append/end/cancel、seq/revision、大小受限批次；权威确认、墓碑、重入快照、权限 | 重复/缺失/迟到批次、clear 后迟到 append、leave/hangup 中断、背压和混合版本；不逐触摸事件发包 | 6–10 |
| P5 V1 验收 | 诊断计数、GPU/通道集成测试、双设备视频点击/绘制、持续通话性能回归 | 帧身份正确；前后台/退出无泄漏；弱网降级不拖垮 RTC；列明设备实测结果 | 6–9 |
| P6 V2 | WARNING/ACTION、方向 Arrow、Region、受控跨表面分段与交互 | 四角/端点一致性、法线突变断段、编号/撤销/同步回归，双设备补测 | 10–17 |

关键路径：P0 → P1 → P2 → P3 → P4 → P5 → P6。协议模型在 P0 定义、POINT 消息在 P2 接通、批处理在 P4 完整实现，避免做完 Stroke 后才发现数据结构无法发送。

首个可体验里程碑是 P2，累计约 15–22 人日；此时仅 POINT 完整闭环，不能标为整个文档实现完成。P5 才是 V1 完成点。

## 5. 改动量估算

以下是基于模块拆分的区间估计，指代码增改规模，不是净新增行数；各阶段会重复修改相同文件，不能把文件数逐阶段相加。

| 范围 | 预计文件与增改行数 |
| --- | --- |
| V1 生产代码 | 修改约 12–18 个现有文件，新增约 12–20 个文件；约 2,500–4,500 行 |
| V1 自动化 | 修改/新增约 10–16 个测试文件；约 1,500–2,500 行 |
| V1 文档 | 本 roadmap、AR_FRAMEWORK、协议/交互/验证记录等约 3–5 份 |
| V2 增量 | 约 800–1,500 行生产代码、500–900 行测试；复用 P1–P4 管线 |

最大不确定项是历史候选的有效纠偏、真实 Depth 对齐、网络状态一致性和渲染视觉质量。若要求“丢失历史信息后仍自动追踪原物体”，需加入视觉跟踪/重识别研发并重新估算，不应藏在 PoseRefiner 工时里。

## 6. 资源预算与验证策略

- 保持历史缓存现有双上限（3 秒、180 帧），新增特征/法线缓存另设字节预算，不保存原生 Frame/Image；原生点云/Depth 使用后及时释放。
- 起始预算建议：每笔最多 512 个重采样点、总计 8,192 点；原生 anchor 总数先保留 32；估计轨迹最多 100 ms/5 cm。以上都是待测试初值，超限必须明确反馈，不能静默丢失已确认内容。
- Stroke 批量发送建议 20–30 Hz 起步，每批最多 16 点且受最终编码字节数限制；保持 4 KiB 单包预算作为起点。现有 30 条/秒令牌预算还包括控制消息，必须保留控制容量并降低绘制发送频率；满队列时不无限积压。
- 性能记录：投影/纠偏/mesh 更新耗时、GL P95、总顶点/anchor/缓存、pending 与丢弃原因、视频 FPS 与通话连续性。避免记录具体坐标、深度、媒体内容或用户标识。
- JVM：历史坐标/法线/候选排序、退化几何、帧率无关纠偏、状态迁移、编号/权限、Stroke 采样/边界、协议与墓碑。
- GPU/集成：Ribbon 退化点、角点/透明度、相同源帧叠加、retain/release、通道实际收发与退出；合成测试只证明算法/接口。
- 真机：至少一台有 Depth、一台 ARCore 支持但无 Depth 的设备，再覆盖不支持 AR 的降级；两端移动拍摄时点击/绘制、300–800 ms 延迟场景、丢包、旋转、前后台、相机恢复、持续 10 分钟通话。实际误差与耗时测后记录，不预先承诺毫米级精度。
- 每步按项目规范检查 diff、UTF-8、敏感信息并 commit。相关 JVM 测试后执行必要 Debug 构建；阶段集成跑 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileReleaseKotlin`；涉及 GPU/通道时补 AndroidTest 与实际运行。

## 7. 本次交付状态

2026-09-11 已进入实现，并按用户要求在当前检查点准备交接。**整份路线图尚未完成，不能标记 P2 或 P5 验收通过。**

| 阶段 | 实际进度 |
| --- | --- |
| P0 | 标注模型、作者、编号、revision、屏幕/世界类型、预算和 controller 权限已落地；v2 协商/权威快照仍待实现。 |
| P1 | 新 PlacementResolver、保守深度邻域法线、平面边缘带分级、有界特征快照、历史射线估计、SCREEN_LOCKED TTL 已落地；原生 Instant Placement 仅允许本地当前精确帧；真机精度和资源预算待验收。 |
| P2 | 本地 POINT、可选 native anchor、证据门控纠偏、表面环/编号 HUD、短期触摸 preview、删除/撤销基础已接通。远端仍走旧 v1；结果 revision 关联去重、选中删除 UI、完整超时同步待补。 |
| P3 | 本地手绘入口、逐样本帧引用、距离重采样、Surface Lock、100 ms/5 cm 缺口限制、一笔一 anchor、Ribbon、整笔取消/撤销已接通。轻平滑、完整 Stroke 状态同步、真机长时绘制/性能验收仍待补。 |
| P4 | 指导方 Stroke 的独立 v2 通道、能力握手、顺序批次、现场权限/执行、权威结果和背压已接通；旧端保持 v1 且不显示手绘。完整内容 revision、重入分页快照和跨连接幂等仍待实现。 |
| P5 | JVM、构建/lint、模拟器 GPU/帧池/旧通道/点击入口通过；无本次真机或双设备性能验收。 |
| P6 | 尚未开始；旧 ARROW/CIRCLE 仍是单点图形，不是方向 Arrow/Region。 |

接手入口：[AR_ANNOTATION_HANDOFF.md](AR_ANNOTATION_HANDOFF.md)。实际命令和结果：[AR_ANNOTATION_VALIDATION.md](AR_ANNOTATION_VALIDATION.md)。
