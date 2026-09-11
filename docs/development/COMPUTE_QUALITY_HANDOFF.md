# Compute-for-Quality 持续交接

更新：2026-09-11。用户要求实现专项设计、随时留 handoff，结束后 push。

用户后续强调：随时提交、push 和维护 handoff。执行节奏调整为每个独立可验证小步都更新本文、commit、push，不等整轮结束。

## 当前状态

- 基线 main；工作区只有用户新增的 `docs/architecture/Compute-for-Quality.md`。不覆盖用户改动。
- 已阅读设计、CameraAdaptationPolicy、NativeRtcSession、DualCameraCapture、MediaStats 及此前通话 handoff。
- 已实现 P0：独立计算等级/热预算控制、GPU 高质量缩放与因果时域降噪，复用已有 EGL/纹理池模式；加上基础 P1 场景和发送 FPS 策略。完整专项并未全部完成，后续边界见下表。
- 控制器、thermal/battery 采样和 GPU 链路已接入 debug 的前后摄，release 暂不开启，等待真机画质/性能门槛。最终验证与 push 状态见文末。
- sources.jar 实际仅包含 README，接口改用当前官方源码和本地 AAR 的 javap 确认。

## 必须保持的边界

- ComputeLevel 与现有 CameraTier.C0–C4 是不同维度，不能替换原有拥塞控制。
- 屏幕共享不装相机处理器；AR 源需明确旁路以保留帧身份、时间戳和空间映射。
- 独立前后摄像头处理状态；切换/停止/尺寸或旋转变化清空历史。处理异常自动旁路，不影响通话。
- GPU 纹理输出在消费者 release 前不可复用；有界资源，不能积压帧；关闭需等待在途引用释放。
- 采集与发送尺寸已有初步解耦（避免每次 ABR 降档重开 HAL），不能退回频繁重开。
- thermal headroom 官方建议每 10 秒最多一次，API 30+；NaN 是未知，不是冷却。thermal status API 29+，每秒读取。
- 不在用户真机安装 APK；模拟器验证不能替代功耗、拖影、双摄、真实码率画质评估。

## 路线与验证记录

- P0：控制器、缩放、时域降噪（已实现，真机预算/画质尚未验收）。
- P1：场景/暗光发送 FPS（已实现）；人脸 ROI、编码复杂度（未实现）。
- P2：接收端 SR/去伪影/细节模式（待实现或明确条件）。
- P3：设计列为可选 ML 丢帧掩盖与语义增强，无模型和实测资料，不应默认生成内容。
- 真机 benchmark、PSNR/SSIM/VMAF 对照和主观验收需要真实数据，尚未开展。

## 参考

- 设计：`docs/architecture/Compute-for-Quality.md`。
- Android Thermal API：https://developer.android.com/games/optimize/adpf/thermal
- WebRTC 接口以缓存的 144.7559.15 AAR classes.jar 的 javap 结果为准；该版本 sources.jar 只有 README，不能当作源码依据。
- Java：`C:/Program Files/Android/Android Studio/jbr`；构建从 `android` 执行 Gradle wrapper。

## 2026-09-11 P0 进展

- `3eaf682`：独立计算预算策略、热/电量采样、5 项纯单测；同步纳入用户设计并修正 headroom 采样周期。
- GPU 实现使用独立 EGL 线程、16 点面积缩放、前两帧非递归运动/边缘门控降噪，复用 ArFramePool（3 个输出槽）及独立 3 个历史槽。主视频不做 CPU 像素 readback，场景摘要的 576 bytes 例外见下文。每摄像头最多约 48 MiB RGBA（1080p），尺寸受限，超限旁路。先缩放再时域滤波，预算按发送像素规模控制；没有光流补偿或递归历史累积。
- 编译曾遇到 ThreadUtils 的 Runnable/Callable 重载选择错误，已改显式 Callable；完整单测与 Debug/AndroidTest APK 已构建，lint 后续已通过。
- emulator-5554 `-e computeQuality true` PASS：RGB GPU 路径、场景跳变、尺寸/旋转/时间戳、保留帧稳定、池耗尽回退、AR 旁路、关闭后保留帧可读。仍需追加真实 OES shader 与降噪效果数值断言。
- AR 接管立即旁路；屏幕源不安装处理器；单摄切换、采集停止、尺寸/旋转/变换变化或帧间隔 >250ms 清历史。
- 处理耗时使用包含 GPU 完成的 elapsed P95（60 帧 nearest-rank 窗口），30 样本后 >5ms 或任一单帧 >20ms，本次通话锁定旁路；未宣称性能提升。shader 编译已前移到初始化，驱动首次实际 draw 的额外成本仍可能导致保守旁路。

## 最新验证与接口边界

- RGB 和 OES GPU 仪器测试已 PASS；OES 首次失败源于测试假定上下方向，改为与相同 SurfaceTexture matrix 的 WebRTC GlRectDrawer 基线逐角比较，并要求四个不同的有效颜色，现已通过。降噪测试确认小亮度扰动被减弱、场景大跳变不拖影。
- P1 场景策略已加入：16×9 RGBA 摘要、每 500ms 最多一次（576 bytes readback），含在处理延迟预算内。亮度/帧差/纹理/高频残差是启发式，不是人脸或语义识别。静止 3 秒发 24fps，暗光低运动 3 秒发 20fps；运动立即恢复 30fps（受原网络档位上限约束）。只降低发送帧率，未更改传感器曝光/ISO，不宣称曝光改善。
- javap 已验证 144.7559.15 AAR：HardwareVideoEncoder、MediaCodecWrapperFactory 是包私有；HardwareVideoEncoderFactory 没有公开 MediaFormat/complexity 注入入口。未反射/覆写 org.webrtc 包；动态 complexity/ROI QP map 需要维护 upstream/fork 接口。
- 修正 RTCStats callback 后时间新鲜度（thermal 采样在 callback 前，不能用它的时间减去新 stats 时间）；P95 现在包括 EGL handler 排队与 GPU 完成。池耗尽/异常也完成输入纹理读取后再回原始帧，避免摄像头复用竞态。
- 调试详情显示每个 camera slot 的计算等级、理由、场景、P95、处理/旁路帧数和失败状态。front 是原始单摄 slot，切后摄时 slot 名称仍为 front。
- 首阶段回归、diff 检查及提交已完成，`3eaf682` 和 `2d68091` 已 push 到 origin/main。用户随后要求“继续”，正在推进下一阶段：真实编码耗时/QP 观测与设备能力记录，再处理 ROI/编码质量控制接口。

## 剩余实现和真机验收

| 设计能力 | 实际状态 / 下一步 |
|---|---|
| C0–C3 计算预算 | 已实现；门槛为工程初值，thermal status 每秒检查、headroom 每 10 秒预测未来 10 秒 |
| C4 Burst | 策略 API 与超时/冷却单测存在；没有产品入口，也不提升分辨率/码率，不能宣称完整 Burst 功能 |
| 高质量缩放/降噪 | 已接前后摄；16 tap 面积采样和运动/边缘门控，不是光流补偿或 AI SR |
| Scene/low-light | 亮度/运动启发式已接入 ACTIVE camera plan 的发送 FPS；未做曝光控制、人脸/手部识别 |
| ROI/complexity | ROI 已有 opt-in Debug bundled ML Kit 检测、相机有界输入和数量诊断；尚未做背景处理/QP map，默认 APK 不含 SDK。complexity 仍需 WebRTC 扩展和设备验证 |
| Codec profile/QP/P95 encode | Java 硬件路径已增加 encode→callback P50/P95/P99 和可选逐帧 QP；原生软件不可观测时保持未知/RTC mean。纯 MediaCodec 内部耗时与 DeviceProfile 尚未实现 |
| Receiver SR/去伪影 | 未实现；没有引入模型或伪装双线性缩放为 SR；需要独立 receiver budget 和窗口可见性策略 |
| P3 ML concealment | 未实现；设计本身列为可选，不生成不存在的内容 |
| DeviceProfile/客观画质评分 | 尚未建立真机基准数据库；当前只有在线处理延迟与确定性合成 GPU 测试 |

### 已知限制

- Release 的 `VIDEO_COMPUTE_QUALITY=false`；Debug 默认 true，可传 `-Pzisee.computeQuality=false` 做 A/B。两个 APK 使用同一 debug 签名。
- 慢 GPU 可能在第一个处理帧出现开销，超过 20ms 后立即旁路。模拟器曾测得数百 ms 并退出增强；不把模拟器数据当机型性能指标。
- 处理器初始化/GL 失败、非纹理帧、超 1080p 像素数时回原生管线；无帧队列。每轨纹理内存上限不代表系统已分配固定大小。
- C0 当前关闭新增 GPU 工作，但不因此重开相机降低采集分辨率；旧 ABR 和原有严重热降档继续负责媒体规格。
- 暗光 FPS 调整只节省发送帧，不保证传感器增加曝光。后续更改 Camera2/CameraX 时必须顾及此前小米 HAL 重开问题。
- GPU 失败本次通话不重试；普通热恢复按 25 秒连续证据逐级升档。未知 headroom/统计不授权 C2+。

### 复现实验

1. 从 `android` 运行 `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:compileReleaseKotlin`，JAVA_HOME 见上文。
2. 仅模拟器安装 APK 后运行 `adb -s emulator-5554 shell am instrument -w -r -e computeQuality true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation`。包含 OES 方向与 WebRTC 基线比较、4:1 条纹缩放数值、时域降噪、切场景、C0/AR 旁路及引用生命周期。
3. 不带 `-e computeQuality true` 运行同一 instrumentation：原生相机→编码→RTP→解码回环、ICE restart、资源释放。ACTIVE 日志断言已从旧 `RTC_QUALITY_CHANGED` 改为 `RTC_ADAPTATION_PLAN`，仍要求无参数拒绝并收到视频。
4. 真机同场景、同 codec/码率、同分辨率比较开/关增强，至少覆盖暗光人脸、手势、快速平移、文字/PCB。记录丢帧、处理 P95、encode mean、热状态与 headroom，持续至少 10 分钟；PSNR/SSIM/VMAF 需要对齐原始参考片段，不能从当前日志推导。
5. 本轮未在用户真机安装，未验证双摄硬件、热稳定或真实画质收益。旧切网与 AR 真机待办见 CALL_FIX_HANDOFF.md。

## 最终验证记录（2026-09-11）

- JVM：261 tests，0 failures，0 errors，0 skipped（新增 5 项 compute 和 3 项 scene）。
- Debug APK、AndroidTest APK、lintDebug、compileReleaseKotlin 均通过；构建日志在未入库的 `android/app/build/compute-quality-*.log`。
- GPU instrumentation：PASS。时钟注入用于像素/引用测试与 21ms 截止断言，防止软件模拟器的速度使算法测试误走旁路；该测试不能证明真实 GPU 在 5ms 内。
- NativeRtcSession instrumentation：PASS。使用真实计时，模拟器首帧 GPU 约 338ms，处理 1 帧后 `failed=true` 自动旁路；仍完成 camera→encode→RTP→decode、ICE 重启恢复和 release。front camera 不可用，切摄测试明确 SKIP；不能替代双摄真机验证。
- 曾失败的用例与修正：OES 上下方向假定→改为 WebRTC 基线逐角断言；旧质量事件→按 ACTIVE/OFF 选对应事件；实时预算保护触发导致像素测试旁路→为确定性算法测试注入时钟，真实预算继续由 native 回环验证。未放宽生产 20ms/5ms 门槛。
- 首个提交：`3eaf682 feat: add thermal-aware video compute budget policy`。
- 实现提交：`2d68091 feat: add bounded GPU camera quality processing`；两个提交已成功 push 到 origin/main（远端从 c7be75a 前进到 2d68091）。本轮工作区随后因继续任务而再次更新。

## 下一阶段进行中：编码观测与能力

- 已读 `ar/render/ArVideoCodecs.kt`：现有 ArEncoderFactory 对 Java 硬件 H264 包装 AR SEI，其他 codec 返回 DefaultVideoEncoderFactory；软件回退是 WrappedNativeVideoEncoder，不能盲目套 Java encode/callback 包装器。
- 计划在 Java 硬件编码器边界记录 encode 调用至 encoded callback 的耗时分布与 QP，不能把它称为 MediaCodec 纯内部耗时；保留原生软件回退和 AR 身份。
- 设备能力记录与真机实测画像区分。不能仅凭 codec 名字/复杂度 range 就认为动态配置可用。
- 本步实现完成：EncoderTimingWindow/FrameSourceRegistry、MeasuredVideoEncoder、SourceTimestampProcessor；Java 硬件包装位于 VideoEncoderFallback 内，H264 外层仍保留 AR SEI 包装。
- CameraQualityProcessor 在增强/旁路两种情况下均记录 source timestamp；屏幕仅装 identity-only observer，不做相机滤波；GPU 初始化失败也回到 identity-only observer。
- 观测保留最近 2 秒、120 个完成样本、128 个 pending、256 个 source timestamp；冲突归属为未知。记录 callback P50/P95/P99 和可选原始 QP，不保存媒体 payload。
- 调试详情显示每个编码实例与 track 的分位数/QP。可归属且 >=10 个样本的 callback P95 优先用于 compute budget；native-only 软件编码保持原样，缺失时仍使用 RTC mean，不假报 P95。
- 新增纯单测已通过：分位数、乱序回调、拒绝/重复/超时、有界窗口、双摄归属冲突、原始帧/编码图像引用保持、初始化/释放、screen 纯观察、P95 压力保护，以及 MeasuredVideoEncoder→ArVideoEncoder 组合的 SEI/QP/引用保持。
- 完整 Debug/AndroidTest 构建、Release Kotlin 编译、lint 已通过；新增组合单测后全量 **272 项单测通过，0 failures/errors/skipped**。
- 新增 `-e encoderTiming true` 仪器路径：合成 AR RGB→H264→RTP→decode，既验证 AR identity 保留又要求真实 Java 编码回调能归属 back track。模拟器执行 **FAIL: H264 unavailable**，止于前置 codec 能力检查（尚未进入编码）；不是测得 encoder callback 成功，必须在支持 H264 的真机补测。
- `-e computeQuality true` 在新版本回归 PASS。软件回退 native 回环也 PASS（实际收帧、ICE restart、资源释放；模拟器首帧过预算退出增强）。没有为通过测试强开不支持的 H264，也没有把硬件失败改成测试成功。
- 本步提交意图：`feat: observe per-track encoder callback latency and qp`。接下来实现设备能力记录与测量画像的明确分层，不盲目启用 complexity。

## 设备能力快照进行中

- 编码观测已提交并 push：`07d6fb1 feat: observe per-track encoder callback latency and qp`。
- 新增 CodecCapabilityProbe：读取 MediaCodecList REGULAR_CODECS 的 H264/H265/VP8/VP9/AV1 编码器/解码器；API 29+ 去除 alias，读取 OEM 硬件/软件声明；低版本保持 UNKNOWN。
- 记录 Surface input、complexity range、CQ/VBR/CBR、声明支持的 360p/540p/720p/1080p30/1080p60、maxInstances；WebRTC hardwareFormats 独立列出，不能按 codec 名字推断选中了哪个 Android component。
- 仅在 debug compute 开启时、RTC worker 初始化阶段读取，不创建 MediaCodec 实例、不改变协商顺序。查询失败记录事件并保留不完整状态，不抛到通话主流程。
- 新增 `-e codecCapabilities true` 仪器路径输出能力表，模拟器 **PASS**：SDK 37，15 项组件，failedQueries=0，WebRTC hardwareFormats 为空。系统有 c2.android.avc.encoder，但声明为 SOFTWARE，解释了此前 H264 instrumentation 的前置失败。系统有软件 AV1/HEVC complexity range，也不能据此启用手机软件慢编码。
- Debug/AndroidTest 构建、Release Kotlin 编译、272 单测、lint 均通过。WebRTC 格式读取失败显示 UNKNOWN，与成功读取但列表为空（none）明确区分。
- 本步只读取声明，不做 benchmark、不自动改 codec/complexity、不持久化用户或设备标识。提交意图：`feat: expose runtime video codec capability declarations`。

## 持续提交检查点：跨线程时间戳归属

- 能力快照已提交并 push：`eaa9365 feat: expose runtime video codec capability declarations`。
- 修复 FrameSourceRegistry 时钟读取与 monitor 获取顺序相反时误删新记录的问题。只删除超过 2 秒的旧记录；查询相对当前采样时钟尚未发生的记录返回未知，但保留记录供后续查询。重复记录的观测时间取最大值，避免较晚取得锁的旧观测缩短冲突标记寿命。
- 回归覆盖双摄不同时间戳记录保留、同微秒时间戳冲突在逆序时钟下持续未知、冲突过期后可重新归属。全量 274 项 JVM 单测（0 failures/errors/skipped）和 Release Kotlin 编译通过；此改动不涉及 GPU 像素或硬件接口，没有追加真机能力结论。
- ROI 选型仍在评估，尚未加入依赖或像素 readback。平台 android.media.FaceDetector 要求 RGB_565，公开接口没有 close，原生销毁依赖 finalize；不适合直接承诺可控的逐通话释放。参考：https://developer.android.com/reference/android/media/FaceDetector 。ML Kit 方案还需处理模型大小、SDK 遥测与当前隐私说明的兼容性，以及检测耗时和过期结果回退；不能将候选方案标记为已实现。
- 下一步仍为 ROI 的可维护检测器与有界处理路径；complexity/QP map、Receiver SR、DeviceProfile 和真机验收状态见上表。用户要求每个独立改动验证后及时 commit、push，并随检查点维护本文。

## ROI 检测基础设施检查点（2026-09-11）

- 新增 `RoiAnalyzer`、`RoiDetector`/`RoiInput` 合约和归一化 `RoiBox`。此步是独立基础模块，**未接入 NativeRtcSession / CameraQualityProcessor，也没有人脸检测器或画质收益**，不新增依赖、像素 readback 或权限。
- 每个 source 应独立拥有 analyzer：单 worker、最多一个在途输入、没有等待帧队列、最多 2Hz。`canSubmit` 用于昂贵输入准备前的预检，`submit` 再次检查并无条件接管输入释放责任，包括忙时、关闭后和几何不匹配的拒绝路径。
- 结果最多 8 个合法矩形，坐标定义为旋转后的正向图像归一化坐标。未来 detector adapter 必须撤销自身 resize/letterbox；未来 shader adapter 需要显式映射到纹理坐标，不能直接混用 AR、sensor 或 GL 坐标。
- 生命周期用 revision 隔离：切源、尺寸/旋转/crop/transform 变化需更新 `RoiGeometry`（同尺寸变换更新 generation）；停采、C0、AR 接管调用 `configure(null)`。禁用后再回到同一 geometry 也不会接受旧任务结果。
- 检测结果同时受提交后的单调时间与源帧时间差约束，均不超过 500ms；未来结果、时钟倒退、超时检测结果均不可用。`null` 表示未知；空列表只表示有效检测未发现区域，未来背景处理不能将未知当成无人脸。
- 非法/过多结果、检测或输入释放异常进入 FAILED，清空结果；只向 owner 上报固定失败阶段，不上报 exception 内容。`close()` 不阻塞调用线程，在在途任务结束后由同一 worker 释放 detector；同步原生检测必须能够返回，不能声称能强制中断失控的 native inference。
- 下一步：选定可显式释放、依赖及隐私可接受的 detector，提供有界低分辨率输入适配，接入 source 生命周期和 compute budget 后，再实现 ROI 的 GPU 背景处理。当前还不具备端到端 ROI 功能。
- 验证通过：281 项 JVM 单测（新增 7 项 ROI，0 failures/errors/skipped）、assembleDebug、compileReleaseKotlin、lintDebug。日志为未入库的 `android/app/build/compute-roi-validation.log`；本步未修改 GPU 或硬件调用，未安装用户真机，也未宣称真机性能或人脸检测效果。
- 本步提交意图：`feat: add bounded asynchronous ROI analysis lifecycle`。

## ROI 检测器与相机输入接入（2026-09-11）

- 上一检查点 `8d7814d` 已提交并 push。
- 接入说明和 SDK 条款/遥测/构建隔离见 [Face ROI 检测实验](../architecture/FACE_ROI_EXPERIMENT.md)。
- 新增默认 false 的 `zisee.faceRoi`；仅 opt-in Debug 引入 bundled ML Kit 16.1.7。Release 始终使用无依赖 provider。不是默认开启人脸检测，也不是已验收的 ROI 画质优化。
- CameraQualityProcessor 在 C2+ 从校正后的 RGB 纹理提供最多 640×360 的低频快照，worker 负责 RGBA→正向 ARGB/旋转/检测；双 source 独立，停采/切源/AR/C0 和失败均使结果不可用。调试详情增加 ROI 状态与有效框数量。
- 生命周期接入时修正 geometry 更新可绕过限频的问题；即使频繁切换也保留最近提交时间。显式处理可选 detector 的 native linkage 失败。
- 新增 RGBA 方向/色序/尺寸上限/释放纯测试与 `-e faceRoi true` 仪器路径。使用公开 NASA 人像，覆盖 GPU 取样后四种旋转的实际检测框；不使用用户媒体作为 fixture。
- 未做背景降质或 QP map；独立推理延迟预算、真实人脸召回率和持续热/功耗验收仍待完成。GPU P95 只包含取样/readback，不包含异步模型推理。
- 验证通过：默认与 `-Pzisee.faceRoi=true` 两种构建均完成 assembleDebug、assembleDebugAndroidTest、lintDebug；compileReleaseKotlin 通过；286 项 JVM 单测 0 failures/errors/skipped。日志为未入库的 `android/app/build/roi-*.log`。
- 提交意图：`feat: wire opt-in face ROI detection into camera processing`。

## 真机日志观察（2026-09-11，Xiaomi nezha，API 36）

- 12:56 通话中 `RTC_COMPUTE_BYPASS front:budget` 在相机启动约 0.5s 后触发，`p95us=5742`，本次通话锁定旁路。降噪/缩放未生效，C2+ 才启动的 ROI 也从未执行；日志无 ML Kit 活动。该次通话**不能**作为 ROI 真机验证。
- 旁路日志只记录 `budget`，无法区分单帧 >20ms（首帧驱动/分配开销）还是 30 样本 P95 >5ms。下一步：旁路事件带触发规则与处理分辨率，处理首帧预热，再在该机用 faceRoi 构建复测；不放宽 5ms/20ms 生产门槛。
- 对端挂断后 SCTP 关闭前，RTC 线程出现两条 `RTC_MEDIA_FAILED`，推断来自数据通道关闭后的 presentation 发送失败（日志无异常类型，属推断）；预期内失败被记为 error，待降级。
- `libEGL no current context` 均紧随 SurfaceTexture disconnect，属 WebRTC EGL 释放噪音。本次通话无崩溃/ANR。另有 09-10 01:45 WebRTC `DecodingQueue` SIGABRT（进程启动 3s，早于本轮改动，无 abort message），需符号化，未跟进。
- ROI 检查点已提交并 push：`e07feaa`。

## 处理预算诊断与首帧预热（2026-09-11）

- 超时判定抽出为纯 `PreprocessBudget`：前 3 个处理帧为预热，单帧 ≤50ms 不触发旁路、不进入 P95 窗口；超过 50ms 记 `WARMUP` 并立即旁路。预热后 20ms 单帧（`FRAME`）与 30 样本 P95 >5ms（`P95`）门槛不变，窗口 60。预热只在处理器生命周期开始时计一次，旋转/切源不重置，避免反复获得 50ms 豁免。
- 代价：通话开头最多 3 帧、每帧最多 50ms 同步阻塞采集线程。这是为区分驱动首帧开销而接受的一次性成本，需真机确认是否可接受。
- `RTC_COMPUTE_BYPASS` 现记录 `name:budget:<WARMUP|FRAME|P95>:n=<样本数>:us=<本帧>:p95us=<窗口P95>:<宽>x<高>`，下次真机日志即可判断旁路原因与处理分辨率。预热期间 `p95Ms` 为 null（计划日志显示 -1）。
- 数据通道 presentation/share 发送失败时，只有通道仍为 OPEN 才记 `RTC_MEDIA_FAILED`；挂断竞态导致的关闭不再计为媒体错误。
- 验证：290 项 JVM 单测（新增 4 项 budget）0 failures/errors/skipped；assembleDebug、assembleDebugAndroidTest、lintDebug、compileReleaseKotlin 通过（`android/app/build/budget-validation.log`）。emulator-5556 `-e computeQuality true` PASS；默认 native 回环在授予 CAMERA/RECORD_AUDIO 后 PASS（首次失败是该模拟器未授权相机，不是代码问题），模拟器首帧超 50ms 触发 WARMUP 旁路，符合预期。未在用户真机安装。
- 下一步：用户在 nezha 上以 `-Pzisee.faceRoi=true` 构建复测，根据新旁路日志决定：若为 `WARMUP`/`FRAME` 首帧类，考虑初始化时预热 draw；若为稳定 `P95`，需降低处理分辨率或拆分工作，而不是放宽门槛。
