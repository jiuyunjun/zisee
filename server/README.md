# Zisee server

Go 控制面：Phase B 服务端认证、邀请兑换、通话状态、认证 WebSocket 与 SDP 交换均已实现。存储可选 Firestore 或 PostgreSQL；Cloudflare TURN 凭据由服务端签发。已部署到 Cloud Run 东京区域。

## 存储后端

设置 `FIRESTORE_PROJECT_ID` 即使用 Firestore（Cloud Run 生产路径），否则回落到 `DATABASE_URL` 指向的 PostgreSQL（本地测试路径）。两者都实现同一个 `identity.Store` 接口。

Firestore 没有唯一约束、外键、CHECK 与级联删除，这些不变量全部在 `internal/firestore` 的事务里重建：每人一个有效邀请、每身份一台设备、每设备一个会话、caller≠callee、一通话至多 offer/answer 两条 SDP、通话结束时显式删除 SDP。复合索引见 `firestore.indexes.json`。

## TURN

设置 `CLOUDFLARE_TURN_TOKEN_ID` 与 `CLOUDFLARE_TURN_API_TOKEN` 后，`GET /v1/ice` 按会话签发短期（1 小时）中转凭据。API token 只留在服务端，客户端只拿到会自行过期的 username/credential。两个变量缺一即拒绝启动；都不设时端点只返回 STUN，通话退化为仅直连而不是失败。

## 运行

需要 Go 1.26.6+。用 PostgreSQL 时设置 DATABASE_URL 并先执行迁移：

```powershell
go run ./cmd/migrate
go run ./cmd/server
```

用 Firestore 时无需迁移（`cmd/migrate` 仅探活）。

PORT 默认 8080。迁移使用建表账户；运行时仅需业务表的 SELECT/INSERT/UPDATE/DELETE 权限。初始迁移幂等，通话表由版本 2 迁移管理，部署前需执行迁移并授予新表权限。healthz 检查进程，readyz 检查数据库及表，支持 SIGTERM 关闭。

## 协议

- [身份协议](../docs/protocols/IDENTITY_PROTOCOL.md)：公钥 bootstrap、一次性挑战、15 分钟令牌、本人资料、改名、退出。
- [信令会话](../docs/architecture/SIGNALING.md)：认证连接与心跳，尚无通话转发。
- [通话协议](../docs/protocols/CALL_PROTOCOL.md)：邀请、接听／拒绝／挂断、忙线、有效期及跨实例快照查询。
- [部署状态](../docs/operations/DEPLOYMENT.md)：zisee-app 的 API 已启用，尚无云数据库和服务。

## 验证

```powershell
go test ./...
go vet ./...
go build ./...
go run golang.org/x/vuln/cmd/govulncheck@v1.7.0 ./...
docker build -t zisee-server:dev .
```

Firestore 测试需要模拟器，未设 `FIRESTORE_EMULATOR_HOST` 时明确 skip，绝不对真实项目执行：

```powershell
docker run -d --name zisee-fs-emu -p 18085:8085 `
  gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators `
  gcloud emulators firestore start --host-port=0.0.0.0:8085 --project=zisee-test
$env:FIRESTORE_EMULATOR_HOST="127.0.0.1:18085"; go test ./internal/firestore/...
```

设置 TEST_DATABASE_URL 指向专用 PostgreSQL 后才执行 PostgreSQL integration 测试，否则明确 skip。测试创建随机 schema，结束删除该 schema，禁止使用生产库。Linux/CI 运行 go test -race -count=1 ./...；Windows race 需要 CGO 和 C 编译器。

镜像非 root，包含 server/migrate，上传清单排除环境文件和测试数据。CI 提供真实 PostgreSQL 并执行 race、vet、build、容器和漏洞检查。

## 依赖与边界

pgx/v5（MIT）、coder/websocket（ISC）仅用于服务端，不增加 APK 体积或权限。版本锁定在 go.mod/go.sum，已按漏洞扫描修复。

当前仅有单实例速率上限及每设备挑战数量限制。公网全局反滥用、跨实例实时消息投递、托管数据库及 IAM 配置后续完成，尚未公开部署注册接口。通话 accepted 表示同意接听，尚无媒体连接。

2026-09-08：10 项测试通过，Linux race、vet、build 通过，govulncheck 未发现漏洞；非 root 容器构建、迁移和 healthz/readyz 冒烟通过。远端 CI 与 Android Keystore 真机联调未执行。

2026-09-08 增量：新增 Firestore 存储（10 项模拟器测试，覆盖邀请唯一性、并发抢兑只成一通、单设备单会话、越权读写、SDP 双向隔离与结束时清理）与 TURN 签发（6 项测试，含凭据不外泄）。已部署 Cloud Run 东京区域，`/livez`、`/readyz`、`/v1/ice` 401 与启动日志 `turn_enabled` 均已核验。

**尚未验证**：真机对线上后端的完整通话，因此 TURN 中转路径与公网信令仍无实证。
