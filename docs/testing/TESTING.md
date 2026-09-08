---
title: Zisee M0 验证指南
document_id: TEST-GUIDE-001
version: 1.1.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: "0.1.0-dev"
owners:
  - android
  - core
---

# 验证指南

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
Lint 报告：`app/build/reports/lint-results-debug.html`。
GitHub Actions 使用相同任务；上传报告，不上传用户数据或签名密钥。

## 自动测试覆盖

- UUIDv7 version、variant、时间戳和随机性抽样；Unicode 姓名边界与控制字符拒绝。
- 并发身份创建、改名保持 ID／创建时间、重新打开磁盘数据、缺失字段拒绝覆盖。
- 通话连接、重连、拒接、清理、失败、非法转换和迟到回调。
- 标注坐标范围、NaN／Infinity 拒绝、视频来源与时间戳约束。

## 安装后手动验收

1. 首次启动仅询问姓名，不请求相机、麦克风或通知权限。
2. 姓名保存后进入首页；杀进程再启动，仍是同一身份。
3. 设置页改名，检查 ID 不变；空白与超长输入显示错误。
4. 窄屏、横屏、字体放大、键盘弹出时可滚动访问输入框和按钮。
5. 系统返回键回到首页；旋转保留当前页面和正在编辑的姓名。
6. 深浅色、TalkBack 检查；触控区域至少 48dp。
7. 通话／邀请码入口说明暂未开放，不显示假连接或成功分享。

## 验证边界

2026-09-08 本机验证：Debug APK 构建成功，20 项 JVM 测试全部通过；lint 为 0 错误、19 条版本提示（target SDK 与依赖可升级，未抑制）。远端 CI 尚未确认执行。

2026-09-08 真机与模拟器验证：真机（小米 25128PNA1C，Android 16，arm64）与模拟器（sdk_gphone16k_x86_64，Android 16）各安装一次，由真机发起呼叫。

已实机证明可用：身份 bootstrap、签名挑战、令牌兑换、认证 WebSocket、邀请、呼叫接听与 TURN credential 下发，全部对已部署的 Cloud Run 后端成功。通话状态机进入 accepted 分支必须先依次通过上述全部环节，因此真机硬件 Keystore 与 Android→Go 联调不再依赖 JVM 软件密钥测试。

尚未证明：SDP 交换完成、ICE 连通、任何媒体帧、P2P 与 TURN 判别。两端 ICE 均未进入 CHECKING。

本地 JVM 测试不等于真机媒体测试。M0 不证明 P2P、TURN、相机、编码器、ARCore、Depth、屏幕共享、前后台通话或弱网切换可用。

完成 M1 后按 [路线图](../product/ROADMAP.md) 用两台真实设备测试 Wi-Fi／蜂窝网络组合、TURN fallback 和挂断资源释放。CI 配置存在不代表远端 workflow 已执行。

## 模拟器不能充当通话的第二端

2026-09-08 实测：Android 模拟器产不出任何 ICE candidate，不能用于验证通话链路。

模拟器操作系统层面存在 eth0 10.0.2.15 与 wlan0 10.0.2.16，但 WebRTC 的 NetworkMonitor 未在 2 秒内报告任何接口（日志 `RTC_ICE_STATE networks_timeout`），gathering 也未在 20 秒内完成。主叫真机因此一直等不到 answer。

模拟器仍可用于信令、通话状态机、权限与界面的冒烟，但按 AGENTS.md §27，其结果不得作为 M1 的验证记录。媒体相关的退出条件必须使用两台真实设备。

## 真机安装

统一 debug 签名后，此前用个人 debug 证书装过 `com.zisee.app.debug` 的设备必须先卸载再安装，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。小米 HyperOS／MIUI 默认拒绝 ADB 安装，报 `INSTALL_FAILED_USER_RESTRICTED`，需在开发者选项中打开「USB 安装」。

# Changelog

## 1.0.0 - 2026-09-08

- 建立构建、单元测试、lint 和手动验收入口。

## 1.1.0 - 2026-09-08

- 记录真机实测：认证与信令链路已打通，媒体链路尚未打通。
- 记录模拟器产不出 ICE candidate，不能充当通话第二端。
- 记录统一 debug 签名后的安装前提。
