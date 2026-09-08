---
title: Zisee 身份与账户系统设计
document_id: ACCOUNT-001
version: 1.0.0
status: Active
created: 2026-09-08
updated: 2026-09-08
applies_to: ">=0.1.0"
owners:
  - core
  - backend
  - android
---

# ACCOUNT.md

# Zisee 身份与账户系统设计

## 1. 目标

Zisee 前期不强制注册登录。

首次进入 App：

```text
输入姓名
↓
生成稳定 identityId
↓
保存本地身份
↓
直接进入 App
```

以后持续使用同一个姓名和 ID。

未来接入正式登录系统时：

```text
现有身份
↓
绑定账户
↓
保留原 identityId、联系人、历史关系
```

核心原则是：

> Identity First，Account Later。

也就是从第一天就区分“身份”和“账户”，避免以后接登录系统时推倒重来。

---

# 2. Identity 与 Account

## Identity

表示：

```text
这个 Zisee 用户是谁
```

例如：

```text
identityId = zid_01J...
displayName = 九云
```

即使没有正式账户，Identity 也可以存在。

## Account

表示：

```text
用户如何证明自己拥有这个 Identity
```

未来可以接：

- Google
- Passkey
- Apple
- Email
- Phone
- 其他 OAuth / OIDC

前期：

```text
Account = none
```

但：

```text
Identity = exists
```

---

# 3. 阶段设计

```text
Phase A
Local Identity
↓
Phase B
Anonymous Server Identity
↓
Phase C
Optional Account Binding
↓
Phase D
Multi-device Account
```

---

# 4. Phase A：本地身份

首次启动：

```text
Welcome
↓
输入姓名
↓
创建 Local Identity
↓
进入 Home
```

不要求：

- 邮箱
- 手机号
- 密码
- 验证码
- Google 登录

姓名只是：

```text
displayName
```

不是唯一用户名。

多人可以都叫：

```text
张三
```

真正唯一的是：

```text
identityId
```

---

# 5. identityId

首次创建身份时生成。

推荐：

```text
UUIDv7
```

或：

```text
ULID
```

也可以使用项目自定义前缀：

```text
zid_01JXXXXXXXXXXXX
```

要求：

- 与姓名无关
- 与设备型号无关
- 不使用 IMEI
- 不使用 MAC Address
- 不使用手机号
- 不直接使用 Android ID
- 不包含隐私信息

identityId 是公开标识，不是认证凭据。

---

# 6. 本地数据模型

建议：

```kotlin
data class LocalIdentity(
    val identityId: String,
    val displayName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

第一阶段可以保存到：

```text
DataStore
```

没有必要为了几个字段立即引入数据库。

---

# 7. 首次 UI

推荐：

```text
欢迎来到 Zisee

别人应该怎么称呼你？

[ 九云                     ]

             继续
```

辅助文案：

```text
以后可以随时修改。
```

不要使用：

```text
注册
游客账户
匿名账户
跳过登录
```

用户没有必要理解这些内部概念。

---

# 8. 启动流程

```text
App Start
↓
Load Local Identity
↓
Identity exists?
├── No
│   ↓
│   Name Onboarding
│   ↓
│   Create identityId
│   ↓
│   Save
│
└── Yes
    ↓
    Home
```

以后即使没有网络：

```text
Identity 仍然存在
```

只是无法发起网络通话。

---

# 9. 修改姓名

用户可以随时：

```text
九云
↓
Jiu
```

只修改：

```text
displayName
```

绝不修改：

```text
identityId
```

因此联系人和历史关系不会因为改名字断掉。

---

# 10. Phase B：匿名服务端身份

当项目开始需要：

- Signaling
- Push
- 联系人
- 邀请
- 通话历史
- TURN credential

时，本地 Identity 需要同步到 Backend。

但用户仍然不需要注册。

客户端首次连接：

```text
POST /identity/bootstrap
```

服务端创建：

```text
Anonymous Identity
```

用户体验仍然只是：

```text
输入姓名
→
使用
```

---

# 11. identityId 不能用于认证

禁止：

```text
知道 identityId
=
可以冒充这个用户
```

identityId 只是标识。

必须另外存在：

```text
Device Credential
```

正确关系：

```text
Identity
=
你是谁

Credential
=
你怎么证明你是谁
```

---

# 12. deviceId

每个安装设备创建独立：

```text
deviceId
```

例如：

```text
zdev_01J...
```

不要使用硬件唯一标识。

未来关系：

```text
Identity
├── Device A
├── Device B
└── Device C
```

前期一般只有一个 Device。

---

# 13. Device Credential

长期推荐：

```text
Android Keystore
```

生成设备 key pair。

```text
Private Key
→ 只在设备

Public Key
→ Backend
```

认证可以：

```text
Backend challenge
↓
Device signs challenge
↓
Backend verifies public key
↓
Issue access token
```

MVP 如果需要更简单，也可以暂时采用服务端生成的高熵 device secret，并使用 Keystore-backed storage 安全保存。

---

# 14. Access Token

设备认证成功后获取短期：

```text
Access Token
```

用于：

- Signaling
- API
- TURN credential
- Push registration

Token 不是 identityId。

Token 过期：

```text
身份不能丢失
```

---

# 15. 邀请优先，而不是用户名搜索

前期不建议做：

```text
全局用户名搜索
```

更适合：

```text
邀请链接
+
QR Code
```

例如：

```text
用户 A
↓
生成邀请
↓
分享给 B
↓
B 打开
↓
看到 A
↓
开始通话 / 添加联系人
```

这样不需要提前设计 public username。

---

# 16. 联系人数据

联系人必须引用：

```text
identityId
```

而不是 displayName。

例如：

```text
Contact {
    identityId
    cachedDisplayName
}
```

姓名只是展示数据。

---

# 17. 通话记录

建议：

```text
CallRecord {
    callId
    peerIdentityId
    peerDisplayNameSnapshot
    startedAt
}
```

保留：

```text
peerDisplayNameSnapshot
```

可以展示当时名称。

真正关联仍然使用：

```text
peerIdentityId
```

---

# 18. Phase C：绑定正式账户

后期加入 Google / Passkey 等登录方式时：

不能：

```text
原匿名身份
↓
废弃
↓
创建新用户
```

应该：

```text
现有 Identity
↓
Bind Account
↓
Identity 保持不变
```

例如：

```text
Before

identityId = zid_A
displayName = 九云
account = none
```

绑定 Google 后：

```text
After

identityId = zid_A
displayName = 九云
account = google:xxxx
```

本质是：

```text
升级现有身份
```

而不是重新注册。

---

# 19. 绑定后保留内容

必须保留：

- identityId
- 联系人
- 通话历史
- Block list
- Invite 关系
- 设置
- 未来其他与 Identity 绑定的数据

这就是 Identity / Account 分离的核心价值。

---

# 20. 账户 UI

未来 Settings：

```text
账户

九云
仅此设备

[ 绑定账户 ]
```

说明：

```text
绑定账户后，可在更换设备时恢复身份。
```

前期不需要首页提醒用户注册。

---

# 21. “仅此设备”

Local-only / Anonymous 用户可以显示：

```text
仅此设备
```

它表达：

```text
当前身份还没有可恢复的正式账户
```

这比：

```text
Anonymous User
```

更适合普通用户。

---

# 22. 卸载风险

如果用户没有绑定正式账户：

```text
卸载 App
+
本地 credential 丢失
=
身份可能无法恢复
```

必须真实表达。

不要假装未来一定能恢复。

在适当位置提示：

```text
绑定账户后可以在更换或重装设备时恢复身份。
```

---

# 23. Phase D：多设备

正式账户成熟后：

```text
Account
↓
Identity
↓
Devices
```

例如：

```text
Identity zid_A
├── Pixel
├── Galaxy
└── Tablet
```

一个用户仍然保持同一个：

```text
identityId
```

---

# 24. 多设备来电

未来：

```text
Caller
↓
Identity B
↓
Active Devices
↓
Push
```

某一台接听：

```text
其他设备停止响铃
```

这属于后期能力，不阻塞 MVP。

---

# 25. 推荐登录方式

未来优先考虑：

```text
Google
Passkey
```

如果增加 iOS：

```text
Sign in with Apple
```

不推荐早期自己维护：

```text
Email
+
Password
+
Forgot Password
+
Email Verification
```

除非产品需求明确需要。

---

# 26. Passkey

长期很适合 Zisee：

```text
绑定账户
↓
创建 Passkey
↓
以后使用系统生物识别恢复/登录
```

优点：

- 无密码
- 抗钓鱼
- 用户体验简单

但不阻塞前期开发。

---

# 27. 服务端数据模型

推荐核心实体：

```text
Identity
Account
Device
Credential
Contact
Invite
Call
Block
```

---

# 28. Identity

```text
Identity {
    id
    displayName
    status
    createdAt
    updatedAt
}
```

status：

```text
ANONYMOUS
REGISTERED
DISABLED
DELETED
```

---

# 29. Account

```text
Account {
    id
    identityId
    provider
    providerSubject
    createdAt
}
```

例如：

```text
provider = google
providerSubject = OIDC sub
```

不要把 email 当作 provider 的唯一稳定 ID。

---

# 30. Device

```text
Device {
    id
    identityId
    platform
    publicKey
    pushToken
    lastSeenAt
    createdAt
    revokedAt
}
```

FCM token 应绑定：

```text
Device
```

而不是直接绑定 Identity。

---

# 31. Invite

```text
Invite {
    id
    creatorIdentityId
    tokenHash
    expiresAt
    maxUses
}
```

邀请链接最好使用独立随机 token。

不要直接把数据库 identityId 当成公开邀请凭据。

---

# 32. 身份状态

推荐：

```text
NoIdentity
↓
LocalOnly
↓
Anonymous
↓
Registered
```

客户端不要依赖大量 Boolean：

```text
isLoggedIn
isAnonymous
hasIdentity
hasToken
hasAccount
```

应该使用明确状态模型。

---

# 33. IdentityRepository

客户端建议从第一天就抽象：

```kotlin
interface IdentityRepository {
    val identity: Flow<Identity?>

    suspend fun create(
        displayName: String
    ): Identity

    suspend fun updateDisplayName(
        displayName: String
    )

    suspend fun refreshSession()

    suspend fun bindAccount(...)
}
```

这样后续从：

```text
Local-only
```

演进到：

```text
Backend + Account
```

UI 和通话模块无需大规模重写。

---

# 34. 模块边界

推荐：

```text
identity/
├── Identity
├── LocalIdentity
├── IdentityRepository
├── IdentityStore
└── IdentityState

auth/
├── Session
├── DeviceCredential
├── AccessToken
└── AccountBinding
```

RTC 不应该知道：

- Google
- Passkey
- Email

RTC 只应该拿到：

```text
identityId
displayName
accessToken
```

---

# 35. Signaling 安全

Signaling Server 必须根据：

```text
Access Token
```

解析当前 identityId。

禁止相信客户端自行传：

```json
{
  "identityId": "zid_other_user"
}
```

然后冒充其他人。

---

# 36. TURN

TURN credential：

```text
Authenticated Device
↓
Backend
↓
Short-lived TURN credential
```

不能把长期 TURN Secret 放入 APK。

---

# 37. Public Username

第一阶段不做。

未来如果真的需要通过搜索加好友，再新增：

```text
handle
```

例如：

```text
@jiuyun
```

此时：

```text
displayName
identityId
handle
```

是三个完全不同的字段。

---

# 38. displayName 规则

建议：

- 支持 Unicode
- 支持中文
- 支持日文
- 支持英文
- 允许重名
- 去掉首尾多余空白
- 禁止全空白
- 长度建议 1~32 grapheme clusters

不要简单按 Java/Kotlin UTF-16 code unit 数量粗暴截断多语言文字。

---

# 39. 头像

前期：

```text
不要求上传
```

使用：

```text
姓名首字符 / initials
```

即可。

头像上传以后再做。

---

# 40. 重置身份

“修改姓名”和“重置身份”必须完全不同。

修改姓名：

```text
identityId 不变
```

重置身份：

```text
生成新的 identityId
```

Reset Identity 必须明确警告：

> 这会创建一个新的 Zisee 身份，旧身份在未绑定账户时可能无法恢复。

---

# 41. Account Conflict

未来可能出现：

当前手机：

```text
Anonymous Identity A
```

登录 Google 后发现该 Google 已绑定：

```text
Identity B
```

不能静默覆盖。

必须进入：

```text
Identity Conflict
```

第一版建议不要自动 merge。

可以提供：

- 使用账户身份
- 取消
- 后续再设计迁移

身份 Merge 涉及联系人、历史、Block、邀请等复杂数据，应单独设计。

---

# 42. 隐私

身份服务端只保存实现功能需要的数据。

默认不保存：

- 音视频内容
- 屏幕画面
- AR 原始 Camera Frame
- Depth Frame
- 麦克风内容

---

# 43. MVP 范围

当前 M0/M1 只需要：

```text
Local Identity
```

实现：

1. 首次姓名输入
2. 生成 identityId
3. DataStore 保存
4. App 重启后恢复
5. Settings 修改姓名

这就足够。

---

# 44. Backend 接入阶段

随后加入：

```text
Anonymous Bootstrap
Device ID
Device Credential
Access Token
```

但用户仍然：

```text
无需注册
```

---

# 45. 正式账户阶段

再加入：

```text
Account Binding
Google
Passkey
Identity Recovery
```

---

# 46. 多设备阶段

最后：

```text
Multiple Devices
Device Management
Multi-device Push
```

---

# 47. 推荐开发顺序

```text
1. LocalIdentity
2. IdentityStore / DataStore
3. 首次姓名 Onboarding
4. IdentityRepository
5. 修改姓名
6. Anonymous Backend Bootstrap
7. Device Credential
8. Access Token
9. Invite / QR
10. Contact
11. Account Binding
12. Identity Recovery
13. Multi-device
```

---

# 48. 最终模型

长期：

```text
Account
   │
   │ proves ownership
   ▼
Identity
   │
   ├── Contacts
   ├── Call history
   ├── Settings
   └── Other user data
   │
   ▼
Devices
   │
   ├── Credential
   └── Push Token
```

当前前期则非常简单：

```text
Identity
   │
   ▼
Current App Installation
```

---

# 49. 核心结论

Zisee 的账户设计不是：

```text
现在完全没身份系统
以后再加登录
```

而应该是：

```text
现在先建立 Identity
以后再给 Identity 加 Account
```

首次体验保持：

```text
输入姓名
↓
立即使用
```

未来升级：

```text
现有 Identity
↓
绑定账户
↓
保留原数据和关系
```

这样既保持前期极低使用门槛，也避免未来账户系统接入时重构核心用户模型。

---

# Changelog

## 1.0.0 - 2026-09-08

- 建立 Zisee Identity First / Account Later 设计。
- 定义首次只输入姓名的 Local Identity。
- 定义稳定 identityId。
- 定义 Anonymous Backend Identity。
- 定义 Device Credential 与 Access Token。
- 定义未来 Account Binding。
- 定义多设备与身份恢复演进路线。

## 已实现：记住联系人和直接呼叫（2026-09-09）

接听邀请通话后，服务端事务保存双方 identityId 关系；改名后联系人查询展示当前姓名，关系不变。首页与通话入口显示最多 20 位联系人，可直接发起新的 ringing 通话，不再重复兑换邀请；仍须对方接听。用户移除关系会同时撤销双方直接呼叫资格，重新联系需再次邀请并接听。未接听／拒绝的邀请不建立联系人。

前台身份观察器负责登录、同步联系人和监听来电，进入实际通话前串行释放观察器会话，通话结束恢复观察。离开前台释放监听；尚无 FCM 后台来电或系统来电界面。联系人由服务器保存，重启 App 后重新加载；卸载导致身份丢失时不可凭姓名恢复关系。此前版本的已结束通话不会凭 ended 状态回填关系，需要升级后重新接听一次。
