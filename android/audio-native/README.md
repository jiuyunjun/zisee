# Zisee 本地 AI 音频运行时

普通 Android 构建直接使用已提交的模型与 `.so`，无需安装 Rust。`verifyAudioNative` 在构建前核对源码、模型、库和许可证清单的 SHA-256；修改原生代码后必须重新生成。

## 固定依赖

- DeepFilterNet：KaleyraVideo/DeepFilterNet `ee6505ab4e899d3bef734427e627a266b6fef441`，MIT / Apache-2.0 双许可；随包 mobile ONNX 模型约 7.98 MB。
- Tract：0.21.4，MIT / Apache-2.0。上游 `^0.21.4` 会解析到不兼容的 ndarray/graph API，故明确锁定。
- JNI：0.21.1；其余 Rust 依赖固定在 `Cargo.lock`。
- Rust：1.98.1；NDK：r27d；Android API：26；arm64-v8a / x86_64，ELF LOAD 对齐 16 KB。
- 32 位 Android 保留 WebRTC 标准降噪；加载不到 AI 库会回退，不影响通话。

没有新增联网权限，不下载模型，不上传音频。APK 的 `assets/audio/THIRD_PARTY_NOTICES.txt` 携带上游及传递依赖许可证。它包含构建依赖的声明，范围比最终链接代码更宽。

当前通用 Debug APK 中，两个 ABI、模型及声明共占约 38.62 MB；这是实际包体成本，不应把 AI 当成免费的体积扩展。尚未启用按 ABI 分包。

## 重建

在 Linux 或 WSL Ubuntu 安装 C/C++ 编译环境、Python 3.12+、Rustup 和 NDK r27d：

```bash
rustup toolchain install 1.98.1 --profile minimal
export ANDROID_NDK_HOME=/absolute/path/android-ndk-r27d
# 可放在 Linux 文件系统以加速编译。不要使用与其他任务共用的目录。
export ZISEE_AUDIO_BUILD=/tmp/zisee-audio-build
python3 android/audio-native/build.py
```

Windows 从 WSL 执行同一个脚本，项目路径写为 `/mnt/c/.../Zisee/android/audio-native/build.py`。
首次构建下载并校验固定源归档；模型也校验固定 SHA-256。`build.py` 生成两个 ABI 库、模型、许可证清单和 `artifacts.properties`。构建缓存由该脚本独占，不手动替换其中的上游源码。普通 Gradle 构建不重新编译 Rust。

## 处理与生命周期

WebRTC AEC/高通 → capture post processing → DeepFilterNet → 温和数字增益/限幅 → Opus。

WebRTC 的扩展点实际位于其 AGC 之后，因此 AI 模式关闭 WebRTC NS/AGC，由桥接在 AI 后做增益/限幅。标准模式使用 WebRTC AEC/NS/AGC。扩展收到的是 480 个 `FloatS16` 单声道样本，不是归一化 Float32；JNI 内做比例转换。

原生上下文和输入/输出 ndarray 缓冲区随通话创建，逐帧复用。模型图预先加载/预热。Tract 内部仍可能分配内存，WebRTC JNI 也创建 direct-buffer 包装；这里没有宣称整个推理链零分配。

桥接捕获可展开的 Rust panic 和模型返回错误，校验尺寸/浮点有效性，失败不覆盖输入。不能捕获 SIGSEGV、进程被杀、系统 OOM 或中止已经阻塞的推理。首次过长推理仍可能影响一帧；预算和后续降级减少持续影响。

`ExternalAudioProcessingFactory` 在当前 SDK 使用进程级全局指针，`destroy()` 的置空和已初始化 callback 路径不安全。因此 Zisee 保留一个进程级转发器，不反复创建/销毁它；每个通话独占一个可释放的模型，会话间解除引用。禁止第二个会话同时接管该转发器。该限制需要随 WebRTC 升级重新审查。

参考源码：[扩展 APM](https://github.com/webrtc-sdk/webrtc/blob/030ad13afd0ab7d16c70662e0e793ea1b892c158/sdk/android/src/jni/pc/external_audio_processing_factory.cc)、[处理顺序](https://github.com/webrtc-sdk/webrtc/blob/030ad13afd0ab7d16c70662e0e793ea1b892c158/modules/audio_processing/audio_processing_impl.cc)、[DeepFilterNet](https://github.com/KaleyraVideo/DeepFilterNet/tree/ee6505ab4e899d3bef734427e627a266b6fef441)。
