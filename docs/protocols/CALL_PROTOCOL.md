---
title: Zisee 邀请与通话控制协议
document_id: PROTO-CALL-001
version: 1.0.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "server 0.1.0-dev"
owners:
  - backend
  - android
---

# 邀请与通话控制

本版实现服务端邀请、呼叫、接听、拒绝、挂断和按需状态同步。Android 页面尚未接入，尚无 SDP/ICE 转发、推送或音视频。`accepted` 只表示被叫同意，不表示媒体连接成功。

## HTTP

所有请求必须使用身份协议签发的 `Authorization: Bearer`。操作方取自认证会话，不能在请求中指定。JSON 请求遵循现有 8 KiB 限制；所有 URL 禁止 query，邀请 token 只能出现在请求正文中。

| 方法与路径 | 请求 | 响应 |
| --- | --- | --- |
| POST `/v1/invites` | 无正文 | `{token, expiresAt}` |
| POST `/v1/invites/redeem` | `{token}` | Call |
| GET `/v1/calls/current` | 无正文 | Call 或 `null` |
| GET `/v1/calls/{callId}` | 无正文 | Call |
| POST `/v1/calls/{callId}/actions` | `{action: "accept"或"reject"或"end"}` | Call |

Call 包含 `callId`、`callerId`、`calleeId`、`state`、`expiresAt`，时间使用 RFC3339。成功返回 200。没有权限查看与不存在的 callId 均返回 404 `call_not_found`，避免泄漏第三方通话。

## 邀请与并发

邀请 token 为 32 字节密码学随机数的无填充 base64url，数据库仅存 SHA-256。有效期 10 分钟，每个身份仅保留一条邀请；创建新邀请立即撤销旧 token。邀请是持有者凭据，只应分享给目标联系人。

兑换方为 caller，邀请创建者为 callee。不可兑换自己的邀请。一次邀请只能由一个身份兑换；同一身份在邀请仍有效且未被替换时重试返回同一 Call，包括已结束的状态。其他身份再次兑换、过期或无效 token 均返回 404 `invite_unavailable`。

PostgreSQL 事务锁定邀请，并按固定顺序锁定双方身份；同一身份最多参与一通有效的 ringing/accepted 通话。竞争兑换其他邀请返回 409 `participant_busy`，失败不会消耗该邀请。状态由共享数据库提供，服务实例重启不会丢失，也不依赖连接粘性。

## 状态

兑换成功创建 `ringing`，60 秒内必须接听。仅 callee 可执行 accept/reject，任一参与者可 end。允许转换：

```text
ringing → accepted → ended
ringing → rejected
ringing → ended
ringing / accepted → expired（达到期限）
```

重复相同的已完成动作返回原状态，不延长期限。无权执行或不允许的转换返回 409 `invalid_call_transition`。行锁串行化竞争动作，已挂断不可重新接听。

当前 accepted 最长有效 1 小时，尚未实现媒体保活续期。到期在读取和动作校验时直接计算为 expired，无需等待后台任务；不再占用忙线。过期邀请由现有分钟清理任务删除，Call 在 expiresAt 后 24 小时删除。暂不作为永久通话历史。

## WebSocket 按需同步

沿用 `/v1/signaling` 和 `zisee.v1`。服务端不会主动发送新的事件，旧客户端的 ready/ping/pong 行为保持兼容。新客户端可发送：

```json
{"v":1,"type":"call.sync","id":"request-1","callId":"<callId>"}
```

返回 `{v:1,type:"call.snapshot",id,call:Call}`。省略 callId 查询本人当前通话，没有则 call 为 null。指定 callId 可查询挂断或拒绝结果；错误返回 `{v:1,type:"error",id,error:"call_not_found"}` 等固定错误码。每条消息重新验证令牌，并按认证身份验证通话归属。

这是请求/响应快照，不是事件队列：中间状态可能被跳过。页面接入时前台建议每 2 秒查询，后台取消；知道 callId 后持续查询该 ID 获取终态。后续 SDP/ICE 需要独立的可恢复消息投递协议，不能用快照轮询代替。

## 迁移与验证

迁移器保留兼容旧库的身份基础建表，以 `schema_migrations.version=2` 记录 `002_calls.sql`；在现有迁移事务和互斥锁内一次应用。运行时没有 DDL 权限要求，需授予新表 SELECT/INSERT/UPDATE/DELETE。readyz 要求新表存在，部署新版本前运行迁移。

真实 PostgreSQL 集成测试覆盖跨连接并发重试、竞争邀请忙线、越权、转换、过期、邀请轮换、清理，以及 HTTP 与认证 WebSocket 快照。没有验证 Android 双机音视频或 Cloud Run 部署。

# Changelog

## 1.0.0 - 2026-09-08

- 实现邀请兑换、持久通话状态和受归属约束的按需快照协议。
