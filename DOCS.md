# DOCS.md

# Zisee 文档索引与版本控制规范

## 1. 文档目的

本文档是 Zisee 项目的统一文档入口，同时定义项目文档的：

- 索引方式
- 命名规范
- 生命周期
- 版本号规则
- 状态标记
- 变更记录
- 适用代码版本
- 废弃策略
- 文档之间的引用方式
- 文档维护责任

目标是保证随着项目增长：

> 能快速知道“有哪些文档、哪份是最新的、哪份仍然有效、哪份对应当前代码”。

---

# 2. 文档总入口

项目根目录的重要文档：

| 文档 | 作用 | 状态 |
|---|---|---|
| `AGENTS.md` | Agent / 开发协作规范 | Active |
| `ARCHITECTURE.md` | 系统总体架构基线 | Active |
| `DOCS.md` | 文档索引与版本控制规范 | Active |
| `README.md` | 项目简介、快速开始、开发入口 | Active |

后续正式文档建议放入：

```text
docs/
```

---

# 3. 仓库结构

Zisee 是单仓库（mono-repo），按技术栈划分顶层目录，各自持有自己的构建入口：

```text
/
├── README.md
├── AGENTS.md
├── ARCHITECTURE.md
├── DOCS.md
│
├── android/          Android 客户端，Gradle 工程根目录
│   ├── settings.gradle.kts
│   ├── gradlew / gradle/
│   └── app/
│
├── server/           Go 后端，认证与信令会话基础
│
├── design/           设计画板源文件（*.dc.html）
│
└── docs/
    ├── product/
    │   ├── PRODUCT.md
    │   ├── ROADMAP.md
    │   ├── DESIGN.md
    │   └── ACCOUNT.md
    │
    ├── architecture/
    │   ├── RTC.md
    │   ├── SIGNALING.md
    │   ├── CAMERA.md
    │   ├── SCREEN_SHARE.md
    │   ├── AR.md
    │   ├── NETWORKING.md
    │   ├── SECURITY.md
    │   └── OBSERVABILITY.md
    │
    ├── protocols/
    │   ├── SIGNALING_PROTOCOL.md
    │   ├── DATA_CHANNEL_PROTOCOL.md
    │   └── AR_PROTOCOL.md
    │
    ├── development/
    │   ├── ANDROID_SETUP.md
    │   ├── BACKEND_SETUP.md
    │   ├── TESTING.md
    │   ├── DEBUGGING.md
    │   └── RELEASE.md
    │
    ├── operations/
    │   ├── DEPLOYMENT.md
    │   ├── TURN.md
    │   └── INCIDENTS.md
    │
    ├── adr/
    │   ├── 0001-use-native-webrtc.md
    │   ├── 0002-p2p-first.md
    │   ├── 0003-use-camerax.md
    │   ├── 0004-cloudflare-turn.md
    │   ├── 0005-multi-track-camera.md
    │   ├── 0006-identity-first-account-later.md
    │   └── ...
    │
    └── archive/
```

`docs/` 始终位于仓库根目录，不按端拆分：产品、架构、协议、ADR 同时约束客户端与服务端，拆开会立刻产生两份互相漂移的事实来源。

代码目录可以随着项目实际需求逐步创建。当前 server 已实现认证、PostgreSQL 和 WebSocket 会话。

禁止为了“看起来完整”一次性创建大量空文档。

---

# 4. 文档状态

每一份正式设计文档都应具有明确状态。

允许状态：

```text
Draft
Review
Active
Deprecated
Archived
```

含义：

## Draft

草稿。

特点：

- 内容可能快速变化。
- 不能被视为正式架构依据。
- 允许存在未解决问题。

---

## Review

正在评审。

特点：

- 主要内容已经形成。
- 等待技术或产品确认。
- 不应再发生无边界的大改。

---

## Active

当前有效。

特点：

- 是当前实现和决策的正式参考。
- 与当前代码和架构保持一致。
- 修改应进行版本更新。

---

## Deprecated

已经不推荐使用，但保留用于兼容或历史查询。

必须说明：

```text
Deprecated Since:
Replacement:
Reason:
```

---

## Archived

仅作为历史资料保存。

不得作为当前开发依据。

---

# 5. 文档头部元数据

除以下特殊文件外：

```text
README.md
AGENTS.md
DOCS.md
```

其他重要设计文档建议在顶部包含统一元数据。

推荐格式：

```markdown
---
title: WebRTC 网络架构
document_id: ARCH-RTC-001
version: 1.2.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: ">=0.1.0"
owners:
  - core
---
```

字段说明：

### title

文档名称。

---

### document_id

文档稳定 ID。

即使文件名以后变化，ID 仍然保持不变。

---

### version

文档自身版本。

采用：

```text
MAJOR.MINOR.PATCH
```

---

### status

必须是：

```text
Draft
Review
Active
Deprecated
Archived
```

---

### created

首次创建日期。

格式：

```text
YYYY-MM-DD
```

---

### updated

最近一次有意义修改日期。

格式：

```text
YYYY-MM-DD
```

---

### applies_to

该文档适用的代码版本。

例如：

```text
>=0.1.0
```

或：

```text
0.2.x
```

或：

```text
>=0.3.0 <0.5.0
```

---

### owners

文档维护责任模块。

第一阶段可以使用：

```text
core
android
backend
rtc
ar
network
```

不要求绑定具体个人。

---

# 6. 文档版本号

文档采用语义化版本：

```text
MAJOR.MINOR.PATCH
```

例如：

```text
1.0.0
1.1.0
1.1.1
2.0.0
```

---

# 7. MAJOR 版本

以下情况升级 MAJOR：

- 架构发生根本变化。
- 原有核心设计不再成立。
- 文档结构被重新定义。
- 协议存在不兼容变化。
- 旧版本无法正确指导新实现。

例如：

```text
P2P-first
```

变为：

```text
SFU-first
```

则相关 RTC 架构文档应：

```text
1.x.x
→
2.0.0
```

---

# 8. MINOR 版本

以下情况升级 MINOR：

- 增加重要章节。
- 新增功能设计。
- 补充新的兼容策略。
- 增加新的协议字段但保持兼容。
- 设计扩展但原有内容仍然成立。

例如：

```text
1.1.0
→
1.2.0
```

新增：

```text
AV1 capability negotiation
```

但不影响 H.264 原设计。

---

# 9. PATCH 版本

以下情况升级 PATCH：

- 修正文案。
- 修正小错误。
- 增加澄清。
- 修正示例。
- 修改拼写。
- 不改变架构行为。

例如：

```text
1.2.0
→
1.2.1
```

---

# 10. 不需要版本升级的修改

纯粹的：

- Markdown 排版
- 空格
- 标点
- 无语义格式化

如果没有影响内容，可以不升级文档版本。

但如果发生 commit，仍应正常提交。

---

# 11. 文档版本与 Git 的关系

文档版本号：

```text
不是 Git commit 的替代品
```

两者分别解决：

### Git

回答：

```text
谁改了什么？
什么时候改的？
具体 diff 是什么？
```

### Document Version

回答：

```text
当前设计属于哪个逻辑版本？
这次变化是否兼容？
```

因此：

> 所有有意义文档修改仍然必须 Git commit。

遵守 `AGENTS.md` 中的提交规范。

---

# 12. 文档 Changelog

重要文档应维护简洁的版本记录。

推荐放在文档末尾：

```markdown
# Changelog

## 1.2.0 - 2026-09-08

- 增加 AV1 capability negotiation。
- 增加 codec fallback 规则。

## 1.1.0 - 2026-09-06

- 增加 TURN fallback。
- 增加 ICE restart。

## 1.0.0 - 2026-09-01

- 初始架构。
```

Changelog 记录：

- 设计变化
- 行为变化
- 重要新增内容
- 废弃内容

不要记录：

```text
修了一个错别字
改了 Markdown 空格
```

---

# 13. 文档与代码版本

项目版本与文档版本是两个独立概念。

例如：

```text
App Version
0.3.0
```

对应：

```text
RTC.md
2.1.0
```

完全正常。

必须通过：

```text
applies_to
```

表达关系。

例如：

```yaml
applies_to: ">=0.3.0"
```

---

# 14. 文档索引规则

所有 Active / Review 的重要文档都必须出现在本文件索引中。

不要依靠：

```text
“大家应该知道 docs 里有哪些文件”
```

DOCS.md 是权威入口。

---

# 15. 当前文档索引

## 根目录

### README.md

路径：

```text
/README.md
```

用途：

- 项目简介与当前实现边界
- 仓库结构（mono-repo 顶层目录）
- 开发环境、构建命令与产物路径
- 代码入口一览

状态：

```text
Active
```

---

### AGENTS.md

路径：

```text
/AGENTS.md
```

用途：

- Agent 工作规则
- UTF-8 规则
- Git commit 规则
- 工程最佳实践
- 安全要求
- Definition of Done

状态：

```text
Active
```

---

### ARCHITECTURE.md

路径：

```text
/ARCHITECTURE.md
```

用途：

- Zisee 总体技术架构
- P2P-first
- TURN fallback
- WebRTC
- 双摄
- Screen Share
- ARCore
- DataChannel
- Backend
- MVP 阶段划分

状态：

```text
Active
```

---

### DOCS.md

路径：

```text
/DOCS.md
```

用途：

- 文档总索引
- 文档版本规范
- 文档生命周期
- 文档维护规则

状态：

```text
Active
```

---

## docs/product

### PRODUCT.md

路径：

```text
/docs/product/PRODUCT.md
```

`document_id`：

```text
PROD-001
```

用途：

- 产品定义与愿景
- 核心模式：Face Call / Show Me / Dual View / Screen Share / Annotation / AR Assist
- 目标用户与核心场景
- MVP 范围与非目标
- 产品成功指标与待验证假设

状态：

```text
Active
```

---

### ROADMAP.md

路径：

```text
/docs/product/ROADMAP.md
```

`document_id`：

```text
ROADMAP-001
```

用途：

- M0 ~ M5 阶段划分
- 每个阶段的功能范围与退出条件
- 连接可靠性、双摄、屏幕共享、标注、AR 的推进顺序

状态：

```text
Active
```

---

### DESIGN.md

路径：

```text
/docs/product/DESIGN.md
```

`document_id`：

```text
DESIGN-001
```

用途：

- UI/UX 设计规范
- 视觉方向与品牌气质
- 核心页面与通话模式的设计原则
- 权限、错误、状态、横竖屏与可访问性要求
- 可直接交给设计工具使用的设计 Brief

状态：

```text
Active
```

---

### CALL_MULTITASKING.md

[M3 通话多任务、屏幕共享与画面协作专项](./docs/product/CALL_MULTITASKING.md)

`document_id`：`DESIGN-M3-001`；版本：1.0.0；状态：Draft。

用途：后台通话、应用内迷你通话、系统画中画、Show Me/共享/AR 组合、双端布局、定格 2D 协作与分步验收。设计不代表已实现。

---

### AR_INTERACTION.md

[AR 现场协作交互专项设计](./docs/product/AR_INTERACTION.md)

`document_id`：`DESIGN-AR-001`；版本：1.2.0；状态：Draft。

用途：AR 入口与双方视角、单现场协作、同时标记与清除权限、功能互斥、异常恢复、扩展能力及分步验收。产品提案，不代表已实现。

---

### ACCOUNT.md

路径：

```text
/docs/product/ACCOUNT.md
```

`document_id`：

```text
ACCOUNT-001
```

用途：

- Identity First / Account Later 身份模型
- Local Identity 与 identityId
- Device Credential 与 Access Token
- 邀请优先的加人方式
- 未来账户绑定与多设备演进

状态：

```text
Active
```

---

## docs/architecture

### SECURITY.md

路径：

```text
/docs/architecture/SECURITY.md
```

`document_id`：

```text
ARCH-SECURITY-001
```

用途：

- M0 安全基线
- 秘密信息与签名密钥处理
- 权限与数据边界

状态：

```text
Active
```

---

## docs/testing

### TESTING.md

路径：

```text
/docs/testing/TESTING.md
```

`document_id`：

```text
TEST-GUIDE-001
```

用途：

- M0 验证指南
- 单元测试、lint、构建的执行方式
- 真机验证要求

状态：

```text
Active
```

---

## docs/operations

### DEPLOYMENT.md

路径：`/docs/operations/DEPLOYMENT.md`

`document_id`：`OPS-DEPLOYMENT-001`

用途：Cloud Run 部署目标、项目准备状态与后续部署步骤。

状态：Active

---

## docs/adr

ADR 使用独立状态词（`Proposed` / `Accepted` / `Superseded` / `Rejected`），不使用文档状态词。

| ADR | 标题 | 状态 | 日期 |
|---|---|---|---|
| [0001](./docs/adr/0001-use-native-webrtc.md) | Use Native WebRTC | Accepted | 2026-09-08 |
| [0002](./docs/adr/0002-p2p-first.md) | P2P First with TURN Fallback | Accepted | 2026-09-08 |
| [0003](./docs/adr/0003-use-camerax.md) | Use CameraX as the Primary Camera API | Accepted | 2026-09-08 |
| [0004](./docs/adr/0004-cloudflare-turn.md) | Use Cloudflare TURN for Fallback Relay | Accepted | 2026-09-08 |
| [0005](./docs/adr/0005-multi-track-camera.md) | Send Front and Back Cameras as Independent Tracks | Accepted | 2026-09-08 |
| [0006](./docs/adr/0006-identity-first-account-later.md) | Identity First, Account Later | Accepted | 2026-09-08 |

新增 ADR 后必须同时更新本表。

---

# 16. 推荐后续建立的文档

随着实现开始，优先建立：

## RTC.md

定义：

- PeerConnection
- SDP
- ICE
- Codec
- Tracks
- RTC stats
- reconnect
- bandwidth control

---

## SIGNALING.md

定义：

- Signaling state
- WebSocket
- Message schema
- Call lifecycle
- Error handling

---

## CAMERA.md

定义：

- CameraX
- Concurrent Camera
- Front / Back Track
- Capability detection
- Resolution strategy
- Camera lifecycle

---

## AR.md

定义：

- ARCore Session
- Pose
- Depth
- Hit Test
- Anchor
- Timestamp alignment
- Remote annotation

---

## TURN.md

定义：

- Cloudflare TURN
- credential
- fallback
- metrics
- self-hosted migration

---

# 17. ADR

重要架构决策使用 ADR。

目录：

```text
docs/adr/
```

文件命名：

```text
NNNN-short-decision-name.md
```

例如：

```text
0001-use-native-webrtc.md
0002-p2p-first.md
0003-use-camerax.md
0004-use-cloudflare-turn.md
```

---

# 18. ADR 状态

ADR 状态推荐：

```text
Proposed
Accepted
Superseded
Rejected
```

---

# 19. ADR 模板

```markdown
---
title: Use Native WebRTC
adr: 0001
status: Accepted
date: 2026-09-08
---

# Context

为什么需要做这个决策。

# Decision

最终选择。

# Alternatives

考虑过哪些方案。

# Consequences

优点、缺点、长期影响。
```

---

# 20. ADR 不允许直接修改历史结论

如果一个 Accepted ADR 后续不再适用：

不要修改旧 ADR 让它“看起来一直是现在的结论”。

正确做法：

```text
ADR 0001
Accepted
```

新的：

```text
ADR 0012
Accepted
```

然后：

```text
0001
status: Superseded
superseded_by: 0012
```

这样保留决策历史。

---

# 21. 设计文档可以修改

与 ADR 不同：

```text
RTC.md
AR.md
CAMERA.md
```

属于“当前状态文档”。

它们应该持续更新，始终尽量描述：

```text
当前真实架构
```

ADR 描述：

```text
为什么走到这里
```

设计文档描述：

```text
现在是什么样
```

两者不要混淆。

---

# 22. 文档过期处理

发现文档不再适用时：

禁止直接放着不管。

必须选择：

### 更新

如果仍然属于当前设计。

### Deprecated

如果正在淘汰但仍有兼容用途。

### Archived

如果彻底只剩历史价值。

---

# 23. Deprecated 文档

必须在顶部明确：

```text
WARNING: DEPRECATED
```

并提供：

```text
Replacement:
```

例如：

```markdown
> [!WARNING]
> 本文档已废弃。
> 请使用 `docs/architecture/RTC_V2.md`。
```

---

# 24. Archived 文档

移动到：

```text
docs/archive/
```

除非保留原路径对于外部引用非常重要。

Archive 内容不再作为当前实现依据。

---

# 25. 文档引用

内部引用必须使用相对路径。

推荐：

```markdown
[RTC 架构](./docs/architecture/RTC.md)
```

不要使用：

```text
本机绝对路径
```

例如：

```text
C:\Users\...
/Users/name/...
```

禁止进入正式文档。

---

# 26. 文档中的代码

代码示例必须尽量满足：

- 与当前实现一致。
- 标明语言。
- 避免使用真实 secret。
- 不复制已经明显过时的 API。
- 如果仅是伪代码，应明确说明。

例如：

```text
Pseudo-code
```

不要让读者误以为可以直接编译。

---

# 27. 日期

正式文档日期统一：

```text
YYYY-MM-DD
```

例如：

```text
2026-09-08
```

不要混用：

```text
09/08/26
2026/9/8
Sep 8
```

避免歧义。

---

# 28. 文件编码

所有文档：

```text
UTF-8
```

默认：

```text
UTF-8 without BOM
LF
```

与 `AGENTS.md` 保持一致。

---

# 29. 文档语言

当前项目正式技术文档默认：

```text
中文
```

允许保留必要英文术语，例如：

```text
WebRTC
PeerConnection
TURN
ICE
Track
Anchor
Pose
DataChannel
```

不要为了强行中文化产生难以理解的生造词。

---

# 30. 文档更新触发条件

发生以下情况必须评估是否更新文档：

- 新模块引入
- 架构改变
- API 协议变化
- 状态机变化
- 数据模型变化
- 新权限
- 新第三方服务
- 网络策略变化
- Codec 策略变化
- AR 坐标或同步策略变化
- 安全设计变化
- 部署流程变化
- MVP 范围变化

---

# 31. 代码修改与文档修改

如果代码修改改变了已记录的行为：

```text
代码和文档应该在同一任务中更新
```

不要长期存在：

```text
代码已经变了
文档仍描述旧架构
```

---

# 32. 文档 Review

重要设计文档从：

```text
Draft
```

进入：

```text
Active
```

前，应检查：

- 是否与当前代码一致
- 是否存在明显矛盾
- 是否有未说明的重要 trade-off
- 是否链接正确
- 是否版本正确
- 是否更新日期正确
- 是否存在安全敏感信息

---

# 33. 单一事实来源

同一个重要事实尽量只定义一次。

例如：

```text
Signaling message schema
```

应该由：

```text
SIGNALING_PROTOCOL.md
```

作为权威来源。

其他文档：

```text
引用它
```

不要复制多份完整 schema。

否则后续容易产生：

```text
A 文档说字段是 String
B 文档说字段是 Int
```

---

# 34. 文档优先级

发生冲突时，默认优先级：

```text
实际运行代码
    ↓
Accepted ADR
    ↓
Active 专项设计文档
    ↓
ARCHITECTURE.md
    ↓
README.md
    ↓
Draft 文档
    ↓
Archived 文档
```

但：

> “代码优先”不意味着允许文档长期错误。

发现不一致必须修正文档或代码。

---

# 35. ARCHITECTURE.md 的定位

`ARCHITECTURE.md` 只描述：

```text
全局架构
```

不要无限膨胀成所有细节的集合。

详细设计应该逐步拆到：

```text
RTC.md
CAMERA.md
AR.md
NETWORKING.md
...
```

ARCHITECTURE.md 负责：

```text
整体关系
+
核心决策
+
链接入口
```

---

# 36. DOCS.md 的定位

本文件负责：

```text
“去哪里找”
```

而不是：

```text
“具体怎么实现”
```

任何新增正式文档都应该考虑加入本索引。

---

# 37. 文档 Commit

根据 `AGENTS.md`：

每次有意义文档修改都必须 commit。

示例：

```text
docs: add documentation index
docs: define document versioning policy
docs: add AR architecture
docs: update RTC design for ICE restart
```

---

# 38. 自动化

项目成熟后可以考虑 CI 检查：

- Markdown links
- UTF-8
- metadata
- duplicate document_id
- invalid document status
- missing index entries
- outdated generated docs

第一阶段不作为阻塞项。

不要为了文档自动化提前增加复杂 CI。

---

# 39. 当前版本控制策略

当前采用：

```text
Git
+
Document Semantic Versioning
+
Status
+
Changelog
+
ADR
```

分别解决：

```text
Git
→ 具体历史

Version
→ 逻辑版本

Status
→ 是否有效

Changelog
→ 版本变化摘要

ADR
→ 决策原因
```

---

# 40. 文档维护原则

Zisee 的文档遵循：

> 文档是工程的一部分，不是工程完成后的附属品。

要求：

- 当前
- 可查
- 可追溯
- 可版本化
- 可废弃
- 不重复
- 不隐藏历史

如果文档无法继续准确指导开发，就必须更新、废弃或归档。

---

# Changelog

## M3 多任务专项索引 - 2026-09-10

- 增加 CALL_MULTITASKING.md，同步 AR 专项 1.2.0 与待实施生命周期边界。

## AR 交互专项索引 - 2026-09-10

- 增加并更新 AR_INTERACTION.md 产品设计提案入口，明确 `99cf7a9` / `0bdaaa6` 基础闭环与待交付边界。

## 1.3.1 - 2026-09-08

- 同步部署文档索引：账单阻塞已解除，Cloud Run 构建部署 API 已启用。

## 1.3.0 - 2026-09-08

- 增加 Cloud Run 部署准备文档，记录 Zisee GCP 项目及账单阻塞状态。

## 1.2.0 - 2026-09-08

- 仓库改为 mono-repo：Android 工程移入 `android/`，新增 `server/` 占位。
- 第 3 章由「推荐文档目录」改为「仓库结构」，同时描述代码目录与文档目录。
- 明确 `docs/` 保留在仓库根目录，不按端拆分。
- 索引增加 README.md、`docs/architecture/SECURITY.md`、`docs/testing/TESTING.md`。
- README.md 状态由 Planned 改为 Active。

## 1.1.0 - 2026-09-08

- 建立 `docs/adr/`，写入 ADR 0001 ~ 0006。
- 索引增加 `docs/product/`：PRODUCT.md、ROADMAP.md、DESIGN.md、ACCOUNT.md。
- 索引增加 ADR 一览表，并要求新增 ADR 时同步更新。
- 推荐目录中的 `UX.md` 更正为实际存在的 `DESIGN.md`，并补充 `ACCOUNT.md`。
- PRODUCT.md 与 ROADMAP.md 已建立，从「推荐后续建立的文档」移入正式索引。

## 1.0.0 - 2026-09-08

- 建立 Zisee 文档总索引。
- 定义文档生命周期。
- 定义文档语义化版本规则。
- 定义文档元数据。
- 定义 Changelog 规范。
- 定义 ADR 规则。
- 定义文档废弃和归档机制。
- 建立当前文档索引。

## 服务端新增文档（1.4.0 - 2026-09-08）

- [身份协议](docs/protocols/IDENTITY_PROTOCOL.md)：PROTO-IDENTITY-001，Active。
- [信令会话](docs/architecture/SIGNALING.md)：ARCH-SIGNALING-001，Active。

## Android 后端接入（1.5.0 - 2026-09-08）

[ANDROID_BACKEND.md](docs/development/ANDROID_BACKEND.md)：DEV-ANDROID-BACKEND-001，Active；Keystore、构建配置、前台连接与验证边界。

## 通话控制协议（2026-09-08）

[CALL_PROTOCOL.md](docs/protocols/CALL_PROTOCOL.md)：PROTO-CALL-001，Active；邀请兑换、接听／拒绝／挂断、通话归属、事务并发和快照同步。

## 视频体验专项（2026-09-08）

[VIDEO_EXPERIENCE.md](docs/architecture/VIDEO_EXPERIENCE.md)：ARCH-VIDEO-EXPERIENCE-001，Active；高清上限、拥塞控制分工、过热保护、媒体监测与真机验收目标。

## 网络切换专项（2026-09-09）

[NETWORK_HANDOVER.md](docs/architecture/NETWORK_HANDOVER.md)：网络切换低卡顿设计；第 39 节记录当前实施范围与待验收项。

## 视频方向专项（2026-09-09）

[VIDEO_ORIENTATION.md](docs/architecture/VIDEO_ORIENTATION.md)：ARCH-VIDEO-ORIENTATION-001，Active；双方横竖屏矩阵、Show Me 主辅视角、镜像、逐 Track 帧方向、布局适配和设备验收。

## 音频专项（2026-09-09）

[AUDIO.md](docs/architecture/AUDIO.md)：音频处理、Opus、设备路由、DeepFilterNet 与自动降级设计；第 56 节记录实际实施和验证边界。
