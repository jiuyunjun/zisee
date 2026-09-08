---
title: Zisee M0 验证指南
document_id: TEST-GUIDE-001
version: 1.0.0
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

2026-09-08 本机验证：Debug APK 构建成功，12 项 JVM 测试全部通过；lint 为 0 错误、19 条版本提示（target SDK 与依赖可升级，未抑制）。ADB 未发现已连接设备，尚未执行安装与界面手动验收；远端 CI 尚未运行。

本地 JVM 测试不等于真机媒体测试。M0 不证明 P2P、TURN、相机、编码器、ARCore、Depth、屏幕共享、前后台通话或弱网切换可用。

完成 M1 后按 [路线图](../product/ROADMAP.md) 用两台真实设备测试 Wi-Fi／蜂窝网络组合、TURN fallback 和挂断资源释放。CI 配置存在不代表远端 workflow 已执行。

# Changelog

## 1.0.0 - 2026-09-08

- 建立构建、单元测试、lint 和手动验收入口。
