---
title: Zisee 邀请与通话控制协议
document_id: PROTO-CALL-001
version: 1.1.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "server 0.1.0-dev"
owners:
  - backend
  - android
---

# 邀请与通话控制

本版实现邀请、呼叫、接听、拒绝、挂断、按需状态同步和初次 Offer/Answer 投递。`accepted` 只表示被叫同意，媒体连接由客户端 ICE 和实际收发状态判断。尚无后台推送。

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

这是请求/响应快照，不是事件队列：中间状态可能被跳过。知道 callId 后持续查询该 ID 获取终态。Android 前台约每秒查询，后台取消。

## 初次媒体协商

WebSocket 单条消息上限为 64 KiB，SDP UTF-8 上限为 48 KiB。所有媒体消息重新验证会话及 callId 归属，只允许 accepted 且未过期的通话发送。原有 HTTP 正文上限保持 8 KiB。

发送：

```json
{"v":1,"type":"media.send","id":"<stable message ID>","callId":"<call ID>","description":{"type":"offer","sdp":"<SDP; candidates sent separately>"}}
```

成功响应 `{v:1,type:"media.ack",id,sequence:1}`。只有 caller 可发送 offer（序号 1），只有 callee 可发送 answer（序号 2），answer 必须在 offer 后发送。每个角色只能提交一份 SDP，重复同一 ID 与同一内容返回原确认，其他重复返回 invalid_call_transition。每代仅支持一轮协商；网络变化的代次重启见后文，不支持任意 Track renegotiation。

接收：

```json
{"v":1,"type":"media.sync","id":"<request ID>","callId":"<call ID>","after":0}
```

返回 `{v:1,type:"media.snapshot",id,snapshot:{call:Call,descriptions:[{sequence,type,sdp}],candidates:[{candidate,sdpMid,sdpMLineIndex}]}}`。仅返回对端且 sequence 大于 after 的描述；客户端在成功设置远端描述后推进游标。重连沿用游标和本地 SDP 消息 ID，无需依赖同一实例。终态返回空描述和最新通话状态。

描述保存在 PostgreSQL 版本 3 的 media_descriptions 表，每通话最多两条，只允许接听后两分钟内初次提交。读取仅返回两分钟内描述；分钟清理任务删除过期数据，因此后台物理清理通常不超过三分钟。正常挂断立即删除描述。SDP 包含网络地址等敏感信息，禁止日志输出或提交到仓库；云端数据库上线前应采用既定传输与存储保护。

## Trickle ICE

SDP 创建并设置后立即发送，不等待 gathering COMPLETE。后续候选通过 `media.ice` 发送，响应 `media.ack`：

```json
{"v":1,"type":"media.ice","id":"q3","callId":"<call ID>","candidates":[{"candidate":"candidate:...","sdpMid":"0","sdpMLineIndex":0}]}
```

每端发送累计、只追加的候选列表（最多 32 条，每条 candidate 最多 1024 UTF-8 字节，mid 最多 32 字节，m-line index 为 0–8）。服务器验证通话成员、accepted 状态及该端 SDP 已存在；前缀相同的重复或较旧批次为幂等成功，修改既有候选被拒绝。客户端仅在 ack 后记录发送数量，网络失败会重发。

`media.sync` 先投递 SDP，客户端用 after 确认该描述后，后续快照的 candidates 返回对端累计列表（没有候选时可为 null），候选数量与 SDP 游标独立。分开投递避免 SDP 加候选超过单条消息上限；客户端先设置远端 SDP，再依次 addIceCandidate，成功后推进本地候选数量。连接阶段以 100 ms 基础间隔轮询（另有请求往返和服务端节流），连接后恢复 1 秒。后到的 TURN 候选仍可加入，不强制 relay。

候选与 SDP 存在同一行／Firestore 文档，沿用两分钟读取窗口和挂断／过期清理。PostgreSQL 需先运行版本 4 迁移；Firestore 字段自动兼容旧文档。**先升级服务器，再安装新客户端；新客户端依赖 media.ice，不能与旧服务端配套使用。** 旧客户端仍可与新服务器配套，混合客户端通话不承诺消除旧端 gathering 等待。

参考：[WebRTC Trickle ICE](https://webrtc.org/getting-started/peer-connections)。

## 迁移与验证

迁移器保留兼容旧库的身份基础建表，以 `schema_migrations` 记录 `002_calls.sql` 和 `003_media.sql`、`004_trickle.sql`；在现有迁移事务和互斥锁内依次应用。运行时没有 DDL 权限要求，需授予新表 SELECT/INSERT/UPDATE/DELETE。readyz 要求新表存在，部署新版本前运行迁移。

真实 PostgreSQL 集成测试覆盖跨连接并发重试、竞争邀请忙线、越权、转换、过期、邀请轮换、清理，以及 HTTP 与认证 WebSocket 快照。没有验证 Android 双机音视频或 Cloud Run 部署。

# Changelog

## 1.1.0 - 2026-09-08

- 增加双方同意后的初次 Offer/Answer 投递、角色限制、幂等确认、游标及 SDP 保留窗口。

## 1.0.0 - 2026-09-08

- 实现邀请兑换、持久通话状态和受归属约束的按需快照协议。


## 网络切换与 ICE 重启（2026-09-08）

`media.sync` 快照新增 `generation`（初始为 0）。`media.send`、`media.ice` 必须携带当前 generation；缺失按 0 兼容旧端的初次通话。每代仍只有 caller offer、callee answer，sequence 仍为 1/2，每代独立重置 SDP 游标、稳定消息 ID 和候选数量。

任一端发送 `{v:1,type:"media.restart",id,callId,generation:<当前代>}`。服务器在 accepted、未过期、成员校验后，以当前代作 compare-and-swap：相同代请求推进一次；另一端或 ACK 丢失后的旧代重试返回已推进的代，不再次递增。每代最短间隔 5 秒，每通话最多 64 次重启；请求太早返回原代，客户端稍后重试。响应为 `{v:1,type:"media.ack",id,generation}`。客户端只从 media.sync 权威快照采用新代。

过期代的 SDP／候选返回 `stale_media_generation`，客户端重新同步而非挂断。调用方永远由原 caller 发 offer，两端同时换网不会产生双 offer。采用新代前刷新短期 TURN credential、更新 PeerConnection 配置；保留采集和 Track，调用 caller 的 restartIce，然后交换新 SDP。未应答的旧本地 offer 先 rollback。旧候选队列清空，native 带 ufrag 的迟到候选按新 SDP 凭据过滤。

PostgreSQL 迁移 **005_ice_restart.sql** 增加通话代次和代次开始时间；推进时事务删除旧媒体行。Firestore 使用代次独立文档 ID，快照只查当前代，旧代沿用两分钟清理、结束时一起清理，避免后台清理任务误删新代文档。SDP 的两分钟投递窗口改为每代开始起算，因此长时通话也可重新协商。

发布必须先升级服务端（PostgreSQL 先迁移 5），再更新双方 APK。旧客户端不处理新代，混合版本不保证换网恢复。初次连接仍兼容 generation 0。

验证：PostgreSQL 和 Firestore 模拟器集成回归覆盖推进、另一端重复请求合并、旧代写入拒绝、新代候选隔离和新 offer/answer。Android 原生模拟器回环验证 ICE 凭据变化且解码继续；这不替代 Wi-Fi↔蜂窝双真机验证。
