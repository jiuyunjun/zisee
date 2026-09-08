---
title: Use Native WebRTC
adr: 0001
status: Accepted
date: 2026-09-08
---

# Context

Zisee 需要一个实时音视频底座，支撑 1v1 高清通话、前后双摄多 Track、屏幕共享与 DataChannel。

候选底座主要分两类：

```text
自己直接使用 WebRTC Native
```

和：

```text
使用上层 SDK（LiveKit 等）封装 WebRTC
```

这个选择会影响 Media Path 的可控程度、服务端依赖，以及后续 Bandwidth Follows Attention、AR 时间同步等能力的实现空间。

参考：`ARCHITECTURE.md` §6.1、§6.2。

# Decision

Android 客户端的 RTC 底座使用：

```text
WebRTC Native
```

LiveKit 等上层 SDK 可以用于技术验证和早期 prototype，但不作为当前基础架构的硬依赖。

# Alternatives

## LiveKit

优点：

- 上手快
- Track abstraction 完整
- Screen Share、录制、RPC 生态成熟
- 天然支持多人与 SFU

缺点（对当前阶段）：

- 核心形态是 SFU，而 Zisee 第一阶段是 1v1 P2P First
- Media Path 被 SDK 与服务端约束
- 为了 1v1 引入服务端媒体依赖，与 P2P First 冲突（见 [ADR 0002](./0002-p2p-first.md)）

## 自研 RTC 栈

直接排除。ICE、SRTP、DTLS、拥塞控制、NACK/RTX 的工程量远超项目范围，`PRODUCT.md` §16 已明确不做自研 WebRTC。

# Consequences

优点：

- 原生具备 ICE / STUN / TURN / SRTP / DTLS / RTP / RTCP / NACK / RTX / 拥塞控制 / DataChannel
- Media Path 完全可控，便于实现多 Track 独立码率与主辅画面切换
- 1v1 场景下不需要中心化媒体服务器

缺点：

- 需要自己实现 Signaling、房间与呼叫状态机
- 需要自己处理 Android 端 Camera / Renderer / Codec 集成细节
- 开发初期速度慢于直接使用上层 SDK

长期影响：

- 未来若需要 3 人以上会议，需要单独引入 SFU，届时应新增 ADR 而不是修改本条
