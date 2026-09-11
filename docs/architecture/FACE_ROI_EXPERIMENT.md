# Face ROI 检测实验

状态：2026-09-11，Debug 默认开启的实验。已接相机检测和诊断，尚未改变编码 QP 或背景处理。

## 构建隔离

Debug 默认 `zisee.faceRoi=true`，普通 `./gradlew.bat :app:assembleDebug` 即包含检测器：Debug source set 使用
`src/roiEnabled/java`，依赖 bundled `com.google.mlkit:face-detection:16.1.7`。A/B 对照或需要排除 SDK 时传
`'-Pzisee.faceRoi=false'`，改用 `src/roiDisabled/java`，没有该依赖和 ML Kit initializer。
Release 始终使用 `src/roiDisabled/java`，无论开关如何都不包含检测器。
`zisee.computeQuality=false` 时普通通话不安装相机处理器，但 faceRoi=true 的 APK 仍含 SDK；
要排除 SDK 和它的初始化行为，必须关闭 faceRoi 构建开关。

## 选型和隐私

使用官方当前列出的 bundled 版本，以避免首次使用等待模型下载。FAST 模式，不开 landmarks、
contours、classification 或 tracking，不做身份识别。SDK 提供显式 `close()`；在工作线程首次
检测时初始化，检测任务真正结束后才释放 Bitmap，不能把等待超时误当成 SDK 已停止读取输入。
项目 minSdk 26 满足官方指引的 API 23 下限。官方给出的 bundled 体积增量约 6.9 MB，实际
通用 Debug APK 含多个 ABI，应以本地打包差值为准。[Android 接入说明](https://developers.google.com/ml-kit/vision/face-detection/android)

ML Kit SDK/模型受 Google APIs/ML Kit 条款约束，不能把示例代码的 Apache 2.0 当成 SDK 许可。
官方说明输入图像及结果在设备内处理，但 SDK 会向 Google 发送性能/使用指标，并可能联系服务端
获取维护信息；这不等于无网络活动。[条款和隐私](https://developers.google.com/ml-kit/terms)

披露包含设备/应用信息、安装级标识、延迟、输入格式/尺寸及初始化、检测、释放等事件。
本实现没有声称关闭 SDK 遥测。推广给用户或开放 Release 前必须补齐产品隐私披露，并确认该
数据处理与产品要求兼容；当前开关专供开发实验。[数据披露说明](https://developers.google.com/ml-kit/android-data-disclosure)

## 处理与回退

- 每摄像头一个 analyzer，仅 C2+ 准备输入。屏幕源不安装此处理器，AR 接管和停采立即使 ROI 失效。
- 从已有校正后的 RGB 纹理取样，保持长宽比，不放大，限制在 640×360 范围。
  GPU readback 最多 921,600 bytes/次、2Hz；这比原场景摘要的 576 bytes 显著增加。
  readback、拷贝计入现有 GPU 处理耗时保护。主媒体帧仍走纹理链路。
- 在 worker 将 bottom-up RGBA 转成正向 ARGB，按视频 rotation 旋转一次。检测框使用
  正向图像的归一化坐标；还没有映射给 GPU shader，不混入 AR 坐标。
- 检测输入独占，不借用摄像头纹理；忙时不准备新输入。输入释放清空 byte array，临时 Bitmap
  在 SDK 任务完成后 recycle。不保存图像或框坐标到日志；调试详情只显示状态和有效框数量。
- 每个结果最多 8 框，提交后时间与源帧时间差均限 500ms，过期/未来/切源结果不可用。
  检测失败、native linkage 失败和 readback 失败旁路 ROI；GPU 整体超预算停止 ROI。
  切换 geometry 也不能绕过 2Hz 限频。

## 尚未验收

低分辨率输入尤其是小人脸可能漏检；空结果不能证明背景没有人。非空结果也可能有误检。
一般召回率、暗光/侧脸/多人、双摄、持续 CPU/内存/热预算尚无真机证据。worker 的异步推理耗时
不包含在 GPU P95 中，后续需要独立推理耗时窗口和预算保护，不能据 GPU P95 推断整体开销。
首轮实验只观察 ROI，不施加背景降质；QP map 仍受 WebRTC 公开接口限制。

模拟器入口：`-e faceRoi true`。覆盖真实 bundled SDK 的空白负例及公开 NASA 人像四方向检测，
测试图片归属和 SHA-256 见 `android/app/src/androidTest/assets/roi/README.md`；测试样本不进入主 APK。
