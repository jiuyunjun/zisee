---
title: Android 服务端认证接入
document_id: DEV-ANDROID-BACKEND-001
version: 1.0.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "0.1.0-dev"
owners:
  - android
  - backend
---

# Android 服务端认证接入

当前 Android 已实现 [身份协议](../protocols/IDENTITY_PROTOCOL.md) 的 bootstrap、签名挑战、令牌兑换和认证 WebSocket 心跳。它是开发联调入口，不代表已有邀请、通话、账户绑定或联系人同步。

## 构建与入口

从 `android/` 构建，使用 Gradle 属性指定已部署、证书可信的 HTTPS 服务 origin：

```powershell
.\gradlew.bat :app:assembleDebug '-Pzisee.backendUrl=https://your-service.example'
```

该示例地址不能实际用于联调。配置只允许 HTTPS origin，不允许用户名、密码、路径、查询或 fragment；不包含 token。未设置属性的 APK 显示“尚未配置服务地址”，不发起网络请求。GCP 项目 `zisee-app` 当前尚未部署后端，因此没有默认可用 URL。

设置页的 **开发连接 → 验证连接** 发起认证；断开按钮取消连接。入口仅出现在 Debug。Release 尚不自动连接，后续随通话功能接入。没有运行时任意服务器输入框，防止身份和签名误发到未知地址。

Cloud Run 若仍受 IAM 保护，移动端 Bearer 是 Zisee 会话令牌，不是 GCP IAM ID token，不能直接替代平台鉴权。部署时需要明确应用层认证和平台入口策略；本次不更改云端 IAM。

## 密钥与身份

- 保留原 LocalIdentity ID；服务端冲突不自动覆盖、合并或重建身份。
- AndroidKeyStore 生成 EC P-256 密钥，使用 SHA256withECDSA 签名，公钥 DER 编码与 Go 协议一致。
- key alias 按服务 origin 和 identityId 的 SHA-256 隔离，不混用不同服务的设备密钥。
- `deviceId = zdev_ + base64url(SHA-256(publicKeyDER))`，来自安装时随机生成的密钥，不使用硬件标识；同一密钥恢复同一设备 ID，避免两份随机数据持久化的中间状态。
- 公钥标记在任何网络请求前写入 DataStore；重试复用密钥和标记。已初始化的密钥丢失／替换时停止认证，不静默重建。
- 私钥不导出，token 仅在连接协程内存中保存，不写 DataStore、日志、SavedState 或 URL。硬件／StrongBox 支持未作假设，也未声称已经验证。

当前设置页改名仍是本地操作。幂等 bootstrap 不覆盖服务端既有姓名，服务端改名同步、离线冲突解决和账户恢复将在后续实现。开发连接成功不会把“仅此设备”误显示为已绑定账户。

## 生命周期和错误

用户触发连接后，仅 Activity 前台保持连接；onStop 取消 HTTP/WebSocket，尽力在 3 秒内撤销令牌。撤销失败记录固定事件，服务端 15 分钟过期仍有效。回到前台时重新认证；显式断开或终止错误后不自动重试。

消息协商 `zisee.v1`，等待 `session.ready` 后才报告连接成功。每 20 秒发送业务 ping，验证对应 pong；握手 15 秒、pong 10 秒超时，令牌到期前主动更新。回调不能复活已取消的旧连接。

网络故障最多连续尝试 5 次，退避 1/2/4/8 秒并加随机抖动；HTTP 429 至少等 120 秒。冲突、密钥不可用、协议错误或认证拒绝进入明确终止状态，由用户重试。进入后台会取消等待。

## 依赖与验证

新增 OkHttp 4.12.0（Apache-2.0）统一处理 HTTP/WebSocket 和取消，复用连接池，不安装日志拦截器、不接受重定向。OkHttp 支持 API 21+，低于项目 minSdk 26，无 native 库，仅增加网络权限；MockWebServer、JSON JVM 实现、coroutines-test 仅用于测试。依赖升级提示保留在 lint 中，后续统一更新工具链。

JVM 测试覆盖与 Go 相同的签名字节向量、ECDSA 签名、HTTP 消息、冲突、不跟随重定向、响应大小限制、认证握手、心跳、取消和前后台迟到回调。MockWebServer 使用明确的回环 IP；HTTP 回环例外仅供 JVM 测试，APK 配置与 Manifest 仍禁止明文网络。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

2026-09-08 已在真机（小米 25128PNA1C，Android 16）对已部署的 Cloud Run 后端完成 Android Keystore 实机验证与 Android→Go 联调：bootstrap、签名挑战、令牌兑换、认证 WebSocket、邀请与呼叫接听全部成功，TURN credential 正常下发。硬件 Keystore 生成的 EC P-256 签名可被 Go 端验通，不再依赖 JVM 软件密钥测试。

尚未完成远端 CI 验证。媒体链路（SDP 交换、ICE 连通、P2P 与 TURN 判别）仍未打通，见 [验证指南](../testing/TESTING.md)。

# Changelog

## 1.0.0 - 2026-09-08

- 建立 Android 设备认证、前台会话和开发连接入口。

验证记录：2026-09-08，默认构建与指定 HTTPS origin 构建成功，20 项 JVM 测试通过，lint 0 错误、19 条版本提示。同日真机安装并完成认证联调。
