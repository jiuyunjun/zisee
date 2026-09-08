---
title: P2P First with TURN Fallback
adr: 0002
status: Accepted
date: 2026-09-08
---

# Context

Zisee 第一阶段只做 1v1。媒体路径有两种基本形态：

```text
两端直连
```

或：

```text
两端都连到服务端，由服务端中转/转发
```

这个决策同时影响延迟、服务器成本、隐私表达，以及产品能承受的码率上限。

参考：`ARCHITECTURE.md` §3.1、§9.1，`PRODUCT.md` §9、§21。

# Decision

媒体路径采用：

```text
P2P First
```

ICE 候选顺序为 host → srflx → relay，优先建立直连；无法直连时自动回落到 TURN 中转。

同时确立一条边界：

> P2P 不能以牺牲接通率为代价。

TURN fallback 必须自动完成，用户不做任何选择，界面上也不暴露 ICE / candidate 概念（`DESIGN.md` §71）。

# Alternatives

## 始终走服务端中转（SFU / MCU）

优点：

- 连接成功率稳定，不受 NAT 类型影响
- 便于录制、转码、多人扩展

缺点：

- 1v1 场景下多一跳，延迟更高
- 媒体流全部经过服务端，与隐私原则冲突
- 带宽成本随通话时长线性增长，个人项目难以承担高码率

## 只做 P2P，不做 TURN

优点：

- 架构最简单，零媒体服务器成本

缺点：

- 对称 NAT、部分移动网络下直接打不通
- 接通率不可控，违反 `PRODUCT.md` §14 中「能接通」是第一优先级

# Consequences

优点：

- 1v1 延迟更低
- 服务端只承担 Control Plane，不承担 Media Plane（`ARCHITECTURE.md` §3.2）
- 基础设施成本低，因此可以允许更高的 bitrate
- 「媒体默认不经过我们的服务器」可以作为真实的产品表达

缺点：

- 必须实现并运维 TURN 通道作为兜底（见 [ADR 0004](./0004-cloudflare-turn.md)）
- 需要持续观测 P2P 成功率与 TURN fallback 率，否则无法判断策略是否有效
- 网络切换时的 ICE restart 与重连逻辑必须自己实现

长期影响：

- 引入多人会议时，P2P First 不再适用于该场景，需要新增 ADR 描述 SFU 路径，本条对 1v1 继续有效
