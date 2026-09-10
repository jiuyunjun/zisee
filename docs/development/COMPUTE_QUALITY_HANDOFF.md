# Compute-for-Quality 持续交接

更新：2026-09-11。用户要求实现专项设计、随时留 handoff，结束后 push。

## 当前状态

- 基线 main；工作区只有用户新增的 `docs/architecture/Compute-for-Quality.md`。不覆盖用户改动。
- 已阅读设计、CameraAdaptationPolicy、NativeRtcSession、DualCameraCapture、MediaStats 及此前通话 handoff。
- 正在实现 P0：独立计算等级/热预算控制、GPU 高质量缩放与因果时域降噪，复用已有 EGL/纹理池模式；随后补验证及能力边界。
- 控制器与 thermal/battery 采样已写入，5 项控制器单测已通过（testDebugUnitTest --tests *ComputeQualityPolicyTest）；GPU 链路尚未接入。尚未 push。
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

- P0：控制器、缩放、时域降噪（进行中）。
- P1：场景/暗光/ROI/编码复杂度（待核实可用接口，不能伪报支持）。
- P2：接收端 SR/去伪影/细节模式（待实现或明确条件）。
- P3：设计列为可选 ML 丢帧掩盖与语义增强，无模型和实测资料，不应默认生成内容。
- 真机 benchmark、PSNR/SSIM/VMAF 对照和主观验收需要真实数据，尚未开展。

## 参考

- 设计：`docs/architecture/Compute-for-Quality.md`。
- Android Thermal API：https://developer.android.com/games/optimize/adpf/thermal
- WebRTC 接口以缓存的 144.7559.15 sources.jar 为准。
- Java：`C:/Program Files/Android/Android Studio/jbr`；构建从 `android` 执行 Gradle wrapper。
