# 咫尺 / Zisee

See closer, even from afar.

面向 Android 的原生实时视频与远程视觉协作应用。当前代码是 **M0 工程框架 + Phase A 本地身份**，版本 `0.1.0-dev`，尚不是可通话的 M1 产品。

## 当前可用

- Kotlin、Jetpack Compose、单 app module + package 分层。
- 首次姓名初始化、UUIDv7 本地身份、DataStore 持久化、改名保留 ID。
- 首页、最近通话空态、设置、跟随系统深浅色；颜色与间距参考 `design/Foundations.dc.html`。
- 通话显式状态机、独立媒体 Track、能力未知状态、RTC／信令接口。
- 分离 2D 标注与空间标记请求，保留源视频帧时间戳。
- 单元测试、Android lint、GitHub Actions 构建配置。

通话与邀请码入口目前显示未开放提示，不创建虚假邀请、联系人或通话记录。没有接入 WebRTC、CameraX、ARCore、MediaProjection、Go 后端、网络认证或 TURN；没有申请相机、麦克风及网络权限。

## 仓库结构

单仓库，按技术栈分顶层目录，各自持有自己的构建入口：

```text
Zisee/
├── android/          Android 客户端（Gradle 工程根目录）
│   ├── gradlew  gradle/  settings.gradle.kts
│   └── app/
├── server/           Go 后端（认证、PostgreSQL、WebSocket 会话）
├── design/           Claude Design 画板源文件（*.dc.html）
├── docs/             产品、架构、ADR、测试文档
└── AGENTS.md  ARCHITECTURE.md  DOCS.md
```

Gradle wrapper 在 `android/` 下，因此所有 Gradle 命令都从 `android/` 执行。Android CI 只在 `android/**` 变更时触发。

## 开发环境与构建

使用 JDK 17（本机 Android Studio 的较新 JBR 也可验证）、Android SDK Platform 35、Build Tools 35.0.0。最低 Android 8.0 / API 26，compileSdk / targetSdk 暂定 35。

工程固定 Gradle 8.11.1、AGP 8.9.1、Kotlin 2.0.21，以复用当前开发机工具链。AGP 8.9 对应 SDK 35 与 Gradle 8.11.1 的兼容范围见 [Android 官方说明](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。这不是商店发布配置；发布前统一评估目标 SDK、依赖与设备兼容性升级。

1. Android Studio 打开 `android/` 目录（不是仓库根目录）并同步 Gradle。
2. 设置 `JAVA_HOME` 与 `ANDROID_HOME`；也可让 Android Studio 生成被忽略的 `android/local.properties`。
3. 在 `android/` 下执行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS / Linux：

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK：`android/app/build/outputs/apk/debug/app-debug.apk`。Debug 包名 `com.zisee.app.debug`；基础包名 `com.zisee.app` 为当前工程默认值，发布前确认。Release 不配置任何签名密钥。

## 代码入口

| 路径（`android/app/src/main/java/com/zisee/app/` 下） | 职责 |
|---|---|
| `MainActivity.kt`、`ui/` | 生命周期感知状态订阅、Compose 页面与主题 |
| `core/AppContainer.kt` | 手动依赖注入的唯一组装入口，Application 持有 |
| `auth/` | Local Identity 模型、UUIDv7、原子持久化 |
| `call/state/` | 纯函数状态机，拒绝非法转换与旧通话回调 |
| `rtc/`、`signaling/` | M1 适配器接口，当前没有网络实现 |
| `media/` | 独立 Track 与运行时能力模型 |
| `ar/annotation/` | 视频坐标、帧引用、2D／空间请求边界 |

DataStore 是唯一身份数据源。ViewModel 负责加载与保存状态，UI 不进行文件 IO。Application 持有仓库；未来 Camera、PeerConnection 和 renderer 必须由单次通话 owner 管理，不放入 Application 全局单例。

`CallReducer` 只负责状态，不执行资源释放或网络操作。后续 controller 必须串行处理事件，挂断进入 Ending，释放资源后发送 Released；失败路径也必须先完成清理才能重置或开始下一次通话。各接口当前是领域边界，不是最终网络协议。

依赖限制在 AndroidX、Kotlin Coroutines 与测试用 JUnit；AndroidX／Coroutines 使用 Apache-2.0，JUnit 4 使用 EPL-1.0。当前不引入 RTC／AR native 二进制或大型 DI 框架，后续选择具体发行物时再审核许可证、APK 体积、最低系统要求与维护状态。

## 文档

- [文档索引](DOCS.md)、[总体架构](ARCHITECTURE.md)
- [路线图](docs/product/ROADMAP.md)、[账户设计](docs/product/ACCOUNT.md)、[UI 设计](docs/product/DESIGN.md)
- [安全基线](docs/architecture/SECURITY.md)、[验证指南](docs/testing/TESTING.md)

M0 的完整退出还需要真机安装验证与远端 CI 实际运行。项目许可证尚未由维护者确定，本次不自行选择开源授权；暂未创建 `LICENSE`。

下一步是 M1：先落实身份 bootstrap／设备认证及信令协议，再接入单摄 WebRTC 和短期 TURN credential，通过两台真机验证后推进 Show Me。

服务端已实现 Phase B 认证基础，尚未与 Android 联调，参见 [服务端说明](server/README.md)。
