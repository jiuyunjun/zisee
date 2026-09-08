---
title: Zisee M0 验证指南
document_id: TEST-GUIDE-001
version: 1.2.0
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

2026-09-08 首次接通：真机（5G／Wi-Fi）对模拟器建立双向音视频通话，ICE 走 host↔prflx 直连，未经 TURN 中继。手机侧 RTT 5 ms、上行约 1.6 Mbps；模拟器侧收到 640x360 视频流。挂断后资源正常释放。

仍未证明：TURN fallback（本次直连成功，未构造 P2P 失败场景）、Wi-Fi↔蜂窝切换、双机真实广域网组合、长时通话稳定性。这些仍需两台真实设备按路线图验证。

历史版本问题：setup time 约 23 秒，修复与复测方法见下节。

本地 JVM 测试不等于真机媒体测试。M0 不证明 P2P、TURN、相机、编码器、ARCore、Depth、屏幕共享、前后台通话或弱网切换可用。

完成 M1 后按 [路线图](../product/ROADMAP.md) 用两台真实设备测试 Wi-Fi／蜂窝网络组合、TURN fallback 和挂断资源释放。CI 配置存在不代表远端 workflow 已执行。

## 模拟器可用于链路冒烟

早先判断「模拟器产不出 ICE candidate、不能充当第二端」，该结论已被推翻，原因不在模拟器。

WebRTC 的 NetworkMonitor 从未被启动过：代码只调了 `addNetworkObserver`，而「创建 PeerConnection 会启动监视器」在当前 libwebrtc 版本不成立。探测器不跑就报不出接口，ICE 因此拿不到任何 candidate。显式调用 `startMonitoring` 后，模拟器可以正常建立通话。

模拟器现在可用于呼叫、接听、SDP 协商、ICE 直连与媒体流的冒烟验证。但按 AGENTS.md §27，它仍不能替代真机：模拟器处于 QEMU 用户态 NAT（10.0.2.x）之后，其打洞行为不代表真实网络，也无法切换 Wi-Fi 与蜂窝。**TURN fallback、网络切换和弱网表现必须用两台真实设备验证。**

## 首次连接等待 20 秒：根因与修复

`RTC_SETUP_MS` 稳定在 23000 上下。原因是 gathering 不上报 COMPLETE，`localDescription()` 每次都要空等满 20 秒的 gathering 预算才发出 SDP，应答方尤其明显。

现已实现 Trickle ICE：SDP 立即发送，候选通过 media.ice 累计增量交换；answer 创建后当轮发送，连接中缩短轮询间隔。保留 NetworkMonitor 启动及 runtime fallback，不通过删掉 TURN 或只发送早到 host 候选缩短等待。

复测需要先更新服务端（PostgreSQL 迁移 4；Firestore 无手动迁移）和两端 APK。分别记录冷启动／连续第二次呼叫的 RTC_SETUP_MS（capture 启动后至 ICE connected）与 RTC_FIRST_FRAME_MS（同一起点至首个远端解码帧），至少各 5 次；另用人工计时记录接听到首帧，覆盖凭据获取和初始化开销。没有新实测数据前不宣称已达到具体秒数。

回归：晚到 relay 候选、SDP 后到候选、信令断开后重复批次、不回传自己的候选、越权和挂断后拒绝写入、过期清理。两台真机继续验证 TURN fallback。

通话 UI 验收：远端主画面按比例完整显示、本地右上角小窗、底部常驻静音／关闭画面／挂断；连接详情默认收起。检查横屏、窄屏和大字体下挂断可达，关闭画面后本地不显示旧帧，恢复后正常出帧。摄像头开关当前控制发送 Track，采集资源持续保留至挂断。

本次代码验证：Android assembleDebug、testDebugUnitTest、lintDebug 通过；Go 全套测试在本地 PostgreSQL 17 与 Firestore Emulator 下通过（包括真实存储集成测试，非跳过）。尚未部署本次服务端或安装本次客户端复测首帧；不以构建和协议测试替代媒体与 UI 真机验收。

## 视频体验专项原生冒烟

专项设计和测量边界见 [VIDEO_EXPERIENCE.md](../architecture/VIDEO_EXPERIENCE.md)。`RtcSmokeInstrumentation` 是仅存在于 androidTest APK 的原生本地回环测试，不访问账户、生产服务器或 TURN。需要显式授予测试目标的相机／麦克风权限，运行时实际使用这些设备。使用以下入口，不把自定义 runner 当成 JUnit 的 connectedAndroidTest 报告：

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.zisee.app.debug android.permission.CAMERA
adb -s emulator-5554 shell pm grant com.zisee.app.debug android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am instrument -w -r com.zisee.app.debug.test/com.zisee.app.rtc.RtcSmokeInstrumentation
```

成功必须同时包含 `stream=PASS` 和 `INSTRUMENTATION_CODE: -1`；不能以 adb 命令退出码为准。输出包括 SDP、首解码帧、至少 30 帧的耗时和实际发送尺寸；不等于公网通话或真实摄像头的设备验证。测试不清除应用身份数据，但 install 会替换该设备的 Debug APK。

模拟热状态仅在专用模拟器运行，结束必须复原：

```powershell
try {
    adb -s emulator-5554 shell cmd thermalservice override-status 3
    adb -s emulator-5554 shell am instrument -w -r -e expectedQuality ECONOMY com.zisee.app.debug.test/com.zisee.app.rtc.RtcSmokeInstrumentation
} finally {
    adb -s emulator-5554 shell cmd thermalservice reset
}
```

这验证 Android 热状态到降档执行路径，不能证明真实散热、耗电或长期稳定性。

## 真机签名与安装

统一 debug 签名后，此前用个人 debug 证书装过 `com.zisee.app.debug` 的设备必须先卸载再安装，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。小米 HyperOS／MIUI 默认拒绝 ADB 安装，报 `INSTALL_FAILED_USER_RESTRICTED`，需在开发者选项中打开「USB 安装」。

# Changelog

## 1.0.0 - 2026-09-08

- 建立构建、单元测试、lint 和手动验收入口。

## 1.1.0 - 2026-09-08

- 记录真机实测：认证与信令链路已打通，媒体链路尚未打通。
- 记录模拟器产不出 ICE candidate，不能充当通话第二端。
- 记录统一 debug 签名后的安装前提。

## 1.2.0 - 2026-09-08

- 记录首次接通：真机对模拟器 P2P 直连，双向音视频。
- 推翻 1.1.0 的模拟器结论，根因是网络监视器未被启动。
- 记录 setup time 约 23 秒及其成因。
