---
title: Zisee Cloud Run 部署准备
document_id: OPS-DEPLOYMENT-001
version: 1.0.0
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
| 账单 | 未启用；关联现有账单账户时返回 `Cloud billing quota exceeded` |
| Cloud Run / Cloud Build / Artifact Registry API | 尚未启用，受账单前置条件阻塞 |
| Cloud Run 服务 | 尚未部署 |
| 部署区域 | 尚未选择，项目创建本身不固定 Cloud Run 区域 |

[项目控制台](https://console.cloud.google.com/home/dashboard?project=zisee-app)。不在仓库记录个人登录账户、账单账户 ID、凭据或 token。所有 CLI 操作显式传入项目，未切换用户全局默认项目。

## 恢复准备流程

1. 通过 [GCP 账单配额申请](https://support.google.com/code/contact/billing_quota_increase) 处理账单配额，或使用另一个具备配额和权限的账单账户。
2. 为 `zisee-app` 关联账单；不要擅自解绑其他项目的账单。
3. 确认 `gcloud billing projects describe zisee-app` 的 `billingEnabled` 为 true。
4. 启用构建部署 API：

```powershell
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com --project=zisee-app
```

5. 完成 `server/` 的 Go 服务、认证、持久化和测试后，再选择区域并部署。Cloud Run 的实例内存和本地文件不能作为身份的持久化存储；信令也需要处理实例间路由及连接重建。

API 准备依据：[Cloud Run 源码部署文档](https://docs.cloud.google.com/run/docs/deploying-source-code)。本次项目创建成功不代表服务部署或公网接口已可用。

# Changelog

## 1.0.0 - 2026-09-08

- 确定 Cloud Run 部署目标，创建 `zisee-app` 并记录实际账单阻塞及恢复步骤。
