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
- 2026-09-11 AR 实测：A=c073b16f 开启现场，底部标记工具条出现，但轻点大画面只切换菜单显隐，无法放置。
  - 根因（已修待真机复测）：VideoRenderer 的 AR 点击层 pointerInput 以 displayed 为 key，而每个渲染帧都是新的 DisplayedArFrame，手势检测每秒被重启约 30 次，点击无法完成并落到外层 controls 切换；displayed 某帧为空时点击层还会整体消失。改为标记模式下常驻点击层，仅按视口尺寸重建，点击时用 rememberUpdatedState 读最新帧，无帧则吞掉点击。
  - A 日志无 AR 报错；AR 放置本身无应用日志，复测若仍失败需加调试日志。
- 2026-09-11 局域网直连分析（A/B 日志）：通话开始 00:44:16 为 host/host wifi，排除 AP 隔离；A 在 4G 上 ICE 重启（00:45:39，新一代无 Wi-Fi 候选），00:46:53 回 Wi-Fi 判定路由恢复不重启，靠持续收集补发；此后 relay/relay→srflx/srflx，从未 host/host。srflx 被选中说明新 Wi-Fi 候选已到 B、两机互通。RTC_RELAY_PINNED 仅诊断不锁中继。现有日志只转发 native LS_ERROR，无法区分 host/host 对未形成还是检查失败。嫌疑：每代候选上限 32（CellularStandby + 双栈 + 多 TURN URL 易超），超限原先静默丢弃。
  - 已加诊断（未装机）：RTC_CANDIDATE_OVERFLOW（首次超 32 时的候选类型）；RTC_ICE_PAIRS（非 host/host 路径稳定 5s 后一次：各 本地/远端类型:状态 的候选对数、两端候选按 网络/类型 计数、已信令数、是否溢出，不含 IP）。未加：90s 补发超时触发的重启日志（需改 MediaNegotiator 构造）。
  - 用户复测旧版（A 安装于 09-10 21:21，早于 5196411/405c136），AR 点击仍只切换菜单、Wi-Fi 重连仍 prflx，均为旧代码表现。用户暂不同意装机，装新版前须再次确认。
- 2026-09-11 01:13 第三轮（A=c073b16f 切网方，仍为旧版 09-10 21:21 安装）：
  - 01:13:51 host/host wifi；01:15:13 Wi-Fi→4G，已有 cellular/srflx 备用路径，仍在 01:15:18 ROUTE 重启，视频中断 7114ms（HANDOVER_VIDEO ms=7114），为本轮最大体验问题，待查备用路径为何未接管。
  - 01:15:48 回 Wi-Fi：17ms 内选中 prflx/host wifi，1s 后 prflx/prflx wifi，无中继。推断 prflx 是 A 新 Wi-Fi 端口尚未作为本地候选收集、B 回包源地址未信令而被标记，实际多半仍是局域网直连；日志不含地址无法证实。码率 5.5s 从 C1 爬到 C3（bwe 4.5M），本通话爬升正常。
  - 01:21 通话 A 相机 7 次 close/open（01:21:09–01:21:32），与 RTC_ADAPTATION_PLAN 时间不对应，非换档所致，原因未知（可能是用户操作 AR/切换，旧版无日志）。
- 2026-09-11 部署方式更正：用户用 Android Studio Apply Changes 部署，新代码在 code_cache/.overlay（01:20 更新，含 f0d953b），base.apk 与 lastUpdateTime 仍为 09-10 21:21。判断装机版本须查 overlay（run-as ... grep code_cache），不能只看 lastUpdateTime。
- 2026-09-11 01:21 通话（最新代码）诊断结论：
  - 回 Wi-Fi 后 RTC_ICE_PAIRS：prflx/host:succeeded、prflx/prflx:succeeded、host/host:in-progress、signaled=18 overflow=false。本端非 relay + 对端 host（B 局域网地址）= 局域网直连；prflx 仅因 A 新 Wi-Fi 实际源地址不在本地候选中而被标记，属标签问题，不影响画质。32 候选上限嫌疑排除。
  - 4G 期间：srflx/relay 成功，relay/host:failed=8 为 TURN 拒绝私网地址，属正常。
  - 01:21 相机 7 次开关对应两次 RTC_SHOW_ME_FALLBACK requested（用户开关给你看），非缺陷。
  - 仍存在：Wi-Fi→4G 必须 ICE 重启，视频中断 2.9s（01:21）至 7.1s（01:15），为下一步重点。
- 2026-09-11 01:29 AR 复测（最新代码，进程自 01:20 起）：点击仍只切换菜单。代码上主画面无其他全屏可点击层，onArTap 已传到 VideoRenderer，点击层只覆盖适配后的画面区域；推断点在黑边上落到外层菜单开关，未证实。已加 AR_TAP 诊断：点击层出现时记录 layer/box 像素尺寸，每次点击记录 frame=none / outside / placed。若复测时点画面也没有 AR_TAP 点击记录，说明点击层没收到事件。
- 2026-09-11 Wi-Fi→4G 7.1s 分析（01:15 通话）：
  - 该通话开头只收集到 host，无 srflx/relay（01:14:00 RTC_BACKUP_PATH missing on=wifi origins=3），切网时无备用路径；01:15:14 起对端音频全为零帧，媒体完全中断。STUN/TURN 为何没结果未查明（01:16:02 有 Binding request timed out）。
  - 恢复评估在信令循环里、排在重建信令连接之后；切网 200ms 后旧信令被主动关闭，重启要等 4G 上 WebSocket 重连并收到 session.ready。naturalRecoveryMs 仅 2s，实际 4.5s 才决定重启，推断耗在信令重连。已加 RTC_SIGNALING_READY ms=… route=…（仅重连时记录）以证实。
  - 可能的改进（未做，涉及架构，需用户确认）：切网后若新网络无备用路径则尽快重启；或在 4G 待机网络上预热信令连接，避免重启等待重连。
- 2026-09-11 01:36 AR 复测（A=c073b16f，最新代码已确认在 overlay 中）：用户在 A 上看到钉住/箭头/圈工具条，点击仍只切换菜单；A 日志 AR_TAP 为 0 条（连 shown 都没有），即主画面点击层从未组合。与代码矛盾：工具条与点击层同为 localArMarking 条件，只有主画面 VideoTile 走占位分支（live=false 或 feed=null）时才会出现“有工具条无点击层”。
  - 模拟器复现（CallArTapSmoke，-e arTap true，ar-notice 场景）：PASS，AR_TAP shown + frame=none，点击到达点击层且不切菜单。说明 UI 逻辑正确，真机状态与预览场景不同。
  - 下一步：用户在 A 上保持 AR+工具条画面时，用 uiautomator dump 查点击层（contentDescription “AR 现场，可轻点放置所选标记”）是否存在及尺寸。
- 剩余：双机切网实测（需求 1）、系统浮窗横竖屏（需求 4，用户自测）、AR 现场跟踪/放置（需求 6）。
- 改动内容：MainActivity 用选中远端 feed 的 VideoGeometry.displayWidth/displayHeight 更新 PiP，共享授权/启动/进行中禁用 auto-enter；CallVideoLayout 远端共享仅返回 PeerScreen，compact 也优先共享；ActiveCall 单摄右上角切前后摄，拖动位置保存为窗口归一坐标，取消随 controls 清空高度；TextureViewRenderer 尺寸变化重新 invalidateOutline。
- 圆角真实根因：VideoRenderer 的 AndroidView.update 在 BoxWithConstraints 尺寸子组合中保留旧 cornerPx；交换后主画面仍圆角、旧主画面小窗无圆角。改为外部 SideEffect 更新保留 renderer，尺寸变化还会 invalidateOutline。四路真实触摸交换测试已 PASS，且审过 rounded-fixed.png；继续补拖动后自动隐藏位置不变测试。
- 连接真机 c073b16f（小米）只读检查：已安装 com.google.ar.core。AR 项目已集成，尚未替用户启动 AR/安装更新。日志存在 RTC_ADAPTATION_PLAN 的 VIEW/QUEUE/ENCODER 降档和恢复；未拿到双方同步记录，不能断言用户整个切网现象只有一个原因。
- 网络纯测试用“BWE随应用发送上限增长”的闭环，原来的 C0/C1 上限不足跨越升档门槛。增加健康反馈后12秒上限探测、30秒冷却，不强制码率/分辨率，遇到丢包/CPU/热/缺失统计关闭探测。首轮失败后已修正将音频/重传/余量纳入 probeCeiling；第二轮250项单测、assembleDebug/AndroidTest、lint 全通过。仍须双方真机网络复测，不宣称已解决实网全部问题。
- 构建命令日志：android/app/build/call-fixes-build.log。运行环境 JAVA_HOME=C:/Program Files/Android/Android Studio/jbr。测试只在 emulator-5554 安装，用户真机不覆盖安装。
