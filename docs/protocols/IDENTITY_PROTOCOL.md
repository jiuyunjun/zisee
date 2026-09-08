---
title: Zisee 身份认证协议 v1
document_id: PROTO-IDENTITY-001
version: 1.0.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "server 0.1.0-dev"
owners:
  - backend
  - android
---

# 身份认证协议 v1

本协议实现 [ACCOUNT.md](../product/ACCOUNT.md) Phase B 的服务端边界。Android 当前仍是 Phase A，尚未生成 Keystore 设备密钥或调用这些接口。MVP 一个身份对应一个设备；绑定账户、多设备、密钥轮换与恢复后续另行设计。

传输使用 HTTPS，UTF-8 JSON；HTTP 路径统一带 `/v1`。请求体上限 8 KiB，不接受未知 JSON 字段或多个 JSON 值。认证只接受 `Authorization: Bearer <accessToken>`，不使用 cookie，不接受 URL 查询参数。当前原生客户端专用，带 Origin 的请求被拒绝。

## 字段与密钥

- `identityId`：沿用 Android 已创建的 `zid_` + 小写 UUIDv7；不得因服务端注册而重建本地身份。
- `deviceId`：客户端随机生成，`zdev_` + 22–64 个 base64url 字符，不使用硬件 ID；在 bootstrap 前持久化。
- `displayName`：1–40 个 Unicode code point，不含控制字符；bootstrap 要求首尾已 trim，改名接口会 trim。
- `publicKey`：Android Keystore EC P-256 公钥的 X.509 SubjectPublicKeyInfo DER，编码为无 padding 的 base64url。
- `signature`：`SHA256withECDSA`，签名结果是 ASN.1 DER 编码，再转无 padding 的 base64url；不是 64 字节 P1363 签名。
- 私钥只在客户端，服务端只存公钥。身份 ID 和设备 ID 都不是认证凭据。

## 1. Bootstrap

`POST /v1/identity/bootstrap`

请求字段：`identityId`、`deviceId`、`displayName`、`publicKey`、`signature`。

签名字节精确定义如下：LF 分隔，无末尾 LF，所有文本 UTF-8。

```text
zisee.bootstrap.v1
{identityId}
{deviceId}
{publicKey}
{base64url(UTF-8(displayName))}
```

成功返回 HTTP 200，包含 `identityId`、`displayName`、`createdAt`、`updatedAt`。时间使用 RFC3339 UTC。

服务端验证私钥持有证明后，事务性创建身份和设备。相同身份、设备、公钥的有效签名重试是幂等的，返回现有记录；不会重命名或重置已存在记录。相同身份换设备／公钥或设备已被其他身份占用时返回 409，不自动覆盖或合并。

这一步属于首次登记，并非已有身份的所有权恢复。客户端不得在首次登记成功前把本地 identityId 当作已经由服务器认证的身份发布。注册前已暴露且被抢先登记的 ID 会冲突，不能靠重新提交 ID 取回。

## 2. 获取挑战

`POST /v1/auth/challenge`

```json
{"deviceId":"zdev_<random>"}
```

返回 `challengeId`、`nonce`、`expiresAt`。前两者各使用 32 字节密码学随机值；挑战有效 2 分钟。每设备最多 5 个未过期挑战，超过返回 429。多个实例共用数据库限额。

## 3. 兑换访问令牌

对以下 UTF-8 字节使用设备私钥签名：LF 分隔，无末尾 LF。

```text
zisee.auth.v1
{challengeId}
{nonce}
```

`POST /v1/auth/token`，请求字段 `challengeId`、`signature`。

成功返回 `accessToken`、`tokenType: "Bearer"`、`expiresAt`。令牌是 32 字节随机值的 base64url，15 分钟有效，服务端仅存 SHA-256 哈希。

验证签名后，挑战删除与新会话创建处于同一事务，并发兑换最多成功一次。相同设备的新认证会撤销旧令牌；失败签名不能获得令牌。挑战／令牌过期与不存在统一返回 401。

响应丢失：重新申请挑战并认证；不尝试重复兑换原挑战。没有长期 refresh token，过期后再次通过私钥证明所有权，不改变 identityId。

## 4. 本人资料与退出

以下接口均要求 Bearer 令牌：

| 请求 | 行为 |
|---|---|
| `GET /v1/identity` | 返回当前认证身份；不接受客户端指定另一个身份 |
| `PATCH /v1/identity`，`{"displayName":"新名字"}` | 修改展示名，保留 ID 和创建时间 |
| `DELETE /v1/auth/session` | 删除当前令牌，返回 204，不删除身份或设备 |

设备公钥撤销字段已预留，但没有管理端撤销 API。账户恢复、设备更换和冲突处理不能伪装成改名。

## 错误与资源界限

统一错误格式：`{"error":"<code>"}`。

| HTTP | code |
|---|---|
| 400 | `invalid_request` / `query_not_allowed` |
| 401 | `unauthorized` |
| 403 | `browser_origin_not_allowed` |
| 409 | `identity_conflict` |
| 415 | `json_required` |
| 429 | `rate_limited` |
| 503 | `temporarily_unavailable` / `not_ready` / `connection_limit` |

响应标记 `Cache-Control: no-store`。服务端不记录原始请求、token、姓名、数据库 URL 或数据库错误详情。单实例设 30 HTTP 请求/秒上限，不能替代公网全局注册配额与反滥用策略；公开部署前必须补齐。

数据库每分钟尽力清理过期挑战与会话；认证查询本身强制检查过期时间，清理延迟不会延长令牌寿命。Cloud Run 无流量暂停 CPU 时不保证定时清理准点执行。

## 验证

`server/integration/` 使用真实 PostgreSQL 验证幂等、并发、冲突、持久化、单次兑换、过期、改名、撤销及 HTTP/WS 鉴权。`server/internal/identity/` 提供跨语言签名字节测试向量。尚未执行 Android Keystore 真机互操作验证。

# Changelog

## 1.0.0 - 2026-09-08

- 定义并实现设备公钥认证、幂等 bootstrap、短期会话及本人资料接口。
