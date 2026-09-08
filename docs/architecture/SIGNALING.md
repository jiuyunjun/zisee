---
title: Zisee 信令会话基础
document_id: ARCH-SIGNALING-001
version: 1.0.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "server 0.1.0-dev"
owners:
  - backend
  - android
---

# 信令会话基础

当前实现的是认证后的 WebSocket 连接与心跳。邀请、CALL_INVITE／ACCEPT／REJECT／END、SDP／ICE 转发尚未实现；不得把当前连接成功当作通话成功。下一步在此基础上实现邀请授权、通话状态和跨实例消息路由。

## 建立连接

- 路径：`wss://<service>/v1/signaling`。
- HTTP 握手带 `Authorization: Bearer <accessToken>`，令牌来自 [身份协议](../protocols/IDENTITY_PROTOCOL.md)。
- 必须协商子协议 `zisee.v1`，原生客户端不发送 Origin。不得把 token 放在 URL。
- 服务端最多接收 64 个并发连接/实例，每条文本消息最多 4 KiB，拒绝二进制数据。

连接成功，服务端首先发送：

```json
{"v":1,"type":"session.ready","expiresAt":"<RFC3339>"}
```

客户端约每 20 秒发送：

```json
{"v":1,"type":"ping","id":"<client correlation ID>"}
```

服务端返回同一 `id` 的 `pong`。`id` 最长 64 字节，可省略。这些业务心跳用于检测连接和重新核验会话；不能只依赖底层 WebSocket ping frame。单连接业务消息处理上限约 10 次/秒。

无业务消息 45 秒、令牌到期、服务关闭或网络中断均会断开连接。退出／重新认证后的旧连接在下一次业务消息时被拒绝；空闲旧连接最多在读超时后清理。客户端通过新挑战取得新令牌后重连，使用带随机抖动的退避，不能无限高频重试。

未知消息类型或错误版本关闭连接，不尝试向客户端指定的任意 peer 转发数据。

## Cloud Run 边界

Cloud Run WebSocket 仍受请求超时约束，session affinity 仅尽力而为。未来通话路由必须使用跨实例共享状态／消息投递，不能依赖单进程 `map[peer]connection`，也不能依赖 max-instances=1 来保证正确性。[官方 WebSocket 说明](https://docs.cloud.google.com/run/docs/triggering/websockets)

当前 PostgreSQL 保存身份、挑战和令牌，多实例可验证同一会话。当前 WebSocket 仅有本连接心跳，不存在尚未实现却宣称可用的跨实例通话转发。

通话协议接入前需要定义：邀请 token 的使用次数和到期、callId 归属、双方同意后的 SDP／ICE 交换、单调序号／消息 ID、重复消息、断线恢复、呼叫超时、忙线、挂断及短期 TURN credential 鉴权。媒体不进入信令服务。

# Changelog

## 1.0.0 - 2026-09-08

- 定义已实现的认证连接、心跳、连接界限和 Cloud Run 路由约束。
