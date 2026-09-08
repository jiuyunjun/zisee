# AGENTS.md

## 项目名称

**咫尺 / Zisee**  
Slogan: **See closer, even from afar.**

## 项目目标

Zisee 是一个面向 Android 的原生高清实时视频通信与远程视觉协作应用。

核心目标包括：

- 1v1 高清、低延迟视频通话。
- 优先使用 P2P 直连，P2P 不可用时自动回退到 TURN。
- 支持前后摄像头同时采集与传输。
- 支持屏幕共享。
- 支持远程视觉协作，包括普通 2D 标注和基于 ARCore 的现实空间锚点标注。
- 在弱网、移动网络切换和设备性能受限情况下保持尽可能稳定的通话体验。
- 尽量降低中心服务器对媒体流的依赖和基础设施成本。
- 在功能、性能、隐私、功耗和可维护性之间保持合理平衡。

---

## 总体原则

### 1. 优先保证正确性

任何修改必须优先保证：

1. 功能正确。
2. 不破坏现有功能。
3. 不引入明显的并发、生命周期、资源泄漏、安全或隐私问题。
4. 代码能够正常构建。
5. 测试应尽可能通过。

不要为了“看起来更优雅”而进行与当前任务无关的大规模重构。

---

### 2. UTF-8 是唯一文本编码

项目中的所有文本文件必须使用 **UTF-8** 读写。

包括但不限于：

- `.kt`
- `.kts`
- `.java`
- `.xml`
- `.json`
- `.yaml`
- `.yml`
- `.toml`
- `.properties`
- `.md`
- `.txt`
- `.gradle`
- `.proto`
- shell 脚本
- CI 配置
- 文档

要求：

- 新建文本文件统一使用 UTF-8。
- 修改已有文本文件时必须保持 UTF-8。
- 不得使用 GBK、Shift-JIS、Windows-1252 等本地编码保存项目文件。
- 不得因为工具默认编码不同而改变现有文件编码。
- 对文件进行脚本化读写时必须显式指定 UTF-8。
- 除非某个外部协议或既有格式明确要求，否则不要添加 UTF-8 BOM。

例如 Python：

```python
Path(path).read_text(encoding="utf-8")
Path(path).write_text(content, encoding="utf-8")
```

---

## Git 与提交规范

### 3. 每次有意义的修改都必须 Commit

完成一个**有独立意义、可描述、可验证**的修改后，必须创建 Git commit。

不要把大量互不相关的改动积累到一个提交里。

典型的“有意义修改”包括：

- 新增一个功能。
- 完成一个子功能。
- 修复一个 Bug。
- 增加一组相关测试。
- 重构一个明确模块。
- 修改项目架构。
- 修改构建配置。
- 新增或更新重要文档。
- 完成一次明确的性能优化。
- 完成一个独立的 UI/UX 改动。

以下情况通常不需要单独提交：

- 尚未形成完整修改的临时编辑。
- 纯探索性代码且最终会被删除。
- 同一个功能中的极小连续调整。

原则：

> 一个 commit 应对应一个清晰、可解释的意图。

---

### 4. Commit 前必须检查

在创建 commit 前，应尽可能完成：

- 查看 `git diff`。
- 确认没有误修改无关文件。
- 确认没有提交密钥、Token、密码、证书或隐私数据。
- 确认新增文件确实需要进入版本控制。
- 运行与改动相关的测试。
- 条件允许时运行构建。
- 检查明显的 lint / compile error。
- 确认 UTF-8 编码未被破坏。

禁止使用：

```bash
git add .
```

作为无脑默认操作。

优先明确添加需要提交的文件：

```bash
git add path/to/file
```

或经过确认后再批量添加。

---

### 5. Commit Message

Commit message 使用简洁、明确的英文 Conventional Commits 风格：

```text
feat: add dual camera capture
fix: handle ICE reconnect after network switch
refactor: extract peer connection manager
docs: add WebRTC architecture notes
test: add signaling state tests
chore: update Android Gradle plugin
perf: reduce frame copy in video pipeline
```

推荐类型：

- `feat`
- `fix`
- `refactor`
- `perf`
- `test`
- `docs`
- `build`
- `ci`
- `chore`

要求：

- 一个提交只描述一个主要目的。
- 不要使用 `update stuff`、`changes`、`fix` 等模糊信息。
- 不要自动 amend、rebase、force push，除非用户明确要求。
- 不要修改或删除用户已有 commit。

---

## 工作方式

### 6. 修改前先理解现有实现

开始修改前：

1. 阅读相关代码。
2. 理解模块职责。
3. 查找已有工具类、抽象层和公共实现。
4. 查看相关测试。
5. 查看相关文档。
6. 避免重复造项目内部已经存在的轮子。

对于不熟悉的模块，不得仅凭文件名猜测实现。

---

### 7. 小步修改

优先采用小步、可验证的修改。

推荐：

```text
理解现状
→ 最小实现
→ 构建/测试
→ 检查 diff
→ commit
→ 继续下一步
```

避免：

```text
一次修改几十个文件
→ 最后统一调试
→ 无法确定问题来源
```

---

### 8. 不进行无关修改

执行任务时：

- 不随意格式化整个项目。
- 不顺手修改与任务无关的代码。
- 不因为个人偏好更换已有框架。
- 不随意重命名公共 API。
- 不进行没有明确收益的大规模抽象。
- 不删除看似无用但用途尚未确认的代码。

如果发现其他问题，可以记录，但不要擅自扩大当前任务范围。

---

## Android 技术原则

### 9. Android 原生优先

默认技术方向：

- Kotlin
- Android SDK
- Jetpack
- Jetpack Compose
- Coroutines / Flow
- CameraX
- 必要时 Camera2
- WebRTC
- ARCore
- MediaCodec
- MediaProjection
- OpenGL ES / Vulkan / Filament（按实际需要）

不为了跨平台而牺牲核心实时音视频能力。

除非明确决定，否则不引入 Flutter、React Native 等跨平台框架替代 Android 原生实现。

---

### 10. API 与兼容性

使用 Android API 前必须考虑：

- `minSdk`
- `targetSdk`
- API level 差异
- OEM 差异
- 权限变化
- 后台限制
- Foreground Service 要求
- Camera / MediaProjection 生命周期
- 硬件 codec 能力
- Concurrent Camera 支持情况
- ARCore / Depth API 支持情况

设备能力必须通过运行时检测，而不是假设所有设备均支持。

例如：

```text
AV1
Concurrent Camera
Depth API
Hardware encoder
HDR
Specific camera combinations
```

都需要 capability check 和 fallback。

---

## 实时音视频原则

### 11. 1v1 默认 P2P

目标媒体架构：

```text
P2P UDP
→ TURN UDP
→ TURN TCP/TLS fallback
```

控制面与媒体面分离。

中央服务主要承担：

- 鉴权
- Signaling
- Call state
- ICE / SDP 交换
- TURN credential 下发
- Push notification
- 必要的状态协调

1v1 不应因为实现方便而默认强制所有媒体经过 SFU。

---

### 12. TURN 是 fallback

TURN：

- 只作为 P2P 无法建立或链路质量明显不合格时的回退方案。
- 使用短期 credential。
- 不得把长期 TURN 密钥硬编码进 APK。
- 必须考虑流量成本。
- 记录 relay 使用比例和原因。

---

### 13. 多 Track 设计

媒体源应尽量保持独立 Track，例如：

```text
audio_microphone
video_front
video_back
video_screen
```

不要过早把前后摄像头永久合成为一个视频流。

独立 Track 有利于：

- 独立订阅。
- 动态码率。
- 动态分辨率。
- 主/辅画面切换。
- 节省带宽。
- 后续扩展更多视频源。

---

### 14. 前后摄像头

优先使用 CameraX Concurrent Camera。

要求：

- 启动前检查设备支持的 concurrent camera combinations。
- 不支持时优雅降级。
- 不假定所有前后摄组合均可同时打开。
- 注意 camera 生命周期和硬件资源竞争。
- 主摄和辅摄可以使用不同分辨率、帧率和 bitrate。

---

### 15. 网络适应

长期目标包括：

- RTT
- jitter
- packet loss
- estimated bandwidth
- candidate type
- network type
- Wi-Fi / cellular
- ICE state
- thermal state
- battery state
- encoder performance

基于这些指标动态调整：

- bitrate
- resolution
- FPS
- codec
- FEC
- retransmission strategy
- 主/辅 Track 资源分配

优先保持：

1. 通话连续。
2. 音频清晰。
3. 主要视觉目标清晰。
4. 低延迟。
5. 最后才是最高分辨率。

---

## AR Remote Collaboration

### 16. AR 是增强能力，不是产品唯一核心

AR 功能用于现实场景远程协作。

被指导方运行 ARCore。

指导方通常只需要：

- 查看远程视频。
- 点击目标位置。
- 创建箭头、圈选、Pin、文字等标注。

---

### 17. 空间标注

AR 标注必须区分：

#### 2D Annotation

绑定视频/屏幕坐标。

适用于：

- 普通画笔。
- 临时圈选。
- Pointer。
- 屏幕共享标注。

#### Spatial Annotation

绑定真实世界坐标。

适用于：

- 3D Anchor。
- 空间箭头。
- 现实物体标记。
- 测距。
- 持续追踪。

不要把两种坐标系统混在一起。

---

### 18. AR 时间同步

远程视频存在传输延迟。

远端用户点击的是历史视频帧，因此不能直接用现场端当前 ARCore Pose 进行空间投影。

设计必须考虑：

```text
Video Frame Timestamp
↕
ARCore Pose History
↕
Depth / Camera Intrinsics
```

目标：

根据用户点击所对应的视频帧时间，恢复尽可能接近该时刻的：

- Camera Pose
- Intrinsics
- Depth
- Tracking state

然后完成 2D → 3D 映射。

这是 AR Remote Annotation 的关键技术问题之一。

---

## 并发与生命周期

### 19. 不阻塞主线程

以下操作不得阻塞 Android 主线程：

- 网络 IO
- 编解码等待
- 文件 IO
- 数据库操作
- 重型图像处理
- AR 数据处理
- Signaling 请求
- TURN credential 获取

合理使用：

- Kotlin Coroutines
- Flow
- Dispatcher
- WebRTC callback thread
- Camera executor
- GL thread

---

### 20. 生命周期必须明确

特别关注：

- Activity
- Fragment
- ViewModel
- Camera
- PeerConnection
- MediaProjection
- ARCore Session
- AudioManager
- Encoder / Decoder
- Surface
- EGL Context
- Foreground Service

资源必须有明确 owner，并在适当时机释放。

禁止依赖 GC 自动释放摄像头、codec、WebRTC 或 AR 资源。

---

## 性能原则

### 21. 视频管线减少复制

高清视频实时处理必须尽量减少：

- Bitmap 转换
- CPU RGB/YUV 来回转换
- ByteArray 拷贝
- GPU → CPU readback
- 不必要的纹理复制
- 多次 resize

优先考虑：

```text
Camera
→ Surface / Texture
→ GPU
→ Encoder
```

而不是：

```text
Camera
→ Bitmap
→ CPU
→ Bitmap
→ GPU
→ Encoder
```

---

### 22. 性能修改必须可测

涉及：

- FPS
- latency
- bitrate
- CPU
- GPU
- temperature
- battery
- memory
- packet loss recovery

的优化，尽量保留前后对比数据。

不要仅凭肉眼感觉宣称“性能提升”。

---

## 安全与隐私

### 23. 不提交秘密信息

绝对禁止提交：

- API Key
- TURN Secret
- Access Token
- Refresh Token
- 私钥
- Keystore
- 密码
- 服务账号凭证
- 用户隐私数据
- 生产环境配置

敏感配置应使用：

- 环境变量
- Local properties
- Secret manager
- CI secret
- 服务端动态下发

必要文件加入 `.gitignore`。

---

### 24. 最小权限

只申请当前功能实际需要的 Android 权限。

权限必须：

- 延迟申请。
- 在用户触发相关功能时申请。
- 明确解释用途。
- 拒绝后提供合理降级。

不要启动 App 就一次请求全部权限。

---

## 错误处理

### 25. 不静默吞异常

禁止：

```kotlin
try {
    ...
} catch (e: Exception) {
}
```

必须至少：

- 记录必要日志。
- 转换成明确状态。
- 或交给上层处理。

但日志不得包含：

- Token
- 密码
- SDP 中不必要的敏感信息
- 用户私人视频/音频数据
- 精确隐私信息

---

### 26. 状态机优先

复杂实时功能应明确建模状态，而不是大量 Boolean 拼接。

例如通话连接：

```text
Idle
Calling
Connecting
Connected
Reconnecting
Ending
Ended
Failed
```

Camera、screen share、AR session 等复杂模块同样优先考虑显式状态机。

---

## 测试

### 27. 测试分层

优先覆盖：

#### Unit Test

- 状态机
- Signaling
- 坐标转换
- 网络策略
- Codec capability
- 数据协议
- AR 时间戳匹配逻辑

#### Integration Test

- PeerConnection
- ICE
- TURN fallback
- 双摄
- Screen Share
- DataChannel
- reconnect

#### Device Test

必须真实设备验证：

- CameraX Concurrent Camera
- MediaCodec
- ARCore
- Depth API
- MediaProjection
- Wi-Fi / Cellular 切换
- 蓝牙耳机
- 前后台切换

模拟器不能替代真实音视频设备测试。

---

## 日志与可观测性

### 28. RTC 必须可诊断

建议记录结构化指标：

- callId
- peerId
- ICE connection state
- selected candidate pair
- candidate type
- RTT
- jitter
- packet loss
- available outgoing bitrate
- actual bitrate
- codec
- resolution
- FPS
- encoder implementation
- reconnect count
- TURN fallback reason

日志和指标应便于后续定位：

- 为什么没走 P2P
- 为什么发生卡顿
- 为什么降码率
- 为什么切换编码器
- 为什么重连

---

## 文档

### 29. 重要设计必须记录

以下内容发生变化时应同步更新文档：

- 架构
- 网络协议
- Signaling 流程
- Track 设计
- AR 坐标体系
- 数据协议
- 安全策略
- 构建方式
- 部署方式
- 重要技术取舍

不要让关键设计只存在于聊天记录或某个人脑中。

---

## 依赖管理

### 30. 谨慎引入依赖

新增第三方依赖前检查：

- 是否真的需要。
- 是否仍在维护。
- License 是否兼容。
- Android 体积影响。
- native library 体积。
- minSdk 要求。
- 权限要求。
- 安全记录。
- 是否可以使用已有依赖解决。

不要为了很小的工具函数引入大型库。

---

## Agent 工作完成标准

### 31. Definition of Done

一个任务只有在尽可能满足以下条件后才算完成：

- 需求已经实现。
- 代码与现有架构一致。
- 没有明显无关改动。
- UTF-8 编码正确。
- 编译通过，或明确说明无法验证的原因。
- 相关测试通过，或明确说明未通过原因。
- 必要文档已更新。
- `git diff` 已检查。
- 没有敏感信息进入版本控制。
- 已创建一个或多个有意义的 Git commit。
- 最终回复简要说明：
  - 修改了什么
  - 如何验证
  - 创建了哪些 commit
  - 是否存在已知限制

---

## 禁止事项

除非用户明确要求，否则禁止：

- 擅自 force push。
- 擅自 rewrite Git history。
- 删除用户未提交的修改。
- 使用 `git reset --hard` 清除现有工作。
- 擅自删除分支。
- 提交敏感信息。
- 大范围无关格式化。
- 隐藏构建或测试失败。
- 编造测试成功结果。
- 假装验证过未实际验证的设备能力。
- 假设所有 Android 设备行为一致。
- 为追求抽象而过度设计。

---

## 核心工程价值观

对于 Zisee：

> **稳定连接优于参数好看。**  
> **真实低延迟优于虚假的“超高清”。**  
> **可诊断优于黑盒。**  
> **可降级优于假设设备完美支持。**  
> **小步、可验证、可回退的工程修改优于一次性大改。**
