# server

Zisee 后端服务。**当前为空目录占位，没有任何实现。**

部署平台已确定为 **GCP Cloud Run**，已创建项目 `zisee-app`（显示名称 Zisee）。账单已关联，Cloud Run、Cloud Build 和 Artifact Registry API 已启用，尚未部署服务。状态与后续步骤见 [部署准备](../docs/operations/DEPLOYMENT.md)。

## 定位

按 `ARCHITECTURE.md` §30，第一版是**单个 Go 服务**，不拆微服务。职责：

```text
REST API
WebSocket signaling
TURN credential
Push integration
Auth
```

只承担 Control Plane。媒体不经过这里——1v1 走 P2P，打不通才回落到 TURN 中转（[ADR 0002](../docs/adr/0002-p2p-first.md)、[ADR 0004](../docs/adr/0004-cloudflare-turn.md)）。

## 为什么现在是空的

M0 只交付 Android 工程框架与 Phase A 本地身份，本地身份不需要服务端。信令协议尚未定稿，此时初始化 Go module 只会产生需要跟着协议返工的空壳。

按 `DOCS.md` §3：

> 禁止为了「看起来完整」一次性创建大量空文档。

## 什么时候开始写

M1，且顺序是先协议后实现：

1. 身份 bootstrap 与 device credential（`docs/product/ACCOUNT.md` Phase B）
2. WebSocket 信令协议与消息 schema（`ARCHITECTURE.md` §7）
3. 短期 TURN credential 签发（`ARCHITECTURE.md` §9.3）

开始时需要一并补上：`go.mod`、`cmd/server/`、`.github/workflows/server.yml`，以及 `docs/architecture/SIGNALING.md`。

## 约束

- TURN 账号密码不得下发到客户端硬编码，必须由本服务签发短期 credential。
- 不保存音视频内容、屏幕画面、AR 原始帧与麦克风内容（`ARCHITECTURE.md` §29）。
- 密钥与配置走环境变量，不进仓库（`AGENTS.md` §23）。
