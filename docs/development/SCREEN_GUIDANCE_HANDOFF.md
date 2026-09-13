# 屏幕共享与跨 App 指导交接

## 范围与入口

实现专项设计的 P0 主流程及 v1.1 UI 语义高亮第一阶段；P1 的文字/橡皮/调色、P2 的 OCR/Vision/物体跟踪不在本轮。入口为通话“共享屏幕”：分别解释普通跨 App Overlay 与可选 Accessibility UI 高亮，可授权或仅共享画面。API 34+ 请求默认完整 Display，仍尊重系统最终授权。旧客户端无法使用语义高亮，但 P0 wire 包保持兼容，普通标注不受影响。

复用既有进程持有的通话 owner、前台服务与独立屏幕 Track。`ScreenGuidance` 由 NativeRtcSession 持有，主线程操作状态和窗口；Activity STOPPED 不移除窗口，通话释放前在主线程关闭。暂停仅阻止捕获帧进入 VideoSource，保留投屏会话；停止仍走原 stopScreenShare 路径，恢复摄像头并保留通话。低于 API 34 新增 DisplayListener 复用 resize，API 34+ 沿用捕获内容 resize 回调，不重建 VirtualDisplay。

## 实现

- `screen/GuidanceModel.kt`：复用 AR 的 VideoPoint/AnnotationAuthor；FIELD 权威，GUIDE 不能伪造作者。PUT、REMOVE、UNDO、CLEAR_OWN、CLEAR_ALL；会话/几何拒绝、去重、32 笔/每笔 128 点预算。Pointer 由权威端在 1.5 秒后移除。
- `screen/ScreenGuidance.kt`：可靠有序 DataChannel id=6，协议 magic/version、最大 48KB、接收队列上限 64、发送积压检查。增量 ACK、拒绝提示、3 秒未确认提示、快照恢复；断开时暂停远端输入。重连与增量序号缺口触发 STATE_SYNC，最终未发送的权威增量会重试快照。无需服务器转发标注。
- `screen/GuidanceCanvas.kt` / `ui/ScreenGuidanceLayer.kt`：FIT_CENTER 内容矩形映射，黑边不接受输入。指针、画笔、圈选、箭头、编号；手绘 25Hz 上限，路径达到预算时降采样。UI 输入携带会话/几何版本，主线程执行前再次校验。
- `screen/GuidanceOverlay.kt`：显示、菜单、绘图输入三个独立窗口。显示窗口透明度取 min(0.75, 系统最大遮挡不透明度)，NOT_TOUCHABLE/NOT_FOCUSABLE；控制窗口有界、可拖动吸边，横屏菜单可滚动；本地画笔才创建触摸窗口，“完成”移除。撤销、清除两种范围、显示隐藏、暂停/恢复、停止确认、返回咫尺；非绘图时 3 秒收起。权限撤销后移除窗口并告知对端能力变化。
- `screen/ZiseeGuidanceAccessibilityService.kt`：只在存在 active target 时读取当前 Accessibility Tree；节点对象在回调内转换为有界不可变快照，不跨线程持有。命中后提升可交互祖先并通过 `TYPE_ACCESSIBILITY_OVERLAY` 显示非触摸边框；窗口/内容/滚动变化以 60ms 防抖重定位，最多遍历 2048 节点、64 层。
- `screen/UiSemanticResolver.kt` / `SemanticAccessibilityBridge.kt`：本地 hit-test、稳定 locator 与生命周期桥接。密码和 editable 节点不保留文本 hash；网络只发送 target ID、规范化 bounds、role、能力位、enabled、confidence 与 tree revision，不发送原始文本、View ID、包名或完整 Tree，也不调用 `performAction`。
- `screen/GuidanceModel.kt` / `ScreenGuidance.kt`：新增 `UI_CAPABILITY`、`UI_TARGET_REQUEST/RESOLVED/UPDATE/LOST`、`UI_HIGHLIGHT_CLEAR`。语义包使用 v2 扩展 magic，P0 的 SYNC/COMMAND/ACK/REQUEST/REJECT 继续使用原 v1 字节格式。无服务/无节点时保留 1.5 秒普通 Pointer 回退。

## 验证与待验收

最新结果：370 项 JVM 单测，0 failures/errors/skipped；assembleDebug、assembleDebugAndroidTest、compileReleaseKotlin、lintDebug 通过。P0 实现时 emulator-5554 的 screenGuidance 冒烟与普通 native camera/ICE/编解码/ICE restart/释放回归均 PASS；本次语义高亮尚未宣称设备验收通过，仍需在开启 AccessibilityService 的实体设备上验证目标命中、滚动跟踪、跨 App 切换和 Overlay 对齐。

单测覆盖作者隔离、撤销/清除语义、重复/旧会话/旧几何拒绝、同尺寸 180 度旋转、暂停、编号稳定、容量预算、二进制畸形输入和增量副本一致性；语义测试覆盖命中/祖先提升、稳定 View ID 重定位、editable/password 脱敏、扩展包边界与 P0 wire version 兼容。`-e screenGuidance true` 本地冒烟覆盖真实 SCTP 两端、真实 Overlay 生命周期、ACK/同步、双方撤销、暂停、Pointer 到期、尺寸清空、旧 UI 输入拒绝、FIT_CENTER 黑边/归一化映射。测试使用合成捕获几何，不能代替 MediaProjection/Accessibility 全链路真机验收。

模拟器运行（仅对测试设备临时允许 Overlay，完成后恢复原 app-op）：

```powershell
adb -s emulator-5554 shell appops set com.lazydoglab.zisee.dev SYSTEM_ALERT_WINDOW allow
adb -s emulator-5554 shell am instrument -w -r -e screenGuidance true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb -s emulator-5554 shell appops set com.lazydoglab.zisee.dev SYSTEM_ALERT_WINDOW default
```

实体设备还需验证：完整屏幕授权、离开 Activity 后 Chrome/设置触摸穿透、开始/完成本地画笔、横竖屏与 180 度旋转、状态栏/挖孔/手势导航对齐、OEM 隐藏窗口、权限中途撤销、系统停止/锁屏、弱网重连、暂停期间新内容不发送、停止共享后语音继续。不要根据 API 支持或本地 SCTP 测试宣称这些已通过。

## 已知限制

平台依据：[MediaProjection 生命周期与完整屏幕配置](https://developer.android.com/media/grow/media-projection)、[Overlay 触摸与不透明度规则](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)、[Android 12 非可信触摸限制](https://developer.android.com/about/versions/12/behavior-changes-all)。

1. 普通标注固定在屏幕坐标；语义高亮可跟踪 Accessibility 暴露且 locator 能重新匹配的节点。Canvas/WebGL/视频等无语义内容直接回退普通 Pointer；OCR/Vision 尚未实现。
2. 全屏 capture 是否包含 Overlay 依设备而异。默认仅绘制中的本地预览，可手动开启永久“本地叠加”；ACK 不是视频帧呈现确认。
3. 只有全 Display 模式具备跨 App 语义；系统/OEM 若强制单 App 捕获，不能保证 Overlay 对齐。无公共 API 能保证识别相同尺寸的 App-only capture。
4. 不能可靠查询其他 App 的 secure/hide-overlay 标志，不能自动把黑屏归因于安全机制；AccessibilityService 只做公开语义的识别和高亮，不用于绕过限制或执行远程操作。
5. 几何版本不绑定每个视频帧。指导端额外校验视频宽高比并等待几何稳定 300ms，但高延迟旋转边界仍需逐帧元数据才能严格消除误标。自动键盘避让、网络质量颜色细分与实体设备 UI 验收待完善。
