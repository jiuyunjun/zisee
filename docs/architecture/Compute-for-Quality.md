# 视频通话 Compute-for-Quality 专项设计

> Android 实现进度（2026-09-11）：已落地 P0 控制器、GPU 面积缩放、因果时域降噪，以及基础场景/发送 FPS 策略。Debug 默认开启，可用 `-Pzisee.computeQuality=false` 构建对照组；Release 暂关闭。ROI、硬编 complexity、接收端 SR 等尚未实现，不将设计目标等同于已交付能力。持续状态、验证及下一步见 [专项 handoff](../development/COMPUTE_QUALITY_HANDOFF.md)。

> 第二步已接入 Java 硬件编码器的 encode→callback P50/P95/P99 与可选 QP，保留 AR SEI 和 native 软件 fallback。该分位数包含编码器排队/调度，不能解释为纯 MediaCodec 内部执行时间；不可观测的 native-only 软件路径保持未知。

> 已增加 Android MediaCodec 能力快照，与 WebRTC hardware formats 分开显示。硬件标志、complexity range、分辨率/FPS 都是设备声明，不是经过热稳定性验证的 DeviceProfile。

> ROI 已推进到 Debug 默认开启的人脸检测与相机取样诊断，Release 不含检测 SDK；背景处理和 QP map 尚未实现。构建开关、SDK 遥测与验证边界见 [Face ROI 检测实验](FACE_ROI_EXPERIMENT.md)。

> 真机数据显示现有控制环存在盲目恢复、压力类型混淆、测量口径错误和每帧同步屏障，正在按 [控制环重构设计](COMPUTE_CONTROL_LOOP.md) 分步重做；在重构完成前不以阈值调整代替。

## 1. 目标

传统实时视频系统主要围绕三个变量做自适应：

`Resolution × FPS × Bitrate`

本方案扩展为：

`Resolution × FPS × Bitrate × Codec × Encoder Complexity × Preprocessing × ROI × Receiver Enhancement × Thermal Budget`

设计目标不是“尽量省电”，而是：

> **在设备还有性能和热余量时，主动使用 CPU / GPU / NPU / ISP 能力，提高单位码率对应的主观画质。**

但必须满足三个硬约束：

- 不明显增加端到端交互延迟；
- 不进入持续 thermal throttling；
- 不因为计算过载导致掉帧。

libwebrtc 本身已经把编码耗时和编码后 QP 作为自适应资源，用于判断 CPU overuse 和画质不足，同时支持注入额外 Resource。因此本方案可以在其现有 adaptation framework 上继续扩展，而不必完全重新设计。


# 2. 总体架构

建议发送链路：

```text
Camera
  │
  ▼
ISP
  │
  ▼
Raw Frame
  │
  ├──────────────► Scene Analyzer
  │                  │
  │                  ├─ motion
  │                  ├─ luminance
  │                  ├─ noise
  │                  ├─ face/person
  │                  └─ texture complexity
  │
  ▼
Compute-for-Quality Processor
  │
  ├─ Temporal Denoise
  ├─ Low-light Enhancement
  ├─ Tone Mapping
  ├─ Intelligent Resize
  ├─ ROI / Background Optimization
  ├─ Mild Sharpen
  └─ optional Super Resolution
  │
  ▼
Hardware Encoder
  │
  ├─ bitrate
  ├─ fps
  ├─ resolution
  ├─ complexity
  ├─ QP control
  └─ codec
  │
  ▼
WebRTC
```

同时运行：

```text
                       ┌──────── Network State
                       │
                       ├──────── Thermal State
                       │
                       ├──────── Battery State
                       │
                       ├──────── Encoder Load
                       │
                       ├──────── GPU/NPU Load
                       │
                       └──────── Scene State
                                  │
                                  ▼
                         Quality Orchestrator
                                  │
                                  ▼
                           Compute Budget
```

这个 `Quality Orchestrator` 是整个方案的核心。


# 3. 最重要的设计原则

## 3.1 不优先用软件编码换画质

这是本方案最容易走错的地方。

例如：

```text
HW H.264
CPU 5~10%
720p30

vs

x264 slow
CPU 150~300%
720p30
```

软件编码确实可能在相同 bitrate 下取得更高压缩效率，但手机上的持续功耗和发热代价往往太大。

正确顺序应该是：

```text
优秀预处理
    ↓
优秀缩放
    ↓
ROI
    ↓
提高硬编 complexity
    ↓
更先进的硬件 codec
    ↓
最后才考虑软件编码
```

Android 的编码器接口本身允许设备暴露 complexity range，而且官方明确说明，更高 complexity 可以使用更多编码工具，从而改善质量或压缩率，但会增加计算消耗。

因此：

> **优先烧 GPU/NPU 和硬件 encoder，而不是烧 CPU 做纯软件编码。**


# 4. Compute Level

不要设计成简单：

```text
qualityEnhancement = true / false
```

应该建立 Compute Level。

## C0 — Survival

设备过热或性能不足。

```text
Temporal NR       OFF
AI enhancement    OFF
ROI analysis      OFF
Sharpen           OFF
SR                OFF

Hardware Encoder  lowest sustainable
```

典型触发：

```text
Android thermal >= SEVERE
iOS thermal == serious / critical
encode time 接近 frame interval
```


## C1 — Efficient

普通设备默认模式。

```text
Temporal NR       LOW
Resize            HIGH QUALITY
ROI               BASIC
Sharpen           VERY LOW
AI                 OFF
Encoder complexity MEDIUM
```

这是最低质量基准。


## C2 — Quality

建议成为现代旗舰手机的默认目标。

```text
Temporal NR       MEDIUM
Low-light NR      ON
Tone Mapping      ON
Face ROI          ON
Background optimization ON
High quality scaler ON

Encoder complexity HIGH
Receiver SR       conditional
```

这一级通常应该产生最大的实际收益。


## C3 — Maximum

性能和温度余量明显充足时。

```text
Motion-compensated temporal NR
Face/person semantic ROI
Advanced low-light processing
High quality resize
Receiver super resolution
Maximum sustainable encoder complexity
```

适合：

```text
旗舰手机
Wi-Fi
电量充足
thermal nominal
```


## C4 — Burst

不是长期运行模式。

例如用户突然：

```text
展示物体
展示身份证件
展示电路板
展示文字
```

可以短时间：

```text
1080p / 1440p
更强降噪
更高 bitrate
更强 ROI
更高 encoder complexity
```

持续例如数秒～十几秒。

然后退回 C2/C3。

这种 **Burst Quality** 很适合视频指导类应用。


# 5. 第一优先级：Temporal Denoise

这是整个 Compute-for-Quality 中性价比最高的模块之一。

摄像头尤其在：

```text
室内
晚上
暗光
前摄
```

会产生大量 sensor noise。

对于编码器：

> **噪点就是不断随机变化的高频信息。**

因此非常难压缩。

例如原图存在大量噪点：

```text
Frame N
░▒▓░▒▓▒░▓

Frame N+1
▒░▓▒░▒▓░
```

编码器无法很好利用帧间预测。

经过 temporal denoise：

```text
Frame N
████████

Frame N+1
████████
```

编码效率会明显改善。

所以这不是单纯：

> GPU 换 prettier image

而可能产生：

> GPU compute → 降低 source entropy → encoder 更容易压缩

因此同样：

```text
720p30
1 Mbps
```

画质可以显著改善。


## 推荐实现

至少保留：

```text
current frame
previous frame
previous-2 frame
```

进行：

```text
motion estimation
+
temporal accumulation
+
edge-aware filter
```

推荐 causal filter：

```text
previous → current
```

不要使用未来帧。

即：

```text
不要：

N-2
N-1
N
N+1
N+2
 ↓
output N
```

因为会天然增加视频延迟。

而应该：

```text
N-2
N-1
N
 ↓
output N
```

做到几乎没有算法等待延迟。


# 6. Motion-aware Denoise

降噪强度不能固定。

建立：

```text
motionScore = opticalFlowMagnitude
noiseScore
luma
```

例如：

```text
静止 + 暗光
Temporal NR = 1.0

轻微运动
Temporal NR = 0.7

人物快速运动
Temporal NR = 0.3
```

否则容易出现：

```text
ghosting
拖影
脸部残影
手部残影
```


# 7. Capture Oversampling

不要简单认为：

> 发720p → Camera就采720p。

推荐：

```text
Camera 1080p
      ↓
ISP
      ↓
denoise
      ↓
high-quality resize
      ↓
720p
      ↓
encoder
```

相比直接：

```text
Camera 720p
      ↓
encoder
```

前者通常可以获得：

- 更好的 antialias；
- 更好的细节；
- 更好的噪声平均；
- 更自然的锐度。

因此：

> **Capture Resolution 和 Encode Resolution 应解耦。**

例如：

```text
capture = 1920×1080

encode =
1280×720
960×540
640×360
```

根据网络动态变化。

Camera pipeline 不需要跟着频繁重新配置。


# 8. Intelligent Resize

缩放器不能只是：

```text
bilinear
```

可以投入 GPU 做更高质量 downsample。

例如：

```text
Lanczos-like
bicubic
edge-aware resize
GPU shader
```

但不要过度 sharpening。

推荐：

```text
downsample
    ↓
very mild edge recovery
```

而不是：

```text
锐化
↓
压缩
```

过度锐化会制造：

```text
halo
ringing
high-frequency detail
```

这些内容反而非常耗 bitrate。


# 9. Face / Human ROI

视频电话的主观质量并不是：

```text
所有像素同等重要
```

通常：

```text
脸
眼睛
嘴
手
展示物体
```

的重要度明显高于：

```text
墙
天花板
背景
```


因此生成一个：

```text
Importance Map
```

例如：

```text
Face       1.0
Hands      0.8
Body       0.6
Foreground 0.5
Background 0.2
```

然后做：

```text
ROI-aware encoding
```

如果底层 encoder 支持 ROI/QP map，就直接调整编码资源。

如果不支持，则可以做：

```text
foreground:
    normal processing

background:
    stronger temporal denoise
    slightly lower texture
```

于是 background 所需要的 bitrate 自然下降。

bitrate 被释放给人物。


# 10. 不建议默认做“AI 人脸修复”

这里必须区分：

### 可以做

```text
denoise
deblur
non-generative SR
contrast recovery
edge reconstruction
```

### 不推荐默认做

```text
generative face restoration
AI 重新生成眼睛
AI 重新生成牙齿
AI 猜皮肤纹理
```

因为视频通讯要求的是：

> **faithful reconstruction**

而不是：

> aesthetically plausible reconstruction。

否则会出现：

```text
对方实际上没看清
↓
AI 猜出来一个东西
↓
用户以为这是真实信息
```

尤其在：

```text
维修指导
文件展示
医疗
商品展示
```

场景非常危险。

所以默认应该坚持：

> **Enhancement 可以恢复信号，不应该创造不存在的内容。**


# 11. Low-light 专项模式

检测：

```text
mean luma ↓
noise ↑
exposure time ↑
ISO ↑
```

进入：

```text
LOW_LIGHT
```

策略：

```text
30fps
↓
24fps / 20fps / 15fps
```

摄像头因此可以：

```text
增加 exposure
降低 gain
```

同时：

```text
temporal denoise ↑
```

然后把节省出的 bitrate 给每帧。

很多情况下：

```text
720p 20fps
```

会比：

```text
720p 30fps
```

暗光质量明显更好。

这里不能迷信 30fps。


# 12. FPS 应由 Motion 决定

计算：

```text
motionScore
```

例如：

```text
静止聊天：

720p
24fps
1.2 Mbps

普通聊天：

720p
30fps
1.5 Mbps

大量移动：

720p
30fps
2 Mbps

快速运动：

540p
30fps
```

也就是说：

> Motion 高 → FPS 权重上升  
> Detail 高 → Resolution 权重上升。

W3C 的 WebRTC degradation preference 本身也是沿着这个思路区分“优先维持帧率”与“优先维持分辨率”。


# 13. Screen Share 必须使用另一套策略

Camera：

```text
motion important
```

Screen：

```text
detail important
text important
```

因此屏幕共享不要复用 camera pipeline。

推荐：

```text
Temporal denoise OFF
Sharpen OFF
Face ROI OFF
Low-light OFF
Super resolution OFF
```

优先：

```text
Resolution ↑
FPS ↓
```

例如：

```text
1920×1080
15fps
```

通常优于：

```text
1280×720
30fps
```

对于文本尤其明显。

WebRTC 的 content hint 也明确区分：

```text
motion
detail
text
```

其中 `detail/text` 就是面向 presentation、网页和文字内容。


# 14. Encoder Compute-for-Quality

这是第二层。


## Android

首先读取：

```text
MediaCodecInfo.CodecCapabilities
EncoderCapabilities
```

获取：

```text
complexityRange
bitrate modes
supported resolution
supported FPS
```

如果支持：

```text
KEY_COMPLEXITY
```

则根据 Compute Level：

```text
C1 → low-medium
C2 → medium-high
C3 → highest sustainable
```

Android 官方明确指出，高 complexity 可以启用更多 encoder tools，以更多计算换更好的质量或 compression ratio。


但不能：

```text
complexity = MAX
```

永久固定。

应该观察：

```text
encodeTimeP95
```


假设：

```text
30fps

frame budget = 33.3ms
```

推荐约束：

```text
encode P95 < 12~15 ms      excellent
15~20 ms                   acceptable
20~25 ms                   warning
>25 ms                     downshift
```

这些是工程起始阈值，需要设备实测，而不是 Android 官方固定标准。


# 15. Apple VideoToolbox

iOS 必须保持：

```text
kVTCompressionPropertyKey_RealTime = true
```

因为视频会议就是 realtime workload。Apple 明确指出关闭 realtime 可以允许编码器慢于实时速度以取得更好的压缩结果，但那是 offline encoding 的场景，不适合交互式视频。

Apple 现在还提供专门的：

```text
VideoConferencing
low-latency encoding
```

配置路径。

同时可以在支持的 encoder 上利用：

```text
SpatialAdaptiveQP
```

使 encoder 根据每帧空间特征调整 coding unit 的 QP。

因此 iOS 的原则是：

```text
VideoToolbox HW
+
Realtime
+
Low latency conferencing mode
+
Spatial adaptive QP
```

而不是为了画质把 realtime 关掉。


# 16. Codec 选择

不要写死：

```text
AV1 > HEVC > H264
```

这是不完整的。

真正应该定义：

```text
effectiveCodecScore =
compressionEfficiency
× hardwareSupport
× encoderLatency
× thermalEfficiency
× decoderSupport
```

例如：

```text
AV1 software
```

即便 compression efficiency 很高，如果：

```text
encode 25ms
CPU 300%
手机迅速发热
```

也可能输给：

```text
H.264 hardware
encode 3ms
```

所以：

> **优先选择双方都有可靠硬件实现的最高效 codec。**

建议启动 call 时做 capability negotiation：

```text
AV1 HW?
VP9 HW?
HEVC HW?
H264 HW?
```

然后建立：

```text
CodecProfile
```

不要只根据 codec 名称判断。


# 17. Receiver Compute-for-Quality

很多系统只优化 Sender。

实际上 Receiver 也有大量算力可以利用。

推荐 pipeline：

```text
Decode
 ↓
Artifact Reduction
 ↓
Optional Super Resolution
 ↓
Mild Detail Recovery
 ↓
Render
```


# 18. Receiver Super Resolution

这是非常值得做的一项。

例如网络只能支持：

```text
540p
1 Mbps
```

传统：

```text
540p decode
↓
GPU bilinear
↓
1080p display
```

Compute-for-Quality：

```text
540p decode
↓
AI/non-generative SR
↓
1080p
```

发送端 bandwidth 没有任何增加。

代价全部转移到：

```text
receiver NPU/GPU
```

因此这是最纯粹的：

> **compute ↔ bandwidth**

交换。


推荐：

```text
360 → 720
540 → 1080
720 → 1080
```

不要：

```text
180 → 1080
```

因为原始信号已经严重不足。


# 19. SR 运行条件

不是永远开启。

例如：

```text
source resolution < render resolution
AND
thermal budget sufficient
AND
GPU/NPU budget sufficient
AND
video window sufficiently large
```

才启用。

如果视频只是：

```text
200×300 dp
```

的小窗：

```text
SR OFF
```

因为肉眼几乎看不出收益。


# 20. Compression Artifact Reduction

低码率时常见：

```text
blocking
ringing
mosquito noise
banding
```

可以在 receiver 上运行轻量：

```text
deblock
dering
adaptive sharpening
```

这种 compute 通常比生成式 AI 安全得多。


# 21. Packet Loss Enhancement

对于偶发缺失 frame：

```text
N-1
N
[missing]
N+2
```

可以尝试：

```text
motion-based concealment
```

甚至轻量 ML temporal prediction。

但是这只能用于：

```text
visual concealment
```

不能替代：

```text
NACK
RTX
FEC
packet-loss adaptation
```

因为生成出来的 frame 不是真实数据。


# 22. Quality Orchestrator

建议每：

```text
500 ms
```

收集一次 RTC / compute metrics。

热状态：

```text
1 sec
```

读取一次即可。

Android thermal status 可每秒检查；thermal headroom 按官方建议至少间隔 10 秒读取一次，过度调用可能返回 `NaN`。`NaN` 必须视为未知。参考：https://developer.android.com/games/optimize/adpf/thermal


输入：

```text
NetworkState
ThermalState
ComputeState
ContentState
BatteryState
CodecState
```


# 23. NetworkState

至少：

```text
targetBitrate
availableOutgoingBitrate

RTT
jitter

packetLoss
NACK
RTX

sendQueueDelay
```

不要仅用：

```text
speed test bandwidth
```


# 24. ComputeState

记录：

```text
captureTime
analysisTime
preprocessTime
encodeTime

decodeTime
postprocessTime
renderTime

framesDropped
framesEncoded

encoderQP
```

尤其重要的是：

```text
encodeTime / frameInterval
```

libwebrtc 自己也使用编码耗时作为 CPU/resource overuse 的 proxy。


# 25. ThermalState

Android：

```text
PowerManager.getCurrentThermalStatus()
```

并且支持：

```text
Thermal Headroom
```

Headroom 的语义非常重要：

```text
0 ---------------- 1.0
cool              severe throttling
```

其中：

```text
1.0
```

表示达到或预测达到 `SEVERE` throttling 阈值。


可以请求：

```text
getThermalHeadroom(10)
```

做十秒左右的预测。

例如：

```text
现在 0.65

10 秒预测：
0.90
```

说明：

> 当前虽然还没有 thermal throttling，但当前 workload 不可持续。

这比等系统真正过热再降档高级得多。


# 26. Android Compute Policy

建议初始工程值：

```text
headroom < 0.65
    C3 allowed

0.65~0.80
    max C2

0.80~0.90
    max C1

>0.90
    reduce workload

>=1.0
    emergency quality downshift
```

注意：

这些不是 Android 规定的标准线。

Android 自己提供的示例 heuristic 更保守，大约从 `0.85` 开始就建议警惕 thermal 状态。

最终阈值必须通过实际机型 thermal profiling 调整。


# 27. iOS Compute Policy

读取：

```swift
ProcessInfo.processInfo.thermalState
```

状态：

```text
nominal
fair
serious
critical
```

Apple 明确建议在较高 thermal state 降低 CPU/GPU workload；`serious` 已经意味着系统正在采取措施降低热状态。

推荐：

```text
nominal
    C3

fair
    C2

serious
    C1

critical
    C0
```


# 28. Thermal 必须 Fast Down / Slow Up

禁止：

```text
C3
↓
C2
↓
C3
↓
C2
```

来回震荡。

策略：

```text
thermal 上升
    immediately downshift

thermal 恢复
    wait
```

例如：

```text
downshift confirmation = 1~2 sec

upshift confirmation = 20~30 sec
```

这是典型：

> Fast Down / Slow Up。


# 29. Scene Analyzer

推荐运行一个非常低分辨率的旁路：

```text
256×144
或者
320×180
```

进行：

```text
motion detection
face detection
person segmentation
noise estimation
brightness estimation
texture complexity
```

没有必要对 1080p 原图跑所有 AI。

因此：

```text
1080p Camera
   │
   ├──► 320×180 analyzer
   │
   ▼
1080p processing
```

这样 AI 分析成本会小很多。


# 30. Content Mode

最终应该自动分类：

```text
PORTRAIT
NORMAL
MOTION
LOW_LIGHT
DETAIL
TEXT
```

对应策略：


## PORTRAIT

```text
Face ROI ↑
Temporal NR ↑
Background compression ↑
FPS 24~30
```


## MOTION

```text
Temporal NR ↓
FPS ↑
resolution 可下降
```


## LOW_LIGHT

```text
FPS ↓
Temporal NR ↑↑
Exposure ↑
```


## DETAIL

例如展示：

```text
电路板
商品
零件
```

则：

```text
resolution ↑
sharpen very mild
ROI = object
FPS ↓
```


## TEXT

```text
resolution ↑↑
FPS 10~20
denoise low
sharpen low/off
```


# 31. 推荐的决策目标函数

不要写成大量 if/else。

逻辑上可以定义：

```text
Score =
    PerceptualQuality

    - λ1 × Latency
    - λ2 × ThermalRisk
    - λ3 × FrameDropRisk
    - λ4 × NetworkCongestionRisk
    - λ5 × BatteryCost
```

每一种候选配置：

```text
Config A
Config B
Config C
```

计算 predicted score。

例如：

```text
A:
720p30
1.2M
medium NR
C2

B:
540p30
1.0M
SR
C3

C:
720p20
1.0M
strong NR
C2
```

根据：

```text
scene
network
thermal
```

选择最高得分。


# 32. 推荐 Config Ladder

不要允许无限连续组合。

定义约 8～12 个经过实测的配置档位。

例如 Camera：


| Level | Encode | FPS | Enhancement |
|---|---|---:|---|
| V0 | 360p | 15 | minimal |
| V1 | 360p | 24 | NR |
| V2 | 540p | 24 | NR |
| V3 | 540p | 30 | NR + ROI |
| V4 | 720p | 24 | NR + ROI |
| V5 | 720p | 30 | NR + ROI |
| V6 | 1080p | 24 | advanced |
| V7 | 1080p | 30 | advanced |

另外独立存在：

```text
Compute Level C0-C3
```

于是可以：

```text
V4 + C1

或者

V3 + C3
```

这非常重要。

因为在低带宽下：

> **降低 resolution + 增加 compute**

有时反而比：

> 高 resolution + 低 compute

看起来更清晰。


# 33. 典型场景

假设 bandwidth：

```text
1 Mbps
```

传统 WebRTC 可能：

```text
720p30
QP 很高
满屏压缩块
```

Compute-for-Quality 可以选择：

```text
Camera 1080p
↓
temporal denoise
↓
high-quality downscale
↓
540p30
↓
ROI
↓
high-complexity HW encode
↓
1 Mbps
↓
receiver SR
↓
1080p display
```

用户主观感受到的画质可能反而明显更好。


# 34. 不要只追求分辨率

例如：

```text
1080p
500 kbps
```

几乎一定不是“高清”。

而：

```text
540p
500 kbps
strong denoise
ROI
good encoder
```

很可能更自然。

因此 ABR 不能写成：

```text
bandwidth > X
    resolution++
```

而应该考虑：

```text
bitsPerPixelPerFrame
QP
scene complexity
```


# 35. QP 是非常有价值的反馈

libwebrtc 自带 QualityScaler 就使用 encoded frame QP 判断当前分辨率下视频质量是否过低。

因此可以把 QP 加进自己的 orchestrator：

```text
QP 持续过高
+
network bitrate 已经到上限
```

不要再硬撑当前 resolution。

应该：

```text
resolution ↓
```

或者：

```text
compute ↑
denoise ↑
ROI ↑
```


# 36. 优先级

如果只有有限开发资源，我建议按这个顺序实施。

```text
P0
Thermal-aware controller
High quality scaling
Temporal denoise

P1
Scene detection
Low-light strategy
Face/person ROI
Encoder complexity control

P2
Receiver SR
Artifact reduction
Object/detail mode

P3
ML packet loss concealment
高级 semantic enhancement
```


# 37. 计算资源应该花在哪里

我给各模块一个工程性 ROI 排名：

```text
★★★★★ Temporal Denoise

★★★★★ Intelligent Resolution/FPS Selection

★★★★★ High-quality Resize

★★★★☆ Face / Foreground ROI

★★★★☆ Hardware Encoder Complexity

★★★★☆ Receiver Super Resolution

★★★☆☆ Low-light Enhancement

★★★☆☆ Artifact Reduction

★★☆☆☆ Deblur

★★☆☆☆ ML Frame Concealment

★☆☆☆☆ Software AV1 / software HEVC brute force
```

如果目标是移动视频通话，我不会首先投资：

```text
software slow encoder
```

而会首先投资：

```text
denoise + ROI + intelligent scaling
```


# 38. 延迟 Budget

建议明确设置 Compute Budget。

30fps：

```text
frame interval
33.3ms
```

例如发送端：

```text
Camera/ISP          5~10 ms
Preprocess          <= 5 ms
Encoder             <= 10 ms
Queue               <= 3 ms
```

这里很多模块实际上可以 pipeline 并行，不必完全串行相加。

重点监控：

```text
P50
P95
P99
```

不要只看平均值。


# 39. GPU / NPU 优先

处理：

```text
denoise
segmentation
SR
resize
```

优先：

```text
ISP
↓
NPU
↓
GPU
↓
CPU
```

具体顺序视设备能力而定。

核心原则：

> CPU 应该负责 orchestration，而不是承担所有像素计算。


# 40. 零拷贝 Pipeline

这个方案一旦大量：

```text
YUV → RGB
RGB → Bitmap
Bitmap → YUV
```

性能会直接被内存带宽浪费掉。

应尽量：

```text
Camera
↓
Surface / Texture
↓
GPU
↓
encoder Surface
```

实现：

```text
zero-copy / minimal-copy
```

很多时候：

> 内存搬运的功耗甚至比算法本身更不值得。


# 41. Benchmark 系统

必须建立 Device Capability Profile。

第一次或后台测试：

```text
720p30 encode
1080p30 encode

C1
C2
C3

Codec A
Codec B
```

记录：

```text
encode P95
frame drop
thermal slope
battery slope
GPU time
```

得到：

```text
DeviceProfile
```

例如：

```text
DEVICE_HIGH_END
DEVICE_MID
DEVICE_LOW
```


但不要只按：

```text
SoC 型号
```

硬编码。

因为 OEM thermal design 差别非常大。


# 42. 画质评价

开发阶段至少使用：

```text
PSNR
SSIM
VMAF
```

但最终一定要做：

```text
subjective evaluation
```

因为视频电话最重要的经常是：

```text
脸清不清楚
眼睛自然不自然
手有没有拖影
文字能不能看清
```

而不是一个单一 objective metric。


# 43. 测试 Dataset

自己建立 RTC dataset：

```text
Bright Face
Dark Face
Walking
Hand Gesture
Outdoor
Driving Passenger
Backlight
Fast Camera Pan
Fine Texture
Hair
Screen Text
PCB / Object Detail
```

对每个源视频跑：

```text
300 kbps
500 kbps
800 kbps
1 Mbps
1.5 Mbps
2 Mbps
3 Mbps
```

然后比较：

```text
baseline
vs
Compute-for-Quality
```


# 44. 最终推荐架构

第一阶段最值得实现的是：

```text
           Camera 1080p
                │
                ▼
      ┌───────────────────┐
      │ Scene Analyzer    │
      │ motion/noise/face │
      └─────────┬─────────┘
                │
                ▼
      ┌───────────────────┐
      │ Temporal Denoise  │
      └─────────┬─────────┘
                │
                ▼
      ┌───────────────────┐
      │ High Quality      │
      │ Resize            │
      └─────────┬─────────┘
                │
                ▼
      ┌───────────────────┐
      │ ROI Optimization  │
      └─────────┬─────────┘
                │
                ▼
      ┌───────────────────┐
      │ HW Encoder        │
      │ Dynamic Complexity│
      └─────────┬─────────┘
                │
                ▼
             WebRTC
                │
                ▼
          HW Decoder
                │
                ▼
      ┌───────────────────┐
      │ Optional SR       │
      │ + Dering/Deblock  │
      └─────────┬─────────┘
                │
                ▼
              Display
```

旁边一个统一控制器：

```text
Network
   +
QP
   +
Encode Time
   +
Motion
   +
Noise
   +
Thermal Headroom
          │
          ▼
┌──────────────────────┐
│ Quality Orchestrator │
└──────────────────────┘
```


# 45. 最终策略

最核心的策略可以压缩成：

```text
网络差
    ↓
先增加计算
    ↓
denoise ↑
ROI ↑
encoder complexity ↑
receiver SR ↑
    ↓
仍然不够
    ↓
resolution ↓
    ↓
仍然不够
    ↓
FPS ↓
```

而不是现在很多 RTC 系统简单地：

```text
网络差
↓
bitrate ↓
↓
resolution ↓
↓
画质糊掉
```

另一方向：

```text
性能充裕
+
thermal headroom 充裕
         ↓
主动提高 Compute Level
         ↓
不一定提高 bandwidth
         ↓
单位 bitrate 画质提高
```


# 46. 产品层面的最终定义

这套机制可以定义成：

## Adaptive Computational Video

传统 ABR：

```text
Network Adaptive Video
```

升级为：

```text
Network
+
Content
+
Compute
+
Thermal
+
Perceptual Quality

= Adaptive Computational Video
```

其核心不是追求最高：

```text
resolution
fps
bitrate
```

而是追求：

> **在当前网络、设备和热约束下的最大感知质量。**
