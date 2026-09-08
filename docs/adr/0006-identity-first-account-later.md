---
title: Identity First, Account Later
adr: 0006
status: Accepted
date: 2026-09-08
---

# Context

Zisee 前期不打算强制注册登录，但未来很可能接入 Google / Passkey 等正式登录方式。

如果前期完全不建立用户模型，等到接登录系统时，联系人、通话历史、邀请关系都会因为「换了一个用户主键」而断裂，核心用户模型需要重构。

参考：`ACCOUNT.md` §1、§2、§18、§49。

# Decision

从第一天起就区分两个概念：

```text
Identity  = 这个 Zisee 用户是谁
Account   = 用户如何证明自己拥有这个 Identity
```

Phase A 只创建 Local Identity：用户输入姓名，生成稳定的 `identityId`（UUIDv7 / ULID，带 `zid_` 前缀），保存本地，直接进入 App。此时 `account = none`，但 `identity` 已存在。

约束：

- `identityId` 与姓名、设备型号无关，不使用 IMEI / MAC / 手机号 / Android ID
- `identityId` 是公开标识，不是认证凭据
- 联系人与通话记录引用 `identityId`，`displayName` 只是展示快照
- 改名只改 `displayName`，永不改 `identityId`
- 未来绑定账户是「升级现有 Identity」，不是重新注册

# Alternatives

## 完全不做身份，等接登录时再说

优点：

- 前期代码最少

缺点：

- 联系人和历史无法稳定引用任何主键
- 接入登录时必须迁移或丢弃既有关系数据，代价随用户量增长

## 一开始就要求注册登录

优点：

- 用户模型从一开始就完整，多设备恢复天然成立

缺点：

- 首次体验门槛高，与「接通快、几乎不用学习」的产品目标冲突
- MVP 阶段需要提前建设完整认证体系，挤占 RTC 的开发时间

# Consequences

优点：

- 首次体验保持「输入姓名 → 立即使用」，不需要邮箱、手机号、密码、验证码
- 未来绑定账户时，`identityId`、联系人、通话历史、Block list、邀请关系全部保留
- 避免账户系统接入时重构核心用户模型

缺点：

- 未绑定账户时，卸载 App 或丢失本地 credential 可能导致身份无法恢复。这一点必须如实告知用户，不能假装未来一定能恢复（`ACCOUNT.md` §22）
- 需要提前设计 `identityId` / `deviceId` / device credential / access token 的分层，比「先不做」复杂
- 未来绑定时可能出现 Account Conflict（该账户已绑定另一个 Identity），第一版不做自动 merge，只让用户明确二选一（`ACCOUNT.md` §41）

长期影响：

- Public username / handle 属于第三个字段，不与 `displayName` 或 `identityId` 混用；如果将来要做搜索加好友，应新增 ADR
