---
title: Send Front and Back Cameras as Independent Tracks
adr: 0005
status: Accepted
date: 2026-09-08
---

# Context

Show Me 需要同时传输前摄（人）与后摄（现场）。传输形态有两种：

```text
两路独立 Video Track
```

或：

```text
GPU 合成为一路视频后发送
```

这个决策直接决定主辅画面切换、按注意力分配带宽（Bandwidth Follows Attention）是否可能实现。

参考：`ARCHITECTURE.md` §3.3、§11.3、§12，`PRODUCT.md` §11.2。

# Decision

不同媒体源使用独立 Track：

```text
audio_microphone
video_front
video_back
video_screen
data
```

前后摄各自走独立编码器与独立 Track，不在客户端合成为单路视频。

# Alternatives

## GPU 合成单路

优点：

- 只有一路编码，功耗与带宽管理简单
- 对端实现最简单，等同普通视频通话

缺点：

- 接收端无法独立订阅或独立调整两路画质
- 主辅画面切换变成重新构图并重新编码，容易闪烁与掉帧
- 无法把带宽按用户注意力倾斜，「稳定 720p 优于卡顿 1080p」的策略失去操作空间
- 增加第三路（屏幕、外接相机）时结构无法扩展

# Consequences

优点：

- 两路可独立设置分辨率与码率，例如后摄 1080p30 + 前摄 360p15
- 主辅切换只是交换渲染位置与调整码率，不需要重建画面
- 未来增加更多摄像头或屏幕共享时结构一致

缺点：

- 同时运行两个编码器，功耗与发热明显高于单路，必须做热度与降级策略
- 需要在 Signaling 与 DataChannel 上维护 Track 语义（哪一路是现场、哪一路是人）
- 弱网时的降级逻辑更复杂：要决定先牺牲哪一路

长期影响：

- 功耗是这条决策最大的风险点，`PRODUCT.md` §25 已将「双摄功耗是否可接受」列为待验证问题；若真机验证结论为不可接受，应新增 ADR 而不是修改本条
