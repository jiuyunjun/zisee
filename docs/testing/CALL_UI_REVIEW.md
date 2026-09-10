# 通话 UI 验收记录

设计交接：[CALL_UI_HANDOFF.md](../product/CALL_UI_HANDOFF.md)。实现由 GPT-5.6 Sol 完成，主代理独立审查代码和模拟器截图。

## 复现

构建 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`，安装 app 和 androidTest Debug APK。运行：

```powershell
adb -s emulator-5554 shell am instrument -w -r -e callUiPreview true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
```

入口仅渲染合成视频，不请求摄像头/麦克风权限、不发起通话。输出 `call-ui-{scenario}-{portrait|landscape}.png` 到应用 externalFilesDir，需同时检查 `stream=PASS`、`INSTRUMENTATION_CODE: -1` 和实际截图。

场景：普通通话、静音并关闭摄像头、长姓名、共享准备、共享进行中、AR 工具与提示、更多面板。另用既有 `orientationPreview=true` 覆盖双方双摄和视频方向组合。

小屏大字体复现使用模拟器 `wm size 960x1800`、density 480、`font_scale 1.5`，短边为 320dp。验证后恢复 `wm size reset`、`font_scale 1.0`；不要在用户真机上随意改这些设置。

## 首轮发现

普通竖屏及长姓名/静音底栏可用，但短屏横屏共享卡与底栏重叠、AR 提示覆盖顶部按钮，AR 默认按钮颜色对比度不足、小窗标签在放大字体下裁切，更多面板半展开时缺乏可见操作。已交还实现者修正。

## 最终结果（2026-09-10）

- 实现提交：`8aca17d`。主代理复跑 assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug 成功（增量任务使用已生成结果）；测试报告 247 项、0 失败、0 错误。Lint 0 错误、45 条警告，未隐藏警告。
- 最终版本分别在默认尺寸及 320dp 短边/1.5 倍字体生成 14 张截图，两轮 instrumentation 均 PASS；另生成 12 张双摄/视频方向截图，orientationPreview PASS。
- 主代理检查普通通话、静音/关画面、长姓名、共享、AR 提示/工具、更多面板及双摄关键截图。返工修复共享条遮挡、AR 按钮对比度、提示与操作优先级、小窗标签裁切及面板系统栏颜色后，本次代码与界面布局验收通过。
- 短横屏共享质量选择放在更多面板；AR 工具横向滚动，操作在提示之前。竖屏 AR 工具允许换行。更多面板初始全展开，内容可滚动。
- 本机截图保存在 `android/app/build/call-ui-review/final-default/`、`final-narrow/`、`final-orientation/`（构建目录不提交）。模拟器尺寸与字体已恢复。

## 验证边界

截图不能证明真实双摄、ARCore、音视频通话或屏幕采集能力。没有连接真机，不宣称完成真实设备媒体验收。TalkBack 已代码检查自动隐藏保护，尚未使用屏幕阅读器完成操作验证；截图生成也不等于完成所有按钮的端到端交互测试。
