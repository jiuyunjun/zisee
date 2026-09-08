---
title: Use CameraX as the Primary Camera API
adr: 0003
status: Accepted
date: 2026-09-08
---

# Context

Zisee 的核心差异化能力 Show Me 依赖前后摄同时采集，Android 上的相机接口有两层可选：

```text
CameraX
```

和：

```text
Camera2
```

Concurrent Camera 的设备支持情况差异很大，必须运行时检测能力而不是假设（`ARCHITECTURE.md` §3.4）。

参考：`ARCHITECTURE.md` §11.1、§11.2。

# Decision

相机层默认使用：

```text
CameraX
```

当 CameraX 无法覆盖所需能力时，针对该能力局部下沉到 Camera2，而不是整体改用 Camera2。

Concurrent Camera 必须查询设备 capability；不支持的设备隐藏双摄入口并降级为快速切换摄像头（`DESIGN.md` §25、`PRODUCT.md` §27）。

# Alternatives

## 全面使用 Camera2

优点：

- 能力上限最高，可精细控制 session、surface 与参数

缺点：

- 样板代码多，生命周期与设备兼容处理复杂
- 厂商差异需要自己逐个消化
- 对 MVP 来说投入产出比低

## 使用 WebRTC 自带的 Camera Capturer

优点：

- 与 WebRTC 集成最省事

缺点：

- 对双摄并发、分辨率协商、能力查询的控制力不足
- 难以支撑主辅画面切换与按注意力分配带宽的策略

# Consequences

优点：

- 生命周期与常见设备兼容问题由 CameraX 处理
- Concurrent Camera 能力查询有官方接口
- 相机代码量显著低于 Camera2

缺点：

- 部分高级能力仍需下沉 Camera2，代码库中会同时存在两层相机代码
- CameraX 版本升级可能带来行为变化，需要在真机矩阵上回归

长期影响：

- 外接 USB 摄像头、多设备摄像头等后续方向可能超出 CameraX 范围，届时应新增 ADR
