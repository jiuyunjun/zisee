# AUDIO.md

> 实施记录见第 56 节；前文定义目标设计，不能视为全部已完成的设备验收。

## 1. 文档目的

本文定义 Zisee 实时音视频通话中的音频采集、处理、编码、传输、播放、设备路由、弱网恢复及质量监控方案。

音频系统优先级高于视频系统。

核心原则：

> 网络恶化时优先保证语音可理解性和连续性。视频可以降级甚至暂停，但音频应尽可能保持可用。

音频体验目标：

* 无明显回声
* 强背景噪声下仍能听清人声
* 音量稳定
* 不明显吞字
* 不出现严重机器人音
* 丢包时尽量连续
* 网络切换时尽量不中断
* 蓝牙/扬声器切换后自动恢复
* AI 降噪性能不足时自动降级

---

# 2. 技术栈

核心音频技术：

```text
WebRTC Audio Processing Module
+
DeepFilterNet
+
Opus
+
NetEq
+
Android Audio Device Management
+
RTCStats
```

职责划分：

```text
AEC / 基础音频处理
    → WebRTC

高级 AI 降噪
    → DeepFilterNet

Codec
    → Opus

Jitter Buffer / PLC
    → NetEq

Audio Route / Bluetooth / AudioFocus
    → Android

质量检测
    → RTCStats + App Monitor
```

原则：

> 不自行重新实现 AEC、PLC、Jitter Buffer 等成熟 RTC 基础算法，把开发资源投入 AI 降噪、设备兼容、异常恢复和质量控制。

---

# 3. 总体音频 Pipeline

发送端：

```text
Microphone
    │
    ▼
Audio Capture
    │
    ▼
High-pass / DC Removal
    │
    ▼
WebRTC AEC
    │
    ▼
DeepFilterNet
AI Noise Suppression
    │
    ▼
AGC / Limiter
    │
    ▼
VAD / Audio Level
    │
    ▼
Opus Encoder
    │
    ▼
RTP / SRTP
    │
    ▼
Network
```

接收端：

```text
Network
    │
    ▼
SRTP / RTP
    │
    ▼
NetEq
 ├─ Jitter Buffer
 ├─ Packet Reordering
 ├─ PLC
 └─ Time Stretch
    │
    ▼
Opus Decoder
    │
    ▼
Audio Playout
    │
    ▼
Speaker / Earpiece / Bluetooth
```

---

# 4. 音频优先级

整个 RTC 系统：

```text
Audio
>
Signaling / RTC Control
>
Video
```

发生拥塞：

```text
降低视频码率
    ↓
降低视频帧率
    ↓
降低视频分辨率
    ↓
关闭辅助视频流
    ↓
暂停视频
    ↓
继续维持音频
```

不得为了维持 1080p / 高帧率视频而造成语音卡顿。

---

# 5. 基础音频格式

推荐内部格式：

```text
PCM
48 kHz
16-bit
Mono
```

原因：

* 与 WebRTC / Opus / DeepFilterNet 链路匹配方便
* 满足 full-band speech
* Mono 足够用于普通通话
* 降低 CPU、带宽及 AEC 复杂度

未来音乐模式可单独支持 Stereo。

---

# 6. Codec

默认：

```text
Opus
```

推荐：

```text
Sample Rate: 48 kHz
Channels: Mono
Packetization: 20 ms
Bitrate: 24–48 kbps

Default:
32 kbps
```

高质量语音：

```text
48–64 kbps
```

弱网：

```text
16–32 kbps
```

支持：

```text
Opus FEC
Opus DTX
```

具体 adaptation 优先交由 WebRTC RTC stack。

---

# 7. AEC

## 7.1 使用 WebRTC AEC

AEC 使用 WebRTC Audio Processing Module。

```text
Remote Audio
    ↓
Speaker Reference ─────┐
                       ▼
                     AEC
                       ▲
                       │
                Microphone
```

AEC 必须在 AI Noise Suppression 之前。

推荐：

```text
Mic
 ↓
AEC
 ↓
DeepFilterNet
```

原因：

DeepFilterNet 主要解决：

```text
background noise
```

AEC 解决：

```text
remote speaker echo
```

二者不能互相替代。

---

# 8. 禁止双 AEC

Android 系统 AEC 与 WebRTC Software AEC 不应无脑同时启用。

错误：

```text
Android AEC
    ↓
WebRTC AEC
```

可能导致：

* 语音失真
* pumping
* 双讲吞音
* metallic artifacts

必须针对 AudioDeviceModule 明确选择主要 AEC pipeline。

---

# 9. DeepFilterNet AI 降噪

## 9.1 定位

Zisee 将 DeepFilterNet 作为高级音频模式的主要 AI Noise Suppression Engine。

目标：

```text
传统 WebRTC NS
        ↓
AI Speech Enhancement
```

重点处理：

* 空调
* 风扇
* 道路噪声
* 汽车噪声
* 键盘
* 敲击
* 咖啡厅
* 室内背景噪声
* 部分突发噪声
* 风噪

目标体验是：

> 在不明显破坏人声自然度的情况下，比传统 WebRTC NS 更激进地降低非语音背景。

---

# 10. 为什么选择 DeepFilterNet

相较传统 DSP NS：

```text
WebRTC NS
```

DeepFilterNet 使用神经网络 speech enhancement。

优势：

```text
复杂非平稳噪声处理能力更强
48 kHz full-band
适合实时处理
开源
可完全本地运行
没有按分钟 API 成本
用户语音无需上传第三方服务
```

非常适合：

```text
Android
+
WebRTC
+
P2P
```

架构。

---

# 11. DeepFilterNet 不负责 AEC

禁止设计：

```text
Mic
 ↓
DeepFilterNet
 ↓
希望顺便把 Speaker Echo 消掉
```

正确：

```text
Mic
 ↓
WebRTC AEC
 ↓
DeepFilterNet
```

原因：

```text
Acoustic Echo Cancellation
≠
Noise Suppression
```

AEC 有远端 reference signal。

普通 AI NS 没有。

---

# 12. DeepFilterNet 与 WebRTC NS

启用 DeepFilterNet 时：

```text
WebRTC NS:
OFF
或最低程度
```

避免：

```text
WebRTC 强 NS
    ↓
DeepFilterNet 强 NS
```

双重强降噪。

否则可能产生：

* metallic voice
* robotic voice
* water artifacts
* consonant loss
* 句尾消失

因此核心原则：

> 同一时间只允许一个主要 Noise Suppression Engine。

---

# 13. Noise Suppression Engine 抽象

Android 不直接依赖具体模型。

定义：

```text
NoiseSuppressionEngine
```

实现：

```text
NoiseSuppressionEngine
    │
    ├── DeepFilterNetEngine
    │
    ├── RnNoiseEngine
    │
    ├── WebRtcNsEngine
    │
    └── DisabledEngine
```

概念接口：

```kotlin
interface NoiseSuppressionEngine {

    fun initialize(config: NoiseSuppressionConfig)

    fun process(
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int
    ): ShortArray

    fun release()
}
```

注意：

生产实现应尽量避免每帧创建新的 `ShortArray`，上述接口仅表示逻辑关系。

真实 realtime pipeline 应：

* buffer reuse
* zero / low allocation
* native memory reuse
* 避免 GC

---

# 14. Noise Suppression Mode

定义：

```text
OFF
STANDARD
AI
AUTO
```

### OFF

不做明显 NS。

适用于：

```text
音乐
环境音
Debug
```

---

### STANDARD

```text
WebRTC NS
```

特点：

```text
CPU 低
延迟低
兼容性高
```

---

### AI

```text
DeepFilterNet
```

用于最佳通话语音质量。

---

### AUTO

默认推荐。

```text
性能允许
    ↓
DeepFilterNet

性能不足 / thermal
    ↓
RNNoise 或 WebRTC NS
```

---

# 15. AI 降噪降级策略

DeepFilterNet 不得成为 RTC 稳定性的硬依赖。

状态：

```text
AI_ACTIVE
    ↓
AI_DEGRADED
    ↓
STANDARD
```

触发条件可以包括：

```text
处理耗时持续过高
Audio deadline miss
CPU pressure
Thermal throttling
内存压力
模型异常
Native crash protection
```

原则：

> 宁可降低降噪能力，也不能让 AI 模型造成音频 underrun。

---

# 16. Realtime Deadline

假设：

```text
10 ms audio frame
```

那么模型处理必须远低于：

```text
10 ms
```

而不是平均刚好 10 ms。

需要留下：

```text
AEC
AGC
Codec
Audio I/O
线程调度
```

余量。

监控：

```text
processingTimeUs

frameDeadlineMiss

maxProcessingTimeUs

p95ProcessingTimeUs

p99ProcessingTimeUs
```

若持续逼近 deadline：

```text
AI → STANDARD
```

---

# 17. DeepFilterNet Android 实现

推荐架构：

```text
Kotlin
  ↓ JNI
C/C++ wrapper
  ↓
DeepFilterNet runtime
  ↓
Optimized inference backend
```

不要：

```text
每 10/20ms
Kotlin 创建对象
↓
复制
↓
JNI
↓
重新分配 Tensor
```

应采用：

```text
Persistent native context
+
Persistent buffers
+
Persistent model state
```

---

# 18. 模型加载

模型在：

```text
Call prepare
```

阶段提前加载。

不要等：

```text
用户已经开始讲话
```

以后再同步加载模型。

状态：

```text
UNINITIALIZED
 ↓
LOADING
 ↓
READY
 ↓
RUNNING
```

失败：

```text
FAILED
 ↓
fallback WebRTC NS
```

---

# 19. Thread

DeepFilterNet 必须运行于独立 realtime-friendly audio processing thread。

禁止：

```text
Main Thread
```

禁止在处理过程中：

```text
Disk IO
Network IO
Logging massive data
JSON
Database
```

Audio thread 只处理 realtime-critical workload。

---

# 20. 模型 Warm-up

第一次 inference 可能产生：

```text
lazy initialization
memory allocation
cache initialization
```

因此模型加载后执行：

```text
warm-up inference
```

再进入正式 Call。

避免第一句话出现：

```text
卡顿
吞字
爆音
```

---

# 21. Model Runtime Benchmark

App 第一次使用 AI Audio 时，可以执行轻量设备能力评估。

记录：

```text
Device
SOC
Android version
Model
Average inference
P95 inference
Thermal state
```

根据结果选择：

```text
DeepFilterNet
RNNoise
WebRTC NS
```

但不要每次通话都执行完整 benchmark。

---

# 22. DeepFilterNet Quality Profile

逻辑上定义：

```text
AI_BALANCED
AI_HIGH_QUALITY
```

首版只需要：

```text
AI_BALANCED
```

优先：

```text
稳定
低延迟
低功耗
```

而不是追求极致离线增强质量。

实时通话和离线音频修复是不同目标。

---

# 23. RNNoise Fallback

RNNoise 作为第二级 AI/DSP fallback。

架构：

```text
DeepFilterNet
    ↓
RNNoise
    ↓
WebRTC NS
```

但这里的箭头表示：

```text
fallback priority
```

不是同时串联。

禁止：

```text
DeepFilterNet
 ↓
RNNoise
 ↓
WebRTC NS
```

三层同时运行。

---

# 24. Background Voice

一个重要限制：

DeepFilterNet 的普通 Noise Suppression 目标主要是：

```text
Speech
vs
Noise
```

对于：

```text
用户本人讲话
+
旁边另一个人讲话
```

两个信号都是 speech。

因此普通 NS 可能不会很好地消除旁边的人声。

未来如果希望进一步接近高级 Krisp 类体验，需要研究：

```text
Target Speaker Extraction

Speaker Isolation

Background Voice Cancellation
```

可能需要：

```text
Voice Enrollment
+
Speaker Embedding
+
Target Speech Separation
```

这是后续独立模块，不与基础 DeepFilterNet 混为一谈。

---

# 25. 风噪

户外及摩托场景重点处理：

```text
Wind Noise
```

可以在 DeepFilterNet 前保留：

```text
High-pass filtering
```

未来增加：

```text
WindNoiseDetector
```

当检测到强风：

```text
High-pass ↑
AI NS profile adjustment
AGC gain limit
```

避免 AGC 把风噪进一步放大。

---

# 26. AGC

推荐处理顺序：

```text
AEC
 ↓
DeepFilterNet
 ↓
AGC
```

原因：

如果 AGC 在 DeepFilterNet 前：

```text
Noise
 ↓
AGC 放大
 ↓
AI 再处理
```

会增加模型压力。

AGC 目标：

* 讲话距离变化时音量稳定
* 避免 clipping
* 避免 pumping
* 不过度提高背景 noise floor

---

# 27. Limiter

AGC 后设置 limiter。

防止：

```text
突然大声讲话
敲击麦克风
音频算法瞬时增益异常
```

导致 clipping。

---

# 28. VAD

VAD 用于：

* Speaking indicator
* DTX
* Active speaker
* Debug
* Stats

不得简单：

```text
VAD=false
→ PCM=0
```

否则容易吞：

```text
句首
句尾
轻声
```

---

# 29. PLC

丢包恢复交给：

```text
Opus
+
NetEq
```

App 不自行生成 synthetic audio。

主要机制：

```text
Opus PLC
Opus FEC
NetEq concealment
```

---

# 30. Jitter Buffer

使用：

```text
NetEq Adaptive Jitter Buffer
```

不要固定一个大的 buffer。

目标：

网络稳定：

```text
低 delay
```

网络抖动：

```text
自动扩大
```

恢复稳定：

```text
逐步降低 delay
```

---

# 31. Audio Device Manager

统一：

```text
CallAudioManager
```

负责：

* Speaker
* Earpiece
* Bluetooth
* Wired Headset
* USB Audio
* AudioFocus
* AudioManager Mode
* Route recovery

禁止 Activity / Fragment / ViewModel 直接分别操作 AudioManager。

---

# 32. Bluetooth

重点测试：

```text
通话前连接

通话中连接

通话中断开

耳机关机

蓝牙切 Speaker

Speaker 切蓝牙
```

切换音频设备时：

```text
DeepFilterNet context
```

原则上不需要销毁。

模型处理的是 capture PCM，与最终 output route 解耦。

AEC 则必须正确跟踪新的 playback path / delay。

---

# 33. MODE_IN_COMMUNICATION

进入通话：

```text
MODE_IN_COMMUNICATION
```

结束：

```text
restore previous mode
```

异常退出也必须恢复。

---

# 34. Audio State

```text
IDLE

PREPARING

ACTIVE

INTERRUPTED

RECOVERING

STOPPING
```

AI Engine 单独：

```text
OFF

LOADING

ACTIVE

DEGRADED

FAILED
```

---

# 35. 网络切换

Wi-Fi → Cellular 等切换不应销毁：

```text
AudioDevice
DeepFilterNet
Microphone capture
```

ICE 重连属于网络层。

音频处理链继续保持 ready。

恢复 RTP 后立即继续发送。

---

# 36. Audio Quality Monitor

采集：

```text
packetsSent
packetsReceived

packetsLost

jitter

roundTripTime

audioLevel

totalAudioEnergy

concealedSamples

concealmentEvents

jitterBufferDelay

insertedSamplesForDeceleration

removedSamplesForAcceleration
```

AI 部分额外：

```text
aiNsMode

inferenceTime

deadlineMiss

fallbackCount

thermalFallback

modelError
```

---

# 37. Debug Dashboard

开发模式显示：

```text
Input Device
Output Device

AEC
NS Engine
DeepFilterNet Model
AGC

Opus bitrate

Packet Loss
Jitter
RTT

Jitter Buffer
PLC

AI Avg Inference
AI P95 Inference
AI P99 Inference

Audio Deadline Miss

Thermal State

Fallback Reason
```

---

# 38. Debug Audio Capture

Debug build 可选保存：

```text
Raw Mic PCM

AEC Output

DeepFilterNet Output

AGC Output

Remote Decoded PCM
```

例如：

```text
raw.wav
aec.wav
ai_ns.wav
agc.wav
remote.wav
```

这样才能客观判断：

```text
声音在哪一级被破坏
```

生产环境默认禁止保存这些音频。

---

# 39. A/B Testing

必须对：

```text
WebRTC NS

RNNoise

DeepFilterNet
```

进行真实设备 A/B。

测试环境：

```text
安静办公室
机械键盘
空调
风扇
电视
咖啡厅
道路
车内
强风
摩托蓝牙
旁边另一人讲话
```

评价指标不能只看：

```text
Noise Reduction dB
```

还要主观评价：

```text
Speech intelligibility

Speech naturalness

Consonant preservation

Musical noise

Robotic artifacts

Double-talk quality
```

---

# 40. AI Audio 性能测试

至少覆盖：

```text
低端 Android
中端 Android
旗舰 Android
```

重点：

```text
CPU
Battery
Thermal
Inference latency
Long call stability
```

测试：

```text
10 min
30 min
60 min
2 h
```

不能只测试短时间 inference benchmark。

---

# 41. Thermal Strategy

如果出现：

```text
CPU throttling
thermal warning
```

降级优先级：

```text
降低视频 Encoder workload
    ↓
降低 AI Noise Suppression workload
    ↓
DeepFilterNet → RNNoise / WebRTC NS
```

同时保持：

```text
Audio capture
AEC
Opus
```

稳定运行。

---

# 42. 默认 Audio Profile

```text
VOICE_AI
```

配置：

```text
Sample Rate:
48 kHz

Channels:
Mono

Packet:
20 ms

Codec:
Opus

Bitrate:
32 kbps target

AEC:
WebRTC ON

Noise Suppression:
DeepFilterNet

AGC:
ON

Limiter:
ON

High-pass:
ON

FEC:
ON

DTX:
ON
```

---

# 43. STANDARD Profile

性能不足时：

```text
VOICE_STANDARD
```

```text
AEC:
WebRTC

Noise Suppression:
WebRTC NS

AGC:
WebRTC

Codec:
Opus
```

目标：

```text
最低 CPU
最高兼容性
```

---

# 44. Music Profile

未来：

```text
MUSIC
```

原则：

```text
AEC optional

DeepFilterNet OFF

NS OFF / Weak

AGC OFF / Weak

Opus bitrate ↑

Stereo optional
```

因为 AI Speech Enhancement 很可能把：

```text
音乐
乐器
环境声
```

判定为 noise。

---

# 45. Android 模块结构

```text
rtc/
└── audio/
    ├── AudioEngine.kt
    ├── AudioDeviceManager.kt
    ├── AudioRoute.kt
    │
    ├── processing/
    │   ├── AudioProcessingEngine.kt
    │   ├── NoiseSuppressionEngine.kt
    │   ├── DeepFilterNetEngine.kt
    │   ├── RnNoiseEngine.kt
    │   └── WebRtcNsEngine.kt
    │
    ├── quality/
    │   ├── AudioQualityMonitor.kt
    │   ├── AudioStats.kt
    │   └── AudioQualityState.kt
    │
    └── debug/
        ├── AudioDebugLogger.kt
        └── AudioDebugRecorder.kt
```

Native：

```text
android/app/src/main/cpp/audio/
    ├── deepfilter/
    ├── rnnoise/
    └── audio_processor_jni.cpp
```

具体第三方源码组织方式根据其 License 和 build system 决定。

---

# 46. AudioEngine

负责：

```text
initialize

start capture

start playout

stop

mute

Audio Processing lifecycle

Noise Suppression mode
```

不负责 UI。

---

# 47. DeepFilterNetEngine

职责：

```text
Load model

Warm up

Initialize native state

PCM frame processing

Inference timing

Error detection

Release
```

不得直接：

```text
操作 PeerConnection

控制 Signaling

修改 UI
```

---

# 48. AudioProcessingEngine

统一调度：

```text
AEC
 ↓
NoiseSuppressor
 ↓
AGC
 ↓
Limiter
```

并负责 profile。

例如：

```text
VOICE_AI
VOICE_STANDARD
MUSIC
```

---

# 49. 与 CallSession 关系

```text
CallSession
 ├── SignalingSession
 ├── PeerConnectionController
 ├── AudioEngine
 │    ├── AudioDeviceManager
 │    └── AudioProcessingEngine
 │          └── DeepFilterNetEngine
 ├── VideoEngine
 ├── NetworkMonitor
 └── StatsMonitor
```

AI NS 只是 AudioEngine 内部能力。

不能让：

```text
DeepFilterNet failure
```

导致：

```text
CallSession failure
```

---

# 50. 不建议事项

禁止同时启用：

```text
WebRTC strong NS
+
DeepFilterNet
```

禁止：

```text
DeepFilterNet
+
RNNoise
```

串联。

禁止：

```text
AI inference on Main Thread
```

禁止每 frame：

```text
malloc/free
object allocation
tensor recreate
```

禁止：

```text
DeepFilterNet failure → end call
```

应该：

```text
DeepFilterNet failure
 ↓
fallback
 ↓
WebRTC NS
```

禁止为了 AI 降噪：

```text
增加巨大音频 buffer
```

导致明显 conversational latency。

---

# 51. Phase 1

先完成：

```text
WebRTC Audio

AEC
NS
AGC

Opus

NetEq

Audio Route

Bluetooth

Basic Stats
```

确保 RTC 基础稳定。

---

# 52. Phase 2

加入：

```text
DeepFilterNet

JNI

Realtime processing

Warm-up

AI Debug Stats

A/B test
```

验证：

```text
效果
CPU
Battery
Thermal
Latency
```

---

# 53. Phase 3

完成：

```text
AUTO Noise Suppression

DeepFilterNet fallback

RNNoise optional fallback

Thermal adaptation

Performance profile
```

---

# 54. Phase 4

研究：

```text
Target Speaker Extraction

Background Voice Cancellation

Speaker Enrollment

Wind-specific enhancement

Music Mode
```

目标逐步接近：

```text
Discord / Krisp class
```

的实际语音体验。

---

# 55. 最终原则

Zisee 音频系统质量排序：

```text
可理解
>
连续
>
低延迟
>
自然
>
降噪强度
>
高保真
```

AI 降噪不能凌驾于实时性。

因此核心架构：

```text
WebRTC AEC
    ↓
DeepFilterNet
    ↓
AGC / Limiter
    ↓
Opus
    ↓
WebRTC Transport
```

接收：

```text
WebRTC Transport
    ↓
NetEq
    ↓
Opus
    ↓
Playout
```

DeepFilterNet 是：

> **增强音频体验的可插拔能力，而不是保证通话成立的基础依赖。**

最终设计要求：

```text
AI 好用时：
使用 DeepFilterNet 获得高级降噪

AI 跑不动时：
自动退回基础 NS

任何情况下：
优先保证电话还能正常说话
```

---

# 56. 实施记录（2026-09-09）

## Phase 1

- `CallAudioManager` 统一管理焦点、通信模式、耳机热插拔与路由恢复。暂时失焦只中断采集/播放，不销毁 PeerConnection；恢复焦点后继续，用户静音状态独立保留。
- API 31+ 使用通信设备 API；API 26–30 使用 SCO 与有界等待，失败回退。默认优先有线、USB、蓝牙，随后听筒/扬声器；用户可显式选择扬声器。结束恢复原模式和路由。
- 明确关闭 ADM 平台 AEC/NS，标准语音启用 WebRTC 软件 AEC、NS、AGC、高通。单声道采集目标 48 kHz。
- 本地音频 SDP 按实际 payload 优先 Opus，声明 FEC、DTX、Mono、32 kbps 和 20 ms。保留其他 codec 的协商回退；实际发送仍取决于双方协商和原生拥塞控制。
- `MediaStats.audio` 独立统计音频方向、码率、丢包、音量、能量、PLC 和 NetEq 区间延迟；缺失值保持未知，处理计数重置。
- JVM 测试与 Debug lint 通过。蓝牙/SCO、双讲回声和真实弱网仍需设备矩阵验收。

参考：[Android 通信设备路由](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)、[WebRTC ADM](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/audio/JavaAudioDeviceModule.java)。
