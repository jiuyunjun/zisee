---
title: Zisee Cloud Run 部署准备
document_id: OPS-DEPLOYMENT-001
version: 1.2.0
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
| Cloud Run 服务 | 尚未部署 |
| 部署区域 | 尚未选择，项目创建本身不固定 Cloud Run 区域 |

[项目控制台](https://console.cloud.google.com/home/dashboard?project=zisee-app)。不在仓库记录个人登录账户、账单账户 ID、凭据或 token。所有 CLI 操作显式传入项目，未切换用户全局默认项目。

## 项目准备与后续部署

此前账单关联遇到配额限制，用户完成关联后已解除该阻塞。以下准备操作已完成：

1. 确认 `gcloud billing projects describe zisee-app` 的 `billingEnabled` 为 true。
2. 启用构建部署 API：

```powershell
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com --project=zisee-app
```

3. 通过 `gcloud services list --enabled --project=zisee-app` 核验上述三项 API。

下一步完成 `server/` 的 Go 服务、认证、持久化和测试，再选择区域并部署。Cloud Run 的实例内存和本地文件不能作为身份的持久化存储；信令也需要处理实例间路由及连接重建。

API 准备依据：[Cloud Run 源码部署文档](https://docs.cloud.google.com/run/docs/deploying-source-code)。本次项目创建成功不代表服务部署或公网接口已可用。

# Changelog

## 1.1.0 - 2026-09-08

- 核验账单已关联，启用并核验 Cloud Run、Cloud Build、Artifact Registry API；解除项目准备阻塞。

## 1.0.0 - 2026-09-08

- 确定 Cloud Run 部署目标，创建 `zisee-app` 并记录实际账单阻塞及恢复步骤。

## 1.2.0 - 2026-09-08

服务端认证、PostgreSQL、WebSocket 心跳及容器已实现并本地验证。尚未创建云数据库或部署 Cloud Run；接下来完成 Android 接入、公网反滥用和通话路由，再选择区域及数据库规格部署。
