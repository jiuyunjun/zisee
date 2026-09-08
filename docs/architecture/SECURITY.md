---
title: Zisee M0 安全基线
document_id: ARCH-SECURITY-001
version: 1.0.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "0.1.0-dev"
owners:
  - android
  - core
---

# 安全基线

当前仅实现 [账户设计](../product/ACCOUNT.md) Phase A。本地 `identityId` 是带 `zid_` 前缀的 UUIDv7 公开标识，绝不作为认证凭据。姓名和 ID 保存在 app 私有 DataStore，不含设备硬件 ID，不创建 token 或长期密钥。

身份创建与改名通过 DataStore 串行事务完成；重复创建不会覆盖原身份。读写失败通过 UI 明示，损坏或缺少字段的已有记录不会自动重置。卸载／清除数据会失去未绑定身份。

Manifest 禁用明文流量、云备份及设备迁移数据，当前不声明摄像头、录音、网络等权限。媒体尚未接入，不应声称已经实现加密通话或设备能力检测。后续权限必须由用户触发具体功能后申请。

日志使用固定事件枚举，不输出姓名、身份字段、异常消息、SDP、ICE candidate、凭据或媒体。未来遥测也必须先定义允许记录的字段。

## M1 接入约束

- 服务端认证必须独立于公开 identityId。设备 credential 使用 Keystore 支持的安全存储。
- HTTPS / WSS，短期 access token；TURN credential 从已认证后端动态获取，长期 TURN secret 不进入 APK。
- SDP 与 ICE 消息必须检查 callId、认证会话归属、大小限制、重放和乱序。
- PeerConnection 默认允许 direct 与 relay candidate，由 ICE 选择；不默认 relay-only。
- 签名密钥、token、生产环境配置和个人数据不得进入 Git 或 CI artifact。

# Changelog

## 1.0.0 - 2026-09-08

- 记录 M0 已实现边界与 M1 认证、权限、日志接入约束。
