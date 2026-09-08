# Zisee server

Go 控制面：已实现 Phase B 服务端认证、PostgreSQL 持久化与认证 WebSocket 心跳。Android、邀请、SDP/ICE、TURN 尚未接入，尚未部署 Cloud Run。

## 运行

需要 Go 1.26.6+ 与 PostgreSQL 17。设置 DATABASE_URL 环境变量，在 server/ 执行：

```powershell
go run ./cmd/migrate
go run ./cmd/server
```

PORT 默认 8080。迁移使用建表账户；运行时仅需表的 SELECT/INSERT/UPDATE/DELETE 权限。初始迁移幂等，后续结构变化需版本化迁移。healthz 检查进程，readyz 检查数据库及表，支持 SIGTERM 关闭。

## 协议

- [身份协议](../docs/protocols/IDENTITY_PROTOCOL.md)：公钥 bootstrap、一次性挑战、15 分钟令牌、本人资料、改名、退出。
- [信令会话](../docs/architecture/SIGNALING.md)：认证连接与心跳，尚无通话转发。
- [部署状态](../docs/operations/DEPLOYMENT.md)：zisee-app 的 API 已启用，尚无云数据库和服务。

## 验证

```powershell
go test ./...
go vet ./...
go build ./...
go run golang.org/x/vuln/cmd/govulncheck@v1.7.0 ./...
docker build -t zisee-server:dev .
```

设置 TEST_DATABASE_URL 指向专用 PostgreSQL 后才执行 integration 测试，否则明确 skip。测试创建随机 schema，结束删除该 schema，禁止使用生产库。Linux/CI 运行 go test -race -count=1 ./...；Windows race 需要 CGO 和 C 编译器。

镜像非 root，包含 server/migrate，上传清单排除环境文件和测试数据。CI 提供真实 PostgreSQL 并执行 race、vet、build、容器和漏洞检查。

## 依赖与边界

pgx/v5（MIT）、coder/websocket（ISC）仅用于服务端，不增加 APK 体积或权限。版本锁定在 go.mod/go.sum，已按漏洞扫描修复。

当前仅有单实例速率上限及每设备挑战数量限制。公网全局反滥用、邀请授权、跨实例通话投递、托管数据库及 IAM 配置后续完成，尚未公开部署注册接口。

2026-09-08：10 项测试通过，Linux race、vet、build 通过，govulncheck 未发现漏洞；非 root 容器构建、迁移和 healthz/readyz 冒烟通过。远端 CI 与 Android Keystore 真机联调未执行。
