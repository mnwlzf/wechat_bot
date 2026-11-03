# 架构与技术选型

## 1. 目标与边界

目标是在 Windows 单机运行一个可管理、可审计、可替换模型的微信 AI 机器人。业务逻辑全部收敛在 Spring Boot 单体中，微信客户端只作为原生 Hook 的运行载体。

必须明确：个人微信没有面向普通开发者的稳定公开机器人协议。Hook 方案受微信版本、风控和服务条款限制。本文档描述的是受控环境下的工程实现，不构成官方接入或合规背书。

## 2. 技术选型

| 领域 | 选择 | 说明 |
|---|---|---|
| 语言 | Java 21 | LTS，支持虚拟线程和现代语言特性 |
| 后端 | Spring Boot 3.4 / Spring MVC | 单体和模块化边界清晰 |
| 数据库 | PostgreSQL 16 | 配置、消息审计、outbox、Hook 游标 |
| 迁移 | Flyway | 数据库结构版本化 |
| ORM | Spring Data JPA | 事务和仓储模式 |
| 缓存/协调 | Redis 7 | 事件去重、会话级限流 |
| 管理端 | Vue 3 + TypeScript + Element Plus | 构建后由 Spring Boot 托管 |
| AI 协议 | OpenAI Chat Completions 兼容 | DeepSeek、Qwen、Moonshot、OpenAI 等 |
| 微信接入 | 预编译 `version.dll` + 本机 HTTP | 不要求 Java 进程注入 DLL |
| 构建 | Maven + Vite | 运行期只有 Java 和基础设施 |

`windows-agent/` 只包含原生 Hook 资产、安装脚本和 C++ 源码，不需要额外的桥接运行时。

## 3. 部署拓扑

```text
┌────────────────────── Windows 11 x64 ────────────────────────┐
│                                                              │
│  WeChat 4.1.10.27                                            │
│    └─ prebuilt version.dll                                   │
│         └─ HTTP 30001（应仅允许本机访问）                     │
│                                                              │
│  Spring Boot :8080                                           │
│    ├─ WeChatHookClient       兼容官方/覆盖层协议              │
│    ├─ InboxPoller            发现数据库、读取增量消息          │
│    ├─ ChatOrchestrator       AI 上下文与模型调用               │
│    ├─ OutboxDispatcher       直接发送并写回执                   │
│    ├─ PostgreSQL             审计、配置、游标、outbox           │
│    ├─ Redis                  去重、限流                         │
│    └─ Vue 3 静态资源         同一 JAR 内的 SPA                  │
└──────────────────────────────────────────────────────────────┘
```

管理端开发时可使用 Vite `:5173`，生产构建后不再需要 Node 或 Nginx。

## 4. 微信 Hook 协议

后端自动探测两种协议：

### 4.1 官方预编译 DLL（默认）

已包含的 `windows-agent/prebuilt/version.dll` 来自 `aixed/WeChat-Hook` 的 `v411027_` release，固定 SHA-256：

```text
23948e7d933ff40735ddc0cc516641fd9f935d67ffdec476bb8db61b79940f29
```

该文件没有 Authenticode 签名，安装采用固定哈希信任模型。其接口为：

- `GET /QueryDB/status`
- `POST /QueryDB/GetAllDBName`
- `POST /QueryDB/execute`
- `POST /GetSelfProfile`
- `POST /SendTextMsg`

官方 DLL 不提供 Bearer Token 鉴权，且二进制内部绑定 `0.0.0.0`。因此 Windows 防火墙必须阻止局域网访问 `30001`；安装脚本可创建仅允许 `127.0.0.1` / `::1` 的允许规则，但仍应检查防火墙配置。

### 4.2 WeChatAgent 覆盖层

自定义覆盖层支持：

- `GET /WeChatAgent/health`
- `GET /WeChatAgent/databases`
- `GET /WeChatAgent/self`
- `POST /WeChatAgent/query`
- `POST /SendTextMsg`

该模式支持 64 位 Bearer Token 和 `X-WeChatAgent-Account` 账号头。Java 会先尝试该协议，收到 404 后自动回退到官方预编译协议。

## 5. 入站消息

每个会话在 `message_*.db` 中对应：

```text
Msg_<md5(chatId)>
```

Java 轮询流程：

1. 校验 Hook 登录状态、微信版本和原生查询能力
2. 每 30 秒刷新已打开的 `message_*.db` / `contact.db`
3. 根据 `WECHAT_ALLOWED_CHATS` 查找会话表
4. 首次启动时，如启用 `skip-history`，记录当前最大锚点并跳过历史
5. 此后按 `local_id`、`server_id` 或 `create_time` 增量读取
6. 只处理 `local_type=1` 且 `typeof(message_content)='text'` 的文本
7. 私聊使用联系人 wxid；群聊通过 `Name2Id` 或消息前缀还原发送者
8. 调用 `InboxService`，完成策略校验、Redis 去重、限流、落库和 AI 异步编排
9. 每处理一条消息后推进 PostgreSQL 游标

游标表为 `wechat_hook_cursor`，唯一键是 `(account, chat_id)`。进程崩溃重启后不会重新读取已提交游标之前的数据。

## 6. 出站消息

`ChatOrchestrator` 将模型回复写入 `outbox_message`。`WeChatHookOutboxDispatcher` 每秒领取一批 `PENDING` 消息：

1. 在事务中租约并递增尝试次数
2. 调用 Hook `/SendTextMsg`
3. 成功后标记 `SENT`
4. 失败后记录错误；未超过 5 次时按 10s、20s、40s、80s 退避重试

旧版 `/api/agent/outbox` 仍保留，供外部调试或迁移期驱动使用，但默认生产链路不再依赖它。

### 6.1 伪装层

`ChatOrchestrator` 不再把模型输出直接写 outbox，而是先经过 `disguise` 包：

1. `StyleNormalizer` 去掉 Markdown、结构词、AI 自称和客套结尾
2. `ReplyComposer` 按句切成 1~3 条
3. `DisguiseClock` 判断是否处于睡眠/离线窗口，必要时整体顺延
4. `QuotaGuard` 按分钟/10 分钟/小时/天与全局配额排队
5. `PacingModel` 按“读 + 想 + 打字 + 昼夜节律”计算延迟
6. **第一条延迟封顶**：配额排队算出的时间最多推到 `app.disguise.max-first-delay-ms`（默认 15 秒），
   超出就压回来并在排期日志里注明「配额排队已压到上限」；睡眠/离线块在封顶之后仍然生效
7. 每条气泡写一行 `outbox_message`，各自带 `next_attempt_at`

`OutboxService.lease` 保证同一会话同一时刻只有一条在途消息，分条不会被并发发出。
完整策略见 [disguise.md](disguise.md)。

### 6.2 模型 Provider 引导

`LlmProviderBootstrap` 在 bean 初始化阶段执行（`InitializingBean`，早于 Web 端口开放，且晚于 Flyway）：

1. 读取 `app.llm.bootstrap.primary` / `fallback` 两个槽位
2. 按 `name` 做幂等 upsert；`overwrite=true` 时以环境变量为准
3. API Key 用 `SecretCipher`（AES-GCM）加密后落库，日志只输出尾 4 位
4. `assign-to-bot=true` 时，把还没有模型的机器人挂到 primary 上
5. 结果通过 `GET /api/admin/providers/bootstrap` 暴露，密钥永不回显

`LlmGateway` 仍然按 `priority` 升序尝试所有启用 provider，primary 失败会自动回退到 fallback。
Base URL 只填域名时客户端自动补 `/v1`，429/5xx 单次重试。

## 7. 数据模型

| 表 | 作用 |
|---|---|
| `model_provider` | OpenAI 兼容模型配置，API Key AES-GCM 加密 |
| `bot_config` | 机器人策略、唤醒词、上下文和限流 |
| `conversation` | 会话索引和最后一条消息；`prompt_template_id` 记录该会话/群单独指定的人设 |
| `prompt_template` | 人设（系统提示词）；`is_default` 标记全局默认，`builtin_key` 标记内置人设 |
| `chat_memory` | 会话共享长期记忆：`SUMMARY` 滚动摘要 + `NOTE` 人工要点 |
| `message_log` | 入站/出站消息审计 |
| `outbox_message` | 可靠回复发送队列 |
| `scheduled_message` | 定时消息；`repeat_rule` 重复规则、`split_lines` 分条发送 |
| `send_policy` | 发信策略；`chat_id` 为空表示全局默认，其余为按群覆盖 |
| `reply_policy` | 回复策略（主动 / 随机 / 唤醒）；`chat_id` 为空表示全局默认 |
| `wechat_hook_cursor` | 按账号和会话持久化增量游标 |

Redis 只承担可重建的协调状态：事件去重和分钟级限流。核心消息、配置和游标都在 PostgreSQL。

### 7.1 上下文拼装顺序

`ChatOrchestrator.buildContext` 按固定顺序拼系统消息，顺序变了会直接影响模型行为：

1. **人设**：会话绑定 → 全局默认人设 → 机器人 `systemPrompt`（三选一）
2. **共享记忆**：该会话的滚动摘要 + 启用的要点（群聊里所有成员共用）
3. **文风约束**：伪装层的 `promptSuffix`
4. **按需提示**：引用回复说明、语音/图片边界说明、联网搜索结果
5. **最近 N 条对话**（`bot_config.context_window`），群聊入站消息带 `[发送者]` 前缀

记忆只补「窗口之外」的历史，所以第 2 步和第 5 步不会重复同一段对话。

### 7.2 发信策略的合并

`SendPolicyService.forChat(chatId)` 每次回复发出去之前解析一次，返回一份只读快照：

```text
DisguiseProperties(环境变量) → send_policy(chat_id IS NULL) → send_policy(chat_id = 当前会话)
```

- 逐字段覆盖：某一层该字段为 NULL 就继续沿用下一层
- 结果按 `chatId` 缓存，任何一次策略写入都会让缓存整体失效
- `DisguiseService` 把这份快照传给 `StyleNormalizer` / `ReplyComposer` / `PacingModel` / `QuotaGuard`，
  所以文风清洗、分条、延迟、限流都按该群的策略算；账号级配额（`globalPerHour` / `globalPerDay`）仍然是全局的
- 会话停用（`enabled=false`）时该群立即回退到默认策略，配置保留

### 7.3 回复策略的合并

`ReplyPolicyService.forChat(chatId, bot)` 在**收消息时**判定要不要回，同样三层：

```text
bot_config.group_wake_words → reply_policy(chat_id IS NULL) → reply_policy(chat_id = 当前群)
```

- 唤醒词为空即「主动回复」，所以老配置（清空唤醒词）的行为完全不变
- `RANDOM` 未命中时消息仍然入库（`status=IGNORED` + 「随机未命中 xx%」），摘要在下一轮仍能覆盖这段历史
- `WAKE` 未命中的文本不入库，直接返回 `IGNORED`，不产生任何写操作
- `HUMAN`（拟人）：消息一律入库，`ChatOrchestrator` 在提示词里追加「自己判断要不要接」的说明，
  模型输出 `[[NO_REPLY]]` 时把来消息标成 `IGNORED` 并**不进投递链路**（不占配额、不写 outbox）；
  判定本身由 `HumanReplyGuard` 按 `humanCooldownSeconds` 节流，状态放内存，重启最多多判一次
- 每次判定直接查库（两条带索引的小查询），改完立即生效，没有缓存失效逻辑
- 私聊不走这套判定，保持「收到就回」

### 7.4 连发消息聚合（防复读机）

判定「要回」之后不直接生成，而是交给 `ReplyDispatcher`，按 `chatId` 聚合：

- **防抖**：最后一条消息之后静默 `app.reply.debounce-seconds`（默认 3 秒）才真正生成，
  一次回复覆盖整串对话；期间的新消息只是把定时器往后推
- **封顶**：从这串消息的第一条算起，攒满 `app.reply.max-wait-seconds`（默认 12 秒）必须回一次，
  刷屏的群不会永远等不到静默；0 表示不封顶
- **串行**：同一个 `chatId` 用 `Set` 占位，上一条还在生成时新请求每 500ms 重试排队，
  保证一个群不会同时冒出两段回复
- **不留悬空状态**：被合并掉的请求通过 `superseded` 回调把那条来消息标成
  `IGNORED` + 「已合并到随后的回复」，页面上不会出现永远停在「已接收」的记录
- **合并即作废**：每个请求带一个 `alive` 令牌，被合并时置为 false；定时任务取消和令牌是双保险，
  已经排进串行队列的生成在启动前会再查一次令牌，避免「已经标记忽略却还是单独回了一条」
- **忽略的消息只当上下文**：`ChatOrchestrator.generateReply` / `generateFixedReply` 入口直接跳过
  `IGNORED` 的来消息（不调模型、不占配额、不写 outbox），但它们仍在 `buildContext` 的历史里，
  后续回复看得到这些内容
- 生成跑在自己的线程池（`reply-worker`，默认 4 个会话并发），不占用 `chatExecutor`；
  关闭开关（`REPLY_DEBOUNCE_ENABLED=false`）即恢复「每条消息各自生成」

## 8. 失败与降级

- Hook 不可达：轮询和发送调度记录节流日志，不影响管理 API
- Redis 不可用：去重和限流服务采用本地降级策略（见现有服务实现）
- 模型失败：入站消息记录失败原因，不生成 outbox
- 发送失败：outbox 指数退避，超过 5 次进入 `FAILED`
- 单条消息处理失败：不推进该条游标，下轮重试
- 历史跳过：每个会话只在首次初始化时记录最新锚点

## 9. 安全边界

- `WECHAT_HOOK_URL` 只接受回环地址
- 官方预编译 DLL 没有 token，必须配合 Windows 防火墙
- 仅处理白名单会话：私聊按私聊白名单（为空时才看「全量接收私聊」），群聊必须逐个加群白名单
- 公众号（`gh_*`）、机器人（`*@weclaw`、`*@openim`）与微信系统会话（`filehelper`、`weixin` 等）永不自动回复
- 不执行任意写 SQL，不修改微信消息数据库
- DLL 安装前校验固定 SHA-256，并备份已有的 `version.dll`
- 不随仓库分发微信客户端或腾讯 DLL
- 上线前必须替换所有默认 Token、数据库密码和加密密钥


