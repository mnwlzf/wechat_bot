# 微信 AI 机器人（Java 单体）

基于 **Java 21 + Spring Boot 3 + PostgreSQL 16 + Redis 7 + 微信 4.1.10.27 原生 Hook** 的单体应用。Vue 3 管理端构建后由 Spring Boot 直接托管；微信消息轮询、AI 编排、发件箱发送全部在 Java 进程内完成。

> 个人微信没有面向普通开发者的稳定公开机器人协议。Hook 方案受微信版本、风控、服务条款和账号风险影响。请仅在明确授权的环境中使用，并优先评估企业微信、公众号或微信客服等官方能力。

## 1. 当前架构

```text
WeChat 4.1.10.27
  └─ 预编译 version.dll（固定 SHA-256）
       └─ 本机 HTTP :30001
            └─ Spring Boot 单体 :8080
                 ├─ WeChatHookInboxPoller  读取 message_*.db
                 ├─ ChatOrchestrator       OpenAI 兼容模型调用
                 ├─ WeChatHookOutboxDispatcher 直接调用 /SendTextMsg
                 ├─ PostgreSQL             消息、配置、outbox、Hook 游标
                 ├─ Redis                  事件去重、限流
                 └─ Vue 3 静态资源         由 Spring Boot 托管
```

运行期只有 Java 进程和 PostgreSQL/Redis。Node.js 仅在修改前端时需要，用于 `npm run build`。

## 2. 已实现能力

- Java 直接对接 Hook，不需要独立 Windows Agent 进程
- 自动兼容两种本地 Hook 协议：
  - 官方 `4.1.10.27` 预编译 DLL：`/QueryDB/*`、`/GetSelfProfile`、`/SendTextMsg`
  - 带鉴权的 `WeChatAgent` 覆盖层：`/WeChatAgent/health`、`/query`、`/databases`
- 首次启动默认跳过历史消息，增量游标持久化到 PostgreSQL
- 只读发现 `message_*.db`，按 `Msg_<md5(chatId)>` 读取文本消息
- 群聊唤醒词、私聊/群聊开关、Redis 去重与限流
- PostgreSQL outbox 可靠发送，失败指数退避，Java 调度器直接确认
- OpenAI 兼容模型管理，可接入 DeepSeek、通义千问、Moonshot、OpenAI 等
- Vue 3 + Element Plus 管理端，生产环境打包进 Spring Boot JAR
- 管理端 Hook 状态、会话和联系人排查 API

## 3. 目录

```text
server/                 Spring Boot 单体
web/                    Vue 3 管理端（构建到 web/dist）
windows-agent/prebuilt/ 预编译 version.dll + 固定哈希清单
windows-agent/          安装脚本、探针、历史源码和可选的测试工具
vendor/                 上游 Hook 源码归档
scripts/                Windows 启动脚本
docs/                   架构与 API 文档
```

## 4. 环境准备

必需：

- Windows 10/11 x64
- JDK 21
- Maven 3.9+
- PostgreSQL 16 和 Redis 7（推荐 Docker Compose）
- 合法的微信 `4.1.10.27` 客户端，放在隔离目录
- `windows-agent/prebuilt/version.dll`

预编译 DLL 信息：

| 项 | 值 |
|---|---|
| 来源 | `aixed/WeChat-Hook` release `v411027_` |
| 目标微信 | `4.1.10.27` |
| SHA-256 | `23948e7d933ff40735ddc0cc516641fd9f935d67ffdec476bb8db61b79940f29` |
| Authenticode | 未签名 |

该 DLL 不是腾讯签名文件，也不是微软签名文件。这里采用“固定上游发布版本 + SHA-256 校验”的信任模型；安装前必须核对哈希。完整清单见 `windows-agent/prebuilt/manifest.json`。上游仓库未声明明确许可证，对外分发或商用前必须单独确认授权。

## 5. 快速启动

### 5.1 配置

项目所有通过环境变量注入的配置项都集中在**根目录唯一的一份 `.env`**：Docker Compose、Spring Boot 后端和 Vite 前端共用它，不再维护 `web/.env`。

```powershell
Copy-Item .env.example .env
# 按需修改 PostgreSQL / Redis / Token / 微信 Hook 各项即可
```

读取方式：

- `scripts\*.ps1` 启动前会调用 `scripts\load-env.ps1` 把 `.env` 注入当前进程环境
- `web\vite.config.ts` 通过 `envDir` 指向仓库根目录，直接读取同一份 `.env`（只有 `VITE_` 前缀会进入浏览器端代码）
- 变量留空 = 不注入，落回 `application.yml` / `vite.config.ts` 的内置默认值；数据库只需提供主机、端口、库名、账号、密码，JDBC 连接串由后端自动拼装，不需要手写
- 直接用 `mvn`、IDE 或 `java -jar` 启动时不会自动加载 `.env`，可先执行 `.\scripts\load-env.ps1`，或在 IDE 运行配置中导入这些变量

官方预编译 DLL 没有 Bearer Token 鉴权。必须保证 `30001` 只允许本机访问，例如安装时执行：

```powershell
.\windows-agent\prepare-prebuilt-hook.ps1
.\windows-agent\install-hook.ps1 `
  -WeChatDirectory "D:\WeChat-4.1.10.27" `
  -ConfigureLoopbackFirewall
```

脚本会校验微信版本、腾讯签名、DLL 哈希，备份旧的 `version.dll`，再使用 `StartPort=30001` 启动微信。

### 5.2 启动基础设施和后端

```powershell
.\scripts\start-infra.ps1
.\scripts\start-server.ps1
```

生产静态资源已经由 Spring Boot 托管，访问：

```text
http://localhost:8080
```

### 5.3 构建单体 JAR

首次修改前端后：

```powershell
cd web
npm ci
npm run build
cd ..
mvn -f server/pom.xml clean package
java -jar server/target/wechat-bot-server-0.1.0.jar
```

Maven 会把 `web/dist` 复制进 JAR 的 `static/` 目录。只修改后端时可以跳过前端构建，直接执行 Maven 打包。

## 6. 配置允许的聊天

不能默认处理全部聊天。先登录微信，等待会话同步，然后通过 Java 管理 API 查询：

```powershell
$headers = @{ Authorization = "Bearer $env:ADMIN_TOKEN" }
Invoke-RestMethod http://localhost:8080/api/admin/wechat/sessions -Headers $headers
Invoke-RestMethod http://localhost:8080/api/admin/wechat/contacts -Headers $headers
```

把需要处理的 `wxid` 或 `@chatroom` ID 写入 `.env`：

```dotenv
WECHAT_ACCOUNT=
WECHAT_ALLOWED_CHATS=wxid_friend,123456@chatroom
WECHAT_HOOK_SKIP_HISTORY=true
```

修改后重启 Spring Boot。`WECHAT_ACCOUNT` 在官方预编译 DLL 下可以留空，后端会从 `/GetSelfProfile` 自动识别；覆盖层模式下建议显式填写。

## 7. 核心配置

全部变量都在根目录 `.env` 中，完整清单与注释见 [.env.example](.env.example)。

基础设施与服务：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `POSTGRES_HOST` / `POSTGRES_PORT` | `127.0.0.1` / `5432` | 数据库主机与端口；后端据此自动拼装 JDBC 连接串，连远程库只改 IP |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `wechat_bot` / `wechat` / - | 库名与账号密码，docker-compose 与后端共用 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_USERNAME` / `REDIS_PASSWORD` / `REDIS_DATABASE` | `localhost` / `6379` / 空 / - / `0` | Redis 连接；`REDIS_USERNAME` 对应 Redis 6+ ACL 账号（留空即只用密码认证），`REDIS_DATABASE` 为库号 |
| `SERVER_PORT` | `8080` | 后端端口 |
| `ADMIN_TOKEN` / `AGENT_TOKEN` | 开发默认值 | 管理端与 Agent 鉴权 |
| `APP_SECRET_KEY` | 开发默认值 | 模型密钥加密，上线必须替换 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | 跨域白名单 |
| `VITE_API_BASE_URL` | 空 | 前端接口基地址，空 = 走 Vite 代理 |
| `VITE_DEV_PORT` | `5173` | Vite 开发服务器端口 |
| `VITE_PROXY_TARGET` | `http://127.0.0.1:${SERVER_PORT}` | Vite 代理转发的后端地址 |

微信 Hook：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `WECHAT_HOOK_ENABLED` | `true` | 是否启用 Java 直接收发 |
| `WECHAT_HOOK_OUTBOX_ENABLED` | `true` | 是否由 Java 直接消费 outbox |
| `WECHAT_HOOK_URL` | `http://127.0.0.1:30001` | 仅允许回环 HTTP 地址 |
| `WECHAT_ACCOUNT` | 空 | 账号目录或 wxid；官方 DLL 可自动识别 |
| `WECHAT_ALLOWED_CHATS` | 空 | 逗号分隔的允许会话 ID |
| `WECHAT_HOOK_SKIP_HISTORY` | `true` | 首次启动是否跳过历史消息 |
| `WECHAT_HOOK_POLL_INTERVAL_MS` | `2000` | 收件轮询间隔 |
| `WECHAT_HOOK_OUTBOX_INTERVAL_MS` | `1000` | 发件调度间隔 |
| `WECHAT_HOOK_BATCH_SIZE` | `100` | 单次读取/发送批量 |
| `WECHAT_HOOK_TOKEN` | 空 | 仅自定义鉴权覆盖层需要 |

## 8. Hook 状态排查

```powershell
$headers = @{ Authorization = "Bearer $env:ADMIN_TOKEN" }
Invoke-RestMethod http://localhost:8080/api/admin/wechat/hook -Headers $headers
```

返回 `connected=true`、`version=4.1.10.27` 即表示 Hook、登录状态和原生查询均可用。接口说明见 [docs/api.md](docs/api.md)，架构细节见 [docs/architecture.md](docs/architecture.md)。

## 9. 测试

```powershell
mvn -f server/pom.xml test
```

测试覆盖 JPA 安全、AI 编排、Redis 降级、Hook 预编译协议回退、历史跳过和 PostgreSQL 游标推进。

## 10. 上线前检查

- 替换所有开发默认 Token 和密钥
- 确保 `30001` 对局域网和外网不可达
- 使用受控的 `4.1.10.27` 微信客户端和固定哈希 DLL
- 为 PostgreSQL/Redis 配置备份、持久化和访问控制
- 增加账号人工接管、内容审核、审计导出和保留期限
- 对个人微信 Hook 方案完成合规与账号风险评估


