---
title: Zisee Cloud Run 部署准备
document_id: OPS-DEPLOYMENT-001
version: 1.4.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "0.1.0-dev"
owners:
  - backend
---

# 部署目标

服务端使用 GCP Cloud Run，承载 Go 控制面服务。媒体仍按现有架构走 WebRTC P2P，TURN 使用 Cloudflare fallback。

## 当前状态（2026-09-08 核验）

| 项目 | 状态 |
|---|---|
| GCP 显示名称 | Zisee |
| Project ID | `zisee-app` |
| 项目生命周期 | ACTIVE |
| 账单 | 已关联；CLI 核验 `billingEnabled: true` |
| Cloud Run / Cloud Build / Artifact Registry API | 三项均已启用并通过 CLI 列表核验 |
| Firestore / Secret Manager API | 已启用 |
| Cloud Run 服务 | `zisee-api` 已部署，修订版 `zisee-api-1575ddd` 承载 100% 流量 |
| 服务 URL | `https://zisee-api-1042746204547.asia-northeast1.run.app` |
| 部署区域 | `asia-northeast1`（东京） |
| 数据库 | Firestore Native，`projects/zisee-app/databases/(default)`，asia-northeast1 |
| TURN | Cloudflare，密钥存于 Secret Manager，启动日志确认 `turn_enabled` |

[项目控制台](https://console.cloud.google.com/home/dashboard?project=zisee-app)。不在仓库记录个人登录账户、账单账户 ID、凭据或 token。所有 CLI 操作显式传入项目，未切换用户全局默认项目。

## 项目准备与后续部署

此前账单关联遇到配额限制，用户完成关联后已解除该阻塞。以下准备操作已完成：

1. 确认 `gcloud billing projects describe zisee-app` 的 `billingEnabled` 为 true。
2. 启用构建部署 API：

```powershell
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com --project=zisee-app
```

3. 通过 `gcloud services list --enabled --project=zisee-app` 核验上述三项 API。

## 首次部署（裸奔版）

```powershell
gcloud run deploy zisee-api --source=server --region=asia-northeast1 --project=zisee-app `
  --allow-unauthenticated --set-env-vars=FIRESTORE_PROJECT_ID=zisee-app `
  --set-secrets=CLOUDFLARE_TURN_TOKEN_ID=cloudflare-turn-token-id:latest,CLOUDFLARE_TURN_API_TOKEN=cloudflare-turn-api-token:latest `
  --timeout=3600 --max-instances=2 --min-instances=0 --memory=512Mi
```

运行时服务账户 `1042746204547-compute@developer.gserviceaccount.com` 需要 `roles/secretmanager.secretAccessor`（在两个密钥上）与项目级 `roles/datastore.user`；首次部署因缺前者失败，补授后成功。

来电推送：加 `--set-env-vars=...,FCM_PROJECT_ID=zisee-app`（Firebase 已在 `zisee-app` 上启用，与 Firestore 同项目），并给运行时服务账户 `1042746204547-compute@developer.gserviceaccount.com` 授 `roles/firebasecloudmessaging.admin`、启用 `fcm.googleapis.com`。凭据走 ADC，无需密钥文件。不设 `FCM_PROJECT_ID` 时推送关闭（日志 `push_not_configured`）。Android 端 `android/app/google-services.json` 也指向 `zisee-app`。

复合索引由 `server/firestore.indexes.json` 声明，创建命令：

```powershell
gcloud firestore indexes composite create --collection-group=calls `
  --field-config=field-path=participants,array-config=contains `
  --field-config=field-path=expiresAt,order=ascending --project=zisee-app
```

## 本次升级：Trickle ICE 与换网恢复

部署源码提交为 `1575ddd`，包含 Trickle ICE、`media.restart` 和按协商代次隔离的 SDP / ICE 交换。沿用现有服务配置部署：

```powershell
gcloud run deploy zisee-api --source=server --region=asia-northeast1 --project=zisee-app --revision-suffix=1575ddd --quiet
```

- Cloud Build：`f71eaed8-6688-45b9-81a0-102fc1397885`，构建成功。
- 修订版：`zisee-api-1575ddd`，Ready / ConfigurationsReady / RoutesReady 均为 True，承接 100% 流量。
- 镜像摘要：`sha256:05f62c81f7921f4a7c49dcb8d044624d1cc52f9fa681be48197b393d97536b2c`。
- 就绪时间：2026-09-08 14:20:58 UTC。
- 线上使用 Firestore，无需执行 PostgreSQL 的 `005_ice_restart.sql` 迁移。PostgreSQL 部署仍需按协议文档执行迁移。
- 双方 Android 客户端均需更新至包含本次换网恢复实现的版本；仅升级服务器不会让旧客户端自动获得此能力。

## 已核验

`GET /livez` 与 `GET /readyz` 均返回 200，`readyz` 通过说明 Cloud Run 能连上 Firestore；`GET /v1/ice` 未带令牌返回 401；启动日志出现 `turn_enabled`。Android 以 `-Pzisee.backendUrl=<服务 URL>` 构建、单测与 lint 通过。

本次升级后重新核验上述三个 HTTP 探针，并确认新修订版启动日志出现 `turn_enabled`。源码提交的本地验证包括 47 项 JVM 测试、Android 构建与 lint、Go 测试（含 PostgreSQL / Firestore 模拟器），以及 Android 模拟器 ICE restart 冒烟测试；这些不能替代线上真机验收。

**尚未验证**：本次升级尚未用双真机复测线上完整通话、TURN 中转及 Wi-Fi / 蜂窝网络切换；首帧耗时和换网恢复耗时仍需实测。

## 已知问题

Cloud Run 的 Google 前端会自行应答 `/healthz`，请求到不了容器（返回 Google 的 HTML 404 而非本服务的 JSON）。因此新增 `/livez` 作为实际可达的存活探针，`/healthz` 保留给本地与容器冒烟测试。

## 裸奔部署的暴露面

当前 `--allow-unauthenticated`，注册与挑战接口对公网开放。已有的防护只是进程内速率限制与每设备挑战数量上限，没有 WAF、没有跨实例配额、没有自定义域名。这是刻意的临时状态，公开分发前必须补齐。

API 准备依据：[Cloud Run 源码部署文档](https://docs.cloud.google.com/run/docs/deploying-source-code)。

# Changelog

## 1.4.0 - 2026-09-08

- 部署 `1575ddd` 的 Trickle ICE 与版本化 ICE restart 服务端实现，记录构建、修订版、流量及探针验证结果。
- 明确 Firestore 无需 SQL 迁移，以及双方客户端升级与真机验收要求。

## 1.3.0 - 2026-09-08

- Firestore 取代 Postgres 作为云端存储，数据库建于 zisee-app／asia-northeast1。
- `zisee-api` 首次部署到 Cloud Run 东京区域，TURN 密钥经 Secret Manager 注入。
- 记录服务账户所需角色、复合索引创建命令与 `/healthz` 被前端拦截的问题。

## 1.1.0 - 2026-09-08

- 核验账单已关联，启用并核验 Cloud Run、Cloud Build、Artifact Registry API；解除项目准备阻塞。

## 1.0.0 - 2026-09-08

- 确定 Cloud Run 部署目标，创建 `zisee-app` 并记录实际账单阻塞及恢复步骤。

## 1.2.0 - 2026-09-08

服务端认证、PostgreSQL、WebSocket 心跳及容器已实现并本地验证。尚未创建云数据库或部署 Cloud Run；接下来完成 Android 接入、公网反滥用和通话路由，再选择区域及数据库规格部署。
