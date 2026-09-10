# 通话实测修复持续交接

更新：2026-09-11。用户要求：随时维护本文，便于下一代理接手。上轮 UI 提交 8aca17d，截图验收 b32f4e9 不能替代本次真机反馈。

## 需求与进度

1. 已修复可复现的码率上限恢复闭环，提交 d042614；仍待双机 Wi-Fi→5G→Wi-Fi 实测，详见下文。移动网络按带宽证据升档，不硬编码限制640，也不盲目强推高清。
2. 已提交：右上角仅单摄时前后切换，关摄时禁用，双摄/AR/共享场景隐藏。用户确认移除应用内最小化按钮，最小化靠返回手势/Home 进入 PiP，不需在「更多」补入口。
3. 已提交：真实触摸四路交换测试，每个 renderer 都测试为小窗，详见下文。
4. 已提交：PiP 使用当前观看源旋转后的显示宽高；缺帧按窗口方向回退。用户稍后自行真机检查系统浮窗。
5. 已提交：拖动位置窗口归一化保存，底栏显隐不清空位置与停靠状态；仪器测试 thumbnailSwaps 已 PASS。
6. 已答复：代码有 AR 接入，连接真机已装 Google AR 服务；实际跟踪/放置仍须现场验证。
7. 已提交：指导方来源只保留共享屏幕；被指导方授权/共享阶段禁用后台 PiP，不影响停止共享入口与恢复相机逻辑。
8. 已敲定并落地：常规视频用底栏静音/摄像头/给你看/更多/挂断，单摄顶栏切前后；双摄由小窗点击选视角、拖动记位置；共享屏幕优先且退出始终明确；AR为独立协作模式，需要设备能力与用户主动开启。
9. 进行中：本文持续记录根因、修改、验证、commit 和剩余项。

## 约束与验证

遵循根 AGENTS.md；UTF-8、限定修改、每个独立修复单独 commit。不得扩大媒体架构重构，不改真实 AR/相机能力判断。构建/单测/lint 与合成 UI 预览用于代码验证；真实网络恢复与 AR 服务安装需设备证据，不能冒充已实测。

## 初步线索

- ActiveCall 的 LaunchedEffect(maxWidth,maxHeight,main,positions) 清空 moved/parked；positions 包含底栏高度，符合用户位置重置现象。
- 圆角经 VideoRenderer → TextureViewRenderer，需要检查尺寸变化后的 outline 更新。
- PiP policy 本身按 width/height 算比例，需检查调用方是否传入旋转前尺寸。
- 2026-09-11 按用户要求将需求 2/3/4/5/7 及升档健康条件合并为一个提交（含 CallThumbnailSmoke 专项测试）。升档健康条件现接受 qualityLimitation="bandwidth"：发送端被自身上限卡住时 WebRTC 报 bandwidth，原先只认 none 会永不升档；用户确认为有效改动。
- 仪器测试：`adb -s emulator-5554 shell am instrument -w -r -e thumbnailSwaps true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation`，2026-09-11 PASS。
- 2026-09-11 第二轮实测（A 5G→Wi-Fi）：B 摄像头反复重置、画质爬升慢。B=65975249（小米 aurora，非 c073b16f），日志 00:44–00:48。
  - 根因一（已修待复测）：B 每次档位变化（C1/C2/C3）后约 100ms 系统记录相机 close→open，8 次一一对应，纯码率变化不重开。applyCameraPlan 每次换档调 changeCaptureFormat，小米 HAL 会整机重开。改为仅当现有采集格式不能覆盖目标宽高/帧率时才 changeCaptureFormat，降档靠 adaptOutputFormat 缩放。
  - 根因二（推断）：重开造成断帧+关键帧突发→sendDelay>60ms→QUEUE 降档（00:47:31 升 C2，00:47:33 在 bwe=4735 时 QUEUE 降回），再叠加 5s 冷却+8s 升档等待，形成慢爬循环。修复一后应缓解，须复测确认。
  - 未解：同局域网却先走 TURN（relay/relay，RTC_RELAY_PINNED directSucceeded=0），00:46:56 才 srflx/srflx，全程无 host/host。需 A 端日志判断是否 host 候选未产生或路由器 AP 隔离。
- 剩余：双机切网实测（需求 1）、系统浮窗横竖屏（需求 4，用户自测）、AR 现场跟踪/放置（需求 6）。
- 改动内容：MainActivity 用选中远端 feed 的 VideoGeometry.displayWidth/displayHeight 更新 PiP，共享授权/启动/进行中禁用 auto-enter；CallVideoLayout 远端共享仅返回 PeerScreen，compact 也优先共享；ActiveCall 单摄右上角切前后摄，拖动位置保存为窗口归一坐标，取消随 controls 清空高度；TextureViewRenderer 尺寸变化重新 invalidateOutline。
- 圆角真实根因：VideoRenderer 的 AndroidView.update 在 BoxWithConstraints 尺寸子组合中保留旧 cornerPx；交换后主画面仍圆角、旧主画面小窗无圆角。改为外部 SideEffect 更新保留 renderer，尺寸变化还会 invalidateOutline。四路真实触摸交换测试已 PASS，且审过 rounded-fixed.png；继续补拖动后自动隐藏位置不变测试。
- 连接真机 c073b16f（小米）只读检查：已安装 com.google.ar.core。AR 项目已集成，尚未替用户启动 AR/安装更新。日志存在 RTC_ADAPTATION_PLAN 的 VIEW/QUEUE/ENCODER 降档和恢复；未拿到双方同步记录，不能断言用户整个切网现象只有一个原因。
- 网络纯测试用“BWE随应用发送上限增长”的闭环，原来的 C0/C1 上限不足跨越升档门槛。增加健康反馈后12秒上限探测、30秒冷却，不强制码率/分辨率，遇到丢包/CPU/热/缺失统计关闭探测。首轮失败后已修正将音频/重传/余量纳入 probeCeiling；第二轮250项单测、assembleDebug/AndroidTest、lint 全通过。仍须双方真机网络复测，不宣称已解决实网全部问题。
- 构建命令日志：android/app/build/call-fixes-build.log。运行环境 JAVA_HOME=C:/Program Files/Android/Android Studio/jbr。测试只在 emulator-5554 安装，用户真机不覆盖安装。
