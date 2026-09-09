---
title: 视频方向与双方横竖屏专项设计
document_id: ARCH-VIDEO-ORIENTATION-001
version: 1.0.0
status: Active
created: 2026-09-09
updated: 2026-09-09
applies_to: "0.1.0-dev"
owners:
  - android
  - rtc
---

# 视频方向与双方横竖屏

## 目标与范围

A、B 可以各自横持、竖持或倒置手机；任何一方转动都不要求另一方配合。Face Call、单方 Show Me、双方 Show Me 和单后摄降级遵循同一组规则：画面正立、比例真实、现场内容完整、前后摄镜像明确，转屏不重建通话或重新协商 SDP。

本次实现摄像头视频的方向防抖、按每路帧尺寸布局、主辅视角选择及画质请求一致性。屏幕共享和 AR 标注尚未接入当前通话，本文定义其后续接入约束，不宣称实现了这些功能。

## 四种不同的方向

| 信息 | 所属端 | 用途 |
|---|---|---|
| Camera sensor orientation | 发送端，每个摄像头各自拥有 | 将传感器原始缓冲区解释为图像 |
| 手机物理方向 | 发送端 OrientationEventListener | 决定发送视频相对重力的正立方向 |
| VideoFrame.rotation 与 buffer 尺寸 | 每路视频逐帧 | 接收端旋转及计算实际宽高比的依据 |
| 当前可用窗口宽高 | 观看端 | 主画面容器、小窗排列、控件安全区 |

禁止用 B 的窗口方向覆盖 A 的帧方向；禁止把 A 前摄的尺寸推断为 A 后摄的尺寸；禁止根据 1920×1080 编码缓冲区直接判定画面横屏。旋转 90°/270° 时显示宽高应交换。

Android Surface rotation 和方向传感器角度的正方向不同。映射及 CameraX targetRotation 参考 [Android 官方旋转指南](https://developer.android.com/media/camera/camerax/orientation-rotation)。自然横屏平板上的 ROTATION_0 不代表竖屏，UI 始终依据实际窗口尺寸布局。

## A 与 B 的组合矩阵

下表描述常见竖屏自然方向手机；以 A 为内容发送端、B 为观看端，反向通话完全对称。最终比例来自 A 的具体视频帧，而不是表中的手机姿态推测。

| A 视频 | B 窗口 | B 主画面 | B 小窗 |
|---|---|---|---|
| 竖向 | 竖向 | 正立，等比完整显示；比例不同时保留边带 | 靠右纵向排列 |
| 横向 | 竖向 | 正立，等比完整显示；通常上下留边 | 靠右纵向排列，每路各自保持横/竖比例 |
| 竖向 | 横向 | 正立，等比完整显示；通常左右留边 | 控件上方横向排列，每路各自保持比例 |
| 横向 | 横向 | 正立，等比完整显示；同样不拉伸 | 控件上方横向排列 |

180°、270° 与相应宽高类别使用相同布局，但帧旋转仍分别处理。对方转屏只更新该路显示几何，不重置自己的手动主视角；自己的窗口旋转重新安排小窗，不改变当前主视角。

主画面使用 `SCALE_ASPECT_FIT`。不为了“铺满”自动裁剪人脸或现场，不在接收端再做一次额外图像旋转。保留黑边是完整显示不同宽高比内容的明确取舍。当前没有自动人脸裁切或手动缩放模式。

实现时不能只设置 renderer 的 FIT：Compose 的 EXACT 全屏尺寸约束会让 SurfaceView 仍占满不同宽高比的窗口，截图已复现竖向画面在横屏窗口被裁掉顶部。`VideoRenderer` 外层容器明确计算 `min(窗口宽, 窗口高 × 帧比例)`，将 renderer 等比居中，剩余区域由父容器显示黑边。

## Show Me 与主视角

| A 模式 | B 模式 | A 默认主画面 | B 默认主画面 |
|---|---|---|---|
| Face | Face | B 的前摄 | A 的前摄 |
| Show Me | Face | A 自己的现场，方便取景 | A 的现场 |
| Face | Show Me | B 的现场 | B 自己的现场，方便取景 |
| Show Me | Show Me | A 自己的现场 | B 自己的现场 |

以上规则与横竖屏无关。双方同时 Show Me 时，两端都保留对方现场小窗，可点击选为主视角。各自选择只影响各自观看布局，不改变对方摄像头开关或取景方向。

- 支持双摄：现场和人像是独立 Track、独立 VideoFeed、独立 rotation/宽高比；最多一大三小。
- 不支持双摄：Show Me 使用单后摄；该摄像头复用原单摄传输 Track，不能误当作 concurrent back Track。
- 开启/关闭 Show Me 后按新模式恢复默认主画面，避免上一次手动选择阻止新现场出现。
- STARTING 期间保留最近稳定模式的布局，直到双摄成功或单摄降级确认。
- 关闭摄像头保留占位，不读取最后一帧来决定远端是否仍在直播；重新开启由正常媒体生命周期处理。
- 主辅切换只移动已有 renderer 节点，不更换 EGL、重建 PeerConnection 或发送 SDP。

## 镜像矩阵

| 视频来源 | 本地预览 | 对方观看 |
|---|---|---|
| 前摄人像 | 镜像，符合自拍习惯 | 不镜像 |
| 后摄现场 | 不镜像 | 不镜像 |
| 屏幕共享（后续） | 不镜像 | 不镜像 |

镜像是 renderer 的水平显示变换，不改发送媒体内容。必须用带有文字、左右标记和箭头的真实物体验证前摄/后摄，不能只看人脸主观判断。CameraX/OEM 是否在纹理变换中额外镜像，仍需真机确认；不能盲目再翻转一次。

## 采集与旋转实现

1. `DeviceOrientation` 初值读取当前 display rotation，避免已经横持进入通话时先假定为 ROTATION_0。
2. `OrientationQuantizer` 量化四个方向。跨越原方向 60° 才切换，即 45° 边界外增加 15° 迟滞，防止手机在对角线附近抖动导致 90° 来回旋转。UNKNOWN/无效值保留上次方向。
   无法检测方向传感器时，单摄保留 capturer 自身基于 display 的旋转，不用冻结的物理方向猜测覆盖它；双摄无传感器设备仍需验证 targetRotation 的设备行为。
3. 单摄延续现有补偿：前摄增加 physical-display，后摄使用相反符号；保留 sensor orientation，不复制或物理旋转像素。
4. CameraX 双摄更新各 Preview 的 targetRotation，由各自 TransformationInfo 提供 frame rotation；不叠加单摄补偿。
5. `VideoFeed.geometry` 发布每路实际 buffer 尺寸与 rotation；StateFlow 仅在几何变化时通知 UI，不经过每秒 RTC stats，也不保存图像。
6. renderer 继续消费原始 VideoFrame，由 WebRTC 应用 rotation。`VideoGeometry` 只用于布局，旋转 90°/270° 时交换显示宽高。

沿用现有通话窗口 `FULL_SENSOR`：通话期间允许四向旋转，即使系统自动旋转被关闭；离开通话恢复原 Activity 设置。该产品行为须在设备验收中明确检查。系统多窗口/大屏可能忽略 requestedOrientation，此时以实际窗口布局，不强行改变对端视频。

Activity 现有 configChanges 处理窗口旋转，普通转屏不触发后台结束通话逻辑。折叠、多窗口引起的其他 Activity 重建及后台通话能力属于已有生命周期边界，不能用该配置宣称所有场景都不会重建。

## 布局与交互实现

`CallVideoLayout` 为无 Android 依赖的布局策略，输入安全区窗口尺寸、每路显示比例与小窗数量。

- 竖屏/方形窗口：右侧纵向排列，一至三个小窗分别保留完整帧比例。
- 横屏窗口：从右向左横向排列，避免在短高度窗口中继续堆叠三个竖向小窗。
- 小窗按当前可用空间限制尺寸，保留顶部信息区和底部控件区；控件栏最大宽度 440 dp，横屏不会拉散至屏幕两端。
- 窗口、主画面或帧比例改变时重置拖动/停靠位置，避免旧像素坐标越界。正常同尺寸帧不会重置位置。
- 拖动过程中保持有界，越过左右边缘可停靠，点击边缘恢复；不把转屏前的停靠状态套到新的几何空间。
- 极小多窗口可能缩小小窗；当前不保证所有小窗在任意小窗口都达到 48 dp 触摸目标，仍需后续紧凑模式。普通手机横竖屏为本次验收范围。

## 画质与协议一致性

UI 必须上报**计算后的实际主画面**，不能仅上报用户是否手动点过小窗。默认的远端主画面也必须请求 LARGE。

`CallVideoLayout.remoteView` 将语义摄像头映射为既有 V1 Track 大小提示：DUAL 分别对应 front/back；BACK_ONLY 的现场对应原 front 传输槽位。当前场景中对方没有任何主画面时可上报 SMALL/SMALL；发送端仍保留自己的媒体策略决定权，现有双摄策略仍会保留一路主要编码质量。

`NativeRtcSession` 保留最新期望提示，DataChannel 尚未 OPEN 时不会丢失；OPEN 后发送。使用现有有序 V1 控制消息，未增加方向信令、服务端字段或摄像头控制命令。实际转屏仅依赖媒体 rotation 信息。

## 标注和屏幕共享的后续边界

2D 标注应绑定具体 Track 与帧时间戳；从触摸位置逆变换 FIT 留边、镜像和该帧 rotation。黑边上的触摸不能映射到视频内容。不得将归一化窗口坐标直接当作原始图像坐标。

空间标注继续使用历史视频帧对应的 AR pose、intrinsics 和 depth，不使用当前手机方向替代历史 pose。本次没有新增标注交互或坐标转换实现。

屏幕共享应使用共享源自身的尺寸/旋转，不能套用摄像头 OrientationEventListener 补偿。共享端转屏后的尺寸变化更新对应 Track 的几何与布局，前后摄仍各自独立。本次没有实现 MediaProjection。

## 验证

自动测试覆盖：

- 四方向显示宽高与前后摄补偿的全组合；传感器对角线抖动、360° 环绕、UNKNOWN 和横屏初值。
- Face/STARTING/DUAL/BACK_ONLY 的双方模式组合、默认及手动主视角。
- A/B 各四种帧旋转、观看窗口横/竖/方形、一至三个小窗的比例、边界和互不重叠。
- 默认远端主画面、双摄主辅交换、单后摄降级的 Track 清晰度请求。

Debug 预览使用合成 YUV 箭头、边框和左右标记，通过真实 SurfaceViewRenderer 渲染，不访问身份、网络、相机或麦克风。运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s <emulator> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <emulator> shell am instrument -w -e orientationPreview true com.zisee.app.debug.test/com.zisee.app.rtc.RtcSmokeInstrumentation
```

生成 Face、单方 Show Me、双方 Show Me × 观看端横/竖 × 远端横/竖的 12 张布局图。成功生成截图仅证明夹具执行完成，仍需人工查看完整边框、正立箭头、小窗比例、控件可见性和叠放。

真机验收至少两台 Android：A/B 分别在 0/90/180/270° 间转动；普通通话、A Show Me、B Show Me、双方 Show Me、双摄不支持降级；镜像文字左右；切换主辅后转屏、拖动/停靠后转屏；系统旋转锁开/关；横持进入通话、放平、摄像头开关、切网期间转屏。记录 callId 连续性、无多余 SDP、正立稳定性和资源释放。模拟器合成图不代表 CameraX 双摄、OEM 镜像、真实传输旋转或 AR 已通过真机验证。

## 本次验证记录（2026-09-09）

- 86 个 JVM 单元测试通过，Debug APK、AndroidTest APK 构建及 lint 通过。
- Small_Phone API 36 模拟器完成 12 种合成视频布局截图并复核；确认异向主画面正立、等比留边，小窗可见，横屏 Show Me 提示与小窗分离，挂断按钮可见。窄小窗使用单字标签并隐藏交换图标，完整语义标签仍保留给无障碍服务。
- 截图验证发现并修复仅设置 FIT 仍裁切、主容器背景遮盖已有 SurfaceView 小窗、提示覆盖横屏小窗三类问题。测试夹具另断言截图实际横/竖方向，避免 FULL_SENSOR 覆盖测试设置造成假覆盖。
- 首次模拟器因原 1 GB 内存被 lowmemorykiller 杀进程；以 4 GB 内存冷启动后完成验证。未修改真机应用或采集真实音视频。
- 截图保存在本地忽略目录 `android/app/build/orientation-review/files/`，不进入版本控制。两台真机端到端旋转、实际 CameraX 双摄和 OEM 镜像仍未验证。
