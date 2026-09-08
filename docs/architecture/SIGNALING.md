---
title: Zisee 信令会话基础
document_id: ARCH-SIGNALING-001
version: 1.3.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "server 0.1.0-dev"
owners:
  - backend
  - android
---

# 信令会话基础

当前实现认证后的 WebSocket 连接、心跳、按需通话快照和持久化初次 Offer/Answer 投递。邀请、接听、拒绝和挂断通过 HTTP 完成，见 [通话协议](../protocols/CALL_PROTOCOL.md)。ICE 候选先收集进 SDP；尚未实现 Trickle ICE。不得把信令连接成功或 accepted 状态当作媒体通话成功。

## 建立连接

- 路径：`wss://<service>/v1/signaling`。
- HTTP 握手带 `Authorization: Bearer <accessToken>`，令牌来自 [身份协议](../protocols/IDENTITY_PROTOCOL.md)。
- 必须协商子协议 `zisee.v1`，原生客户端不发送 Origin。不得把 token 放在 URL。
- 服务端最多接收 64 个并发连接/实例，每条文本消息最多 64 KiB（SDP 字段 48 KiB），拒绝二进制数据。

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

当前 PostgreSQL 保存身份、挑战、令牌、邀请、通话和短期 SDP。多实例可查询同一通话和对端描述。`call.sync` 查询状态，`media.send`/`media.sync` 提交及查询初次 SDP；`media.ice` 追加累计 ICE 候选，`media.sync` 独立于 SDP 游标返回对端候选；通过稳定消息 ID 和游标支持传输重连，不依赖进程内连接表。

下一步需要 Trickle ICE、ICE restart、媒体状态恢复和短期 TURN credential 鉴权。媒体不进入信令服务。

# Changelog

## 1.3.0 - 2026-09-08

- 增加初次媒体协商与短期 SDP 投递；消息上限调整为 64 KiB。

## 1.2.0 - 2026-09-08

- 接入参与者授权的 call.sync 快照，保留心跳客户端兼容性；新增通话协议链接。

## 1.0.0 - 2026-09-08

- 定义已实现的认证连接、心跳、连接界限和 Cloud Run 路由约束。

## 1.1.0 - 2026-09-08

Android 已实现认证握手、心跳、令牌更新、有界退避及前后台取消，尚未与 Go 完整联调，见 [接入说明](../development/ANDROID_BACKEND.md)。
