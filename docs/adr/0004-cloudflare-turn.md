---
title: Use Cloudflare TURN for Fallback Relay
adr: 0004
status: Accepted
date: 2026-09-08
---

# Context

[ADR 0002](./0002-p2p-first.md) 确立 P2P First，但要求无法直连时必须自动回落到 TURN。因此需要一个 TURN 通道，问题是自建还是托管。

第一阶段的现实约束是：单人/小团队维护，通话量小，TURN 只在 fallback 时使用。

参考：`ARCHITECTURE.md` §9.2、§9.3。

# Decision

早期使用：

```text
Cloudflare TURN
```

TURN credential 不允许硬编码在 APK 内，必须由客户端向已认证的后端换取短期有效的 credential。

演进路径：

```text
Cloudflare TURN
        ↓
Hybrid
        ↓
Self-hosted TURN Cluster
```

# Alternatives

## 自建 coturn

优点：

- 完全自主可控
- 大规模时单位成本更低

缺点：

- 需要维护实例、证书、端口、监控与扩容
- 早期通话量下，运维成本远大于收益

## 不提供 TURN

已被 [ADR 0002](./0002-p2p-first.md) 排除：接通率是第一优先级，没有 fallback 无法保证接通。

# Consequences

优点：

- 无需维护实例，适合当前规模
- TURN 只在 fallback 路径上，成本可控
- 后续切换自建 coturn 时，客户端只需更换 ICE server 配置

缺点：

- 依赖外部服务的可用性与计费策略
- 必须实现「后端签发短期 credential」这条链路，不能省略
- 需要观测 TURN fallback 率，否则无法及时发现 P2P 成功率下滑带来的成本上升

长期影响：

- 迁移到自建 TURN 集群时应新增 ADR 并将本条标记为 Superseded，而不是就地改写
