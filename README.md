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
- 拟人化伪装层：读/想/打字延迟、睡眠与离线块、四窗口配额、爆发冷却、分条、复读与群发检测、AI 味清洗
- OpenAI 兼容模型管理，可接入 DeepSeek、通义千问、Moonshot、OpenAI 等
- 模型 Provider 支持环境变量引导（`LLM_*`），首次启动自动建库并挂载，密钥加密存储
- 每个 Provider 支持主/备双 Key，401/402/403 立即切换，429/5xx 重试后切换
- 接入 DeepSeek 官方联网搜索（`/v1/tools/search`），按关键词启发式触发并注入提示词
- 关键词自动回复规则、敏感词过滤（改写 / 拦截）、会话级人工接管
- 提示词模板（会话级人格切换）与定时消息（到点自动转入投递队列）
- 图片 / 语音 / 视频 / 文件 / 链接消息：记录元数据，回复策略可选「只记录 / 固定话术 / 交给模型」
- 用量统计：按天收发量、token 消耗、平均耗时与模型分布，可选填单价后估算成本
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
- Spring Boot 通过 `spring.config.import=optional:file:./.env[.properties]` 直接加载仓库根目录的 `.env`，**因此 IDEA / `java -jar` 也能用，只要工作目录是仓库根目录**；已存在的系统环境变量优先级更高
- 变量留空 = 不注入，落回 `application.yml` / `vite.config.ts` 的内置默认值；数据库只需提供主机、端口、库名、账号、密码，JDBC 连接串由后端自动拼装，不需要手写

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

### 5.4 在 IntelliJ IDEA 里运行

可以，仓库已带运行配置 `.run/WeChatBot Backend.run.xml`，IDEA 打开项目后直接出现在右上角下拉框里。

前提：

1. **Project SDK 选 JDK 21**（`File → Project Structure → SDK`，本机是 `C:\Program Files\jdk-21.0.11`）；
2. **Maven 项目已导入**：`server/pom.xml` 作为 Maven 工程加载，模块名 `wechat-bot-server`；
3. **开启注解处理**（Lombok 需要）：`Settings → Build → Compiler → Annotation Processors → Enable annotation processing`；
4. **Working directory 必须是仓库根目录** `D:\wechat_bot` —— `.env` 是按相对路径 `./.env` 加载的，指到 `server/` 就读不到。

运行配置已经设好，**打开项目后点右上角绿色三角即可**，不需要再改任何东西：

- 主类 `com.example.wechatbot.WechatBotApplication`
- 工作目录 `$PROJECT_DIR$`（这样 `spring.config.import=optional:file:./.env` 能直接读到根目录 `.env`）
- 环境变量 `SERVER_PORT=18080`（与微信 `CallBackURL` 指向的端口一致）
- `-Dfile.encoding=UTF-8`

端口说明：`.env` 里的 `SERVER_PORT` 也改成了 `18080`，与 `scripts/run-backend.ps1`、`wechat-guard.ps1`、`write-wx-ini.ps1` 里的回调地址保持一致。**IDEA 与脚本二选一运行**，同一个端口不能同时被两个实例占用。

前端不需要额外构建步骤：`web/dist` 已存在时，后端会直接从 `file:web/dist/` 提供页面（`WebConfig` 里配置了 classpath 与文件目录两级回退），访问 `http://127.0.0.1:18080/` 即可。如果改过前端代码，跑一次 `pnpm --dir web build` 刷新 `web/dist`。

## 6. 配置允许的聊天

不能默认处理全部聊天。先登录微信，等待会话同步，然后通过 Java 管理 API 查询：

```powershell
$headers = @{ Authorization = "Bearer $env:ADMIN_TOKEN" }
Invoke-RestMethod http://localhost:8080/api/admin/wechat/sessions -Headers $headers
Invoke-RestMethod http://localhost:8080/api/admin/wechat/contacts -Headers $headers
```

**白名单在管理端「运行策略」里改，写完立即生效，不用重启、不用碰 `.env`**：

```text
运行策略 → 会话白名单：每行一个 wxid 或 @chatroom
          全量接收：白名单为空时是否接收所有私聊
          处理群聊消息：关闭则群消息直接忽略
```

对应接口：

```powershell
$h = @{ Authorization = "Bearer $env:ADMIN_TOKEN"; "Content-Type" = "application/json" }
Invoke-RestMethod http://localhost:8080/api/admin/settings -Headers $h
Invoke-RestMethod -Method Put http://localhost:8080/api/admin/settings/wechat -Headers $h `
  -Body (@{ groupsEnabled = $false; acceptAll = $true; allowedChats = @("wxid_friend") } | ConvertTo-Json)
```

`.env` 里的 `WECHAT_ALLOWED_CHATS` / `WECHAT_CALLBACK_ACCEPT_ALL` / `WECHAT_CALLBACK_GROUPS_ENABLED` 只是**首次启动的默认值**；一旦在管理端保存过，就以数据库为准（`GET /api/admin/settings` 的 `source` 字段会显示 `db` 还是 `env`）。

`WECHAT_ACCOUNT` 在官方预编译 DLL 下可以留空，后端会从 `/GetSelfProfile` 自动识别；覆盖层模式下建议显式填写。

### 6.1 回调模式（推荐：`IsLogin=0` 也能收消息）

官方预编译 DLL 在本机实测 `IsLogin` 恒为 0，`/QueryDB/*` 全部不可用，但 `/SendTextMsg` 可用，且 DLL 支持把收到的消息主动 POST 给 `CallBackURL`。因此推荐用回调模式收发：

```powershell
Start-Process "C:\Program Files\Tencent\Weixin\Weixin.exe" `
  -ArgumentList 'StartPort=30001','CallBackURL="http://127.0.0.1:8080/api/wechat/hook/callback"' `
  -WorkingDirectory "C:\Program Files\Tencent\Weixin"
```

```dotenv
WECHAT_SELF_WXID=wxid_xxxxxxxx      # 本账号 wxid，用于丢弃自己发出的消息
WECHAT_HOOK_CALLBACK_ENABLED=true   # 打开回调接收；同时关闭 DB 轮询，避免重复投递
WECHAT_CALLBACK_ACCEPT_ALL=true     # WECHAT_ALLOWED_CHATS 为空时接收全部会话
WECHAT_CALLBACK_GROUPS_ENABLED=false
```

回调报文形如：

```json
{"event_type":1001,"msgid":7856394399714252158,"type":1,"timestamp":1793267914,
 "wxid":"wxid_friend","sender":"wxid_friend","roomid":"","content":"在吗"}
```

`roomid` 非空表示群消息，此时会话 ID 取 `roomid`、发送者取 `sender`。`event_type` 只处理 `1001`（消息），`type` 目前只处理 `1`（文本）。回调端点只接受回环来源，`WECHAT_CALLBACK_REQUIRE_LOOPBACK=false` 可关闭该校验。

### 6.2 进程守护

微信必须在交互式用户会话里运行，所以用“登录时触发的计划任务”而不是 Windows 服务：

```powershell
# 注册（登录时启动，隐藏窗口）
powershell -ExecutionPolicy Bypass -File scripts\install-wechat-guard-task.ps1 `
  -Port 30001 -CallbackUrl "http://127.0.0.1:8080/api/wechat/hook/callback"

# 卸载
powershell -ExecutionPolicy Bypass -File scripts\install-wechat-guard-task.ps1 -Uninstall
```

守护每 10 秒检查一次：进程是否存在、`30001` 是否可达、`Weixin.exe` 版本是否为 `4.1.10.27`、`version.dll` 哈希是否被覆盖、`wx.ini` 是否仍指向当前回调地址。进程消失则拉起；连续 3 次探测失败则重启微信（每小时最多 5 次）；**版本不匹配时只告警不重启**，避免把可用状态弄坏。

状态写入 `logs/guard-status.json`，并汇总在 `GET /api/admin/wechat/hook/diagnostics`：Hook 可达性、`IsLogin`、`hWeixin` 实例指纹、回调计数（total/accepted/ignored）与最近一条事件。

若希望手动双击微信也能收到回调，可在安装目录写 `wx.ini`（需一次管理员权限）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\write-wx-ini.ps1 `
  -CallbackUrl "http://127.0.0.1:8080/api/wechat/hook/callback"
```

```ini
[wx]
recv_url=http://127.0.0.1:8080/api/wechat/hook/callback
```

守护检测到 `wx.ini` 存在时只传 `StartPort`，回调地址交给 ini；不存在时用命令行参数兜底。

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
| `WECHAT_SELF_WXID` | 空 | 本账号 wxid，用于丢弃自己发出的消息 |
| `WECHAT_HOOK_CALLBACK_ENABLED` | `false` | 打开 `CallBackURL` 回调接收（此时 DB 轮询自动停用） |
| `WECHAT_CALLBACK_ACCEPT_ALL` | `true` | 白名单为空时是否接收全部会话 |
| `WECHAT_CALLBACK_REQUIRE_LOOPBACK` | `true` | 回调端点是否只接受 127.0.0.1/::1 |
| `WECHAT_CALLBACK_GROUPS_ENABLED` | `false` | 是否处理群消息回调 |
| `WECHAT_HOOK_SKIP_HISTORY` | `true` | 首次启动是否跳过历史消息 |
| `WECHAT_HOOK_POLL_INTERVAL_MS` | `2000` | 收件轮询间隔 |
| `WECHAT_HOOK_OUTBOX_INTERVAL_MS` | `1000` | 发件调度间隔 |
| `WECHAT_HOOK_BATCH_SIZE` | `100` | 单次读取/发送批量 |
| `WECHAT_HOOK_TOKEN` | 空 | 仅自定义鉴权覆盖层需要 |

模型 Provider（启动时写入数据库，无需打开管理端手填）：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `LLM_BOOTSTRAP_ENABLED` | `true` | 关闭后只使用管理端维护的 provider |
| `LLM_BOOTSTRAP_OVERWRITE` | `true` | 每次启动用环境变量覆盖同名 provider；`false` 时只补空字段 |
| `LLM_BOOTSTRAP_ASSIGN_TO_BOT` | `true` | 自动挂到还没有模型的机器人上 |
| `LLM_NAME` | 空 | provider 名称；留空按域名生成 `env-api.deepseek.com` |
| `LLM_BASE_URL` | 空 | 只填域名会自动补 `/v1`，不要带 `/chat/completions` |
| `LLM_API_KEY` | 空 | 落库前用 `APP_SECRET_KEY` 加密，日志只打尾 4 位 |
| `LLM_BACKUP_API_KEY` | 空 | 主 Key 失效时自动切换的备用 Key，同样加密落库 |
| `LLM_MODEL` | 空 | 例如 `deepseek-chat`、`qwen-plus` |
| `LLM_PRIORITY` / `LLM_TIMEOUT_SECONDS` | `10` / `60` | 优先级数字越小越先用；超时秒数 5~300 |
| `LLM_FALLBACK_*` | 空 | 可选备用通道，字段同上，默认 priority `20` |
| `LLM_FALLBACK_BACKUP_API_KEY` | 空 | 备用通道自己的备用 Key |

`LLM_BASE_URL` 与 `LLM_MODEL` 任一为空即跳过引导，因此留空不会破坏管理端配置。启动后可核对：

```powershell
$h = @{ Authorization = "Bearer $env:ADMIN_TOKEN" }
Invoke-RestMethod http://localhost:8080/api/admin/providers/bootstrap -Headers $h
Invoke-RestMethod http://localhost:8080/api/admin/providers -Headers $h
Invoke-RestMethod -Method Post http://localhost:8080/api/admin/providers/<id>/test -Headers $h
Invoke-RestMethod -Method Post "http://localhost:8080/api/admin/providers/<id>/search?q=今天新闻" -Headers $h
```

`/providers/bootstrap` 返回本次启动的动作（`created` / `updated` / `unchanged` / `skipped`）、掩码后的 Base URL 和告警，密钥永不回显。

### 7.1 双 Key 故障切换

每个 Provider 可存两把 Key：`api_key_encrypted`（主）+ `api_key_backup_encrypted`（备）。调用顺序与切换规则：

| 情况 | 行为 |
|---|---|
| 主 Key 返回 `401/402/403`（无效 / 欠费 / 无权限） | 立即换备用 Key，不重试主 Key |
| 主 Key 返回 `429/5xx` 或连接失败 | 同一 Key 先重试一次，仍失败再换备用 Key |
| 主备都失败 | 抛错，由 `LlmGateway` 按 `priority` 继续尝试下一个 Provider |

管理端字段：`apiKey`（写入即覆盖）、`backupApiKey`（传空串表示清除）、`hasBackupApiKey` / `backupApiKeyHint`（只读回显）。

### 7.2 DeepSeek 官方联网搜索

搜索走官方接口 `POST {LLM_SEARCH_BASE_URL}/v1/tools/search`，与对话的 Base URL 解耦（中转站通常不代理搜索），因此需要一把**官方** Key：

```dotenv
LLM_SEARCH_ENABLED=true
LLM_SEARCH_MODE=HEURISTIC        # OFF / HEURISTIC / ALWAYS
LLM_SEARCH_BASE_URL=https://api.deepseek.com
LLM_SEARCH_API_KEY=sk-官方key
LLM_SEARCH_BACKUP_API_KEY=sk-官方备用key
LLM_SEARCH_COUNT=5               # 请求条数 1~20
LLM_SEARCH_NEED_CONTENT=false    # true 会返回正文，费用更高
LLM_SEARCH_MAX_RESULTS=4         # 注入提示词的最大条数
LLM_SEARCH_MAX_CONTENT_CHARS=1200
LLM_SEARCH_TIMEOUT_SECONDS=20
```

- 搜索 Base URL、搜索 Key、备用 Key 与聊天模型 Provider **完全独立**，不会复用聊天模型的 Key。\n- 可以在管理端「运行策略 → 联网搜索」直接修改，保存后立即生效并加密落库；密钥只显示末四位。
- `HEURISTIC` 模式只在命中时效词（新闻/天气/股价/比分/查一下…）或“弱时效词 + 问号”时才搜，纯寒暄不搜，省钱。
- 搜索结果以一条 system 消息注入到对话历史之前，并附“以结果为准、不要编造、不要直接贴链接”的约束。
- 搜索失败（超时、额度、无结果）一律降级为不联网回复，不影响正常聊天。

### 7.3 运行策略（改完立即生效）

策略存在 `app_setting` 表里，写入后内存缓存同步更新，**不需要重启**；没有记录时回落到环境变量默认值。

| 策略项 | 作用 | 覆盖的环境变量 |
|---|---|---|
| `wechat.allowedChats` | 允许回复的会话（私聊 wxid / 群 `@chatroom`） | `WECHAT_ALLOWED_CHATS` |
| `wechat.acceptAll` | 白名单为空时是否接收全部私聊 | `WECHAT_CALLBACK_ACCEPT_ALL` |
| `wechat.groupsEnabled` | 是否处理群消息 | `WECHAT_CALLBACK_GROUPS_ENABLED` |
| `llm.fallbackEnabled` | 是否启用备用链路（多 Provider 回退）。关闭后只走主 Provider 的主/备双 Key | `LLM_FALLBACK_ENABLED` |
| `media.enabled` / `media.replyMode` / `media.ackText` | 媒体消息开关与回复策略（`ignore` / `ack` / `llm`） | — |

### 7.4 引用消息与图片识别

- **引用消息**：`type=49` 且带 `<refermsg>` 时按文本处理，`content` 是本次说的话，`quote_author`/`quote_text` 记录被引用的发送者与内容；被引用的对象可能是文本、图片、语音、表情、文件或链接，统一渲染成占位文本（`[图片]`、`[语音 4″]`、`[文件] 报价单.pdf` …）。送给模型时当前消息会拼成 `[引用 张三：原文]` + 本次说的话。
- **图片识别**：`type=3` 的图片会真的交给模型看——按 `md5` 在微信数据目录定位本地 `.dat`，先走 Hook 的 `/Decode_Pic`，失败再用 `aeskey` 本地 AES 解密，缩放到长边 1024 内、转 JPEG 后以多段 content（`text` + `image_url`）发送。任一步失败都退化为 `[图片]` 占位文本，不影响回复；上游拒绝带图请求时自动改纯文本重试。
- 相关环境变量：`LLM_VISION_ENABLED`、`LLM_VISION_MAX_EDGE`、`LLM_VISION_MAX_BYTES`、`LLM_VISION_CACHE_DIR`、`LLM_VISION_DECODE_WAIT_MS`、`WECHAT_IMAGE_ROOT`，详见 [docs/api.md](docs/api.md#图片识别)。

```powershell
# 只保留主链路 + 双 Key 兜底，不再切换下一个 Provider
Invoke-RestMethod -Method Put http://localhost:8080/api/admin/settings/llm -Headers $h `
  -Body (@{ fallbackEnabled = $false } | ConvertTo-Json)
```

`GET /api/admin/settings` 返回每项的 `value` 与 `source`（`db` 表示管理端改过，`env` 表示仍在用默认值）。想恢复默认值，删掉 `app_setting` 里对应的行并重启即可。

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

## 11. 伪装层（反识别）

个人微信风控主要看**行为**：频率、时间分布、内容相似度、会话关系。因此回复不再"生成即发送"，
而是先过一层伪装：洗文风 → 分条 → 看是否在线 → 排队配给 → 按打字速度延迟。

```text
模型回复 → StyleNormalizer（去 Markdown / 结构词 / AI 自称）
        → ReplyComposer（按句切成 1~3 条）
        → DisguiseClock（睡眠 / 随机离线块）
        → QuotaGuard（分钟 / 10 分钟 / 小时 / 天 / 全局）
        → PacingModel（读 + 想 + 打字 + 昼夜节律）
        → outbox_message（每条独立 nextAttemptAt，同会话串行发送）
```

默认行为：

| 项 | 默认值 |
|---|---|
| 睡眠窗口 | 00:30–07:30，期间消息推迟到醒来 |
| 每次分条 | 最多 3 条，单条约 90 字 |
| 单会话配额 | 3/分钟、8/10 分钟、20/小时、120/天 |
| 账号级配额 | 40/小时、220/天 |
| 回复延迟 | 约 2–15 秒，8% 概率长停顿，2% 概率离开数分钟 |
| 复读检测 | 同会话相似度 > 0.62 触发换说法重答 |

调参与观测：

```powershell
$h = @{ Authorization = "Bearer $env:ADMIN_TOKEN" }
Invoke-RestMethod http://localhost:8080/api/admin/disguise -Headers $h

$body = @{ chatId = "wxid_demo"; chatType = "PRIVATE"; text = "**好的**！首先，我为您整理如下" } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/admin/disguise/preview -Method Post -Headers $h -ContentType "application/json" -Body $body
```

无控制台 + 随机启动抖动：

```powershell
.\scripts\start-stealth.ps1          # 后台启动，随机延迟 0~90 秒
.\scripts\start-stealth.ps1 -Stop    # 停止
```

完整策略、参数基线、新号 7 天曲线和残余风险见 [docs/disguise.md](docs/disguise.md)。





