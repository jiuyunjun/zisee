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
- WebRTC 接口以缓存的 144.7559.15 sources.jar 为准。
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
| ROI/complexity | 未实现；需要可维护的 WebRTC MediaCodec 配置扩展，以及能力检测/拒绝回退和设备验证 |
| Codec profile/QP/P95 encode | 原硬编选择保留；encodeMs 是 RTC 区间均值，未新增原始编码耗时分位数/逐帧 QP |
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
- 尚未写下一阶段实现代码，首阶段代码仍为 2d68091；接手从上述文件及 rtc/compute 目录继续。
