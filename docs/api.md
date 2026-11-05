# API 说明

## 1. 认证与地址

默认地址：`http://localhost:8080`

- `/api/admin/**`：`Authorization: Bearer <ADMIN_TOKEN>` 或 `X-Admin-Token`
- `/api/agent/**`：`X-Agent-Token: <AGENT_TOKEN>`（外部集成接口）
- `/actuator/**`：按 Spring Boot Actuator 配置访问
- 其余路径：管理端静态资源

示例：

```powershell
$headers = @{ Authorization = "Bearer dev-admin-token" }
```

## 2. Hook 管理 API

### GET `/api/admin/wechat/hook`

检查 Hook 连接、微信版本、登录状态和允许会话。

```json
{
  "enabled": true,
  "configured": true,
  "connected": true,
  "protocol": "UPSTREAM_PREBUILT",
  "version": "4.1.10.27",
  "account": "wxid_xxx",
  "startupState": "ready",
  "allowedChats": ["wxid_friend", "123@chatroom"],
  "checkedAt": "2026-10-28T02:30:00Z"
}
```

- `protocol=UPSTREAM_PREBUILT`：官方预编译 DLL
- `protocol=OVERLAY`：带 token/health 的覆盖层
- Hook 不可用时返回 `connected=false` 和 `message`

### GET `/api/admin/wechat/sessions?limit=200`

通过 `session.db` 返回最近会话。用于查找 `wxid` 或 `@chatroom` ID。`limit` 范围 1–500。

### GET `/api/admin/wechat/contacts?limit=200`

通过 `contact.db` 返回联系人，用于核对显示名和会话 ID。

### GET `/api/admin/wechat/chats?kind=private|groups&keyword=&limit=200`

白名单候选搜索：按昵称 / 备注 / 群名称 / ID 片段匹配，返回可直接加入白名单的会话。
数据按可信度合并本机会话备注（`chat_alias`）、可选的外部读取服务（`WECHAT_READER_URL`，
对齐 WeChatAgent 的 WeFlow reader）、微信联系人（`contact.db`）、本地会话库、微信会话列表（`session.db`）
和最近的 Hook 回调。没有名字的会话（只有 wxid / `xxx@chatroom`）还能按最近一条消息匹配。

```json
[
  {
    "chatId": "wxid_rsje67b7hozm22",
    "name": "张三",
    "kind": "PRIVATE",
    "group": false,
    "blocked": false,
    "blockedReason": "",
    "source": "alias",
    "lastMessage": "晚上一起吃饭？",
    "lastActiveAt": "2026-10-31T13:44:46.720111+08:00"
  },
  {
    "chatId": "gh_62c4ce184e73",
    "name": "某某服务号",
    "kind": "OFFICIAL",
    "group": false,
    "blocked": true,
    "blockedReason": "公众号 / 机器人 / 企业微信会话不自动回复",
    "source": "conversation",
    "lastMessage": "活动通知",
    "lastActiveAt": "2026-10-30T09:12:00+08:00"
  }
]
```

`kind` 取值 `PRIVATE` / `GROUP` / `OFFICIAL` / `SYSTEM`；`blocked=true` 表示该会话永远不会被自动回复，
管理端应置灰不可选。`source` 取值 `alias` / `reader` / `contact` / `conversation` / `session` / `hook`，
名称优先级：本机备注 → 外部 reader → 备注/昵称 → 会话名 → ID。

> 微信 4.x 的 `contact.db` 是 SQLCipher 加密的，本仓库预编译 Hook 的 `QueryDB` 拿不到数据
> （源码里 `g_IsLogin` 恒为 0），所以默认只能列出聊过的 / 回调过的会话，名字靠备注补。

### PUT `/api/admin/wechat/chats/{chatId}/alias`

给会话存一个本机备注（存进 `chat_alias` 表，不依赖微信数据库）。名称留空即删除备注。

```json
{ "name": "张三" }
```

响应：`{ "chatId": "wxid_rsje67b7hozm22", "name": "张三" }`


## 3. 管理 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/ping` | 连通性检查 |
| GET | `/api/admin/dashboard` | 仪表盘统计 |
| GET | `/api/admin/conversations` | 会话分页列表 |
| GET | `/api/admin/conversations/{id}/messages` | 会话消息 |
| GET | `/api/admin/flow` | 实时流水：`?since=<游标>&limit=120`，返回排队中的会话 + 增量事件 |
| GET/POST/PUT/DELETE | `/api/admin/providers` | 模型提供方 |
| GET | `/api/admin/providers/bootstrap` | 本次启动的环境变量引导结果（密钥掩码） |
| POST | `/api/admin/providers/{id}/test` | 模型连通测试（主 Key 失败会自动用备用 Key） |
| POST | `/api/admin/providers/{id}/search?q=` | 兼容接口；实际使用独立的联网搜索配置 |
| GET | `/api/admin/settings` | 运行时策略快照（值 + 来源 db/env；含 `allowedPrivateChats` / `allowedGroupChats` 拆分视图） |
| PUT | `/api/admin/settings/wechat` | 会话白名单 / 全量接收 / 群聊开关，立即生效 |
| PUT | `/api/admin/settings/llm` | 模型备用链路开关，立即生效 |
| PUT | `/api/admin/settings/filter` | 敏感词词表与过滤模式（off / rewrite / block），立即生效 |
| GET/PUT | `/api/admin/search-settings` | 独立联网搜索 Base URL、主/备 Key、模式与结果参数 |
| POST | `/api/admin/search-settings/test` | 使用独立搜索配置测试联网搜索 |
| POST | `/api/admin/conversations/{id}/takeover` | 人工接管开关：`{"paused":true}` 期间只记录不自动回复 |
| GET/POST/PUT/DELETE | `/api/admin/auto-replies` | 关键词 / 正则自动回复规则 |
| GET | `/api/admin/diagnostics` | 诊断聚合：钩子探活 + 回调计数 + 最近回调 + 失败投递 |
| GET | `/api/admin/diagnostics/outbox` | 失败投递列表 |
| POST | `/api/admin/diagnostics/outbox/{id}/retry` | 手动重试失败投递（attempts 归零重新入队） |
| GET/POST/PUT/DELETE | `/api/admin/prompt-templates` | 人设（提示词模板）：增删改查，`defaultPersona` 标记默认人设 |
| POST | `/api/admin/prompt-templates/{id}/default` | 设为全局默认人设（未单独指定的会话都用它） |
| DELETE | `/api/admin/prompt-templates/default` | 取消默认人设，回退到机器人 `systemPrompt` |
| POST | `/api/admin/conversations/{id}/persona` | 会话/群单独绑定人设：`{"promptTemplateId":"…"}`，传 `null` 回退默认 |
| POST | `/api/admin/personas/bind` | 按 chatId 绑定（私聊填 wxid、群填 `@chatroom` 结尾）：`{"chatId":"…","promptTemplateId":"…"}`，会话不存在时自动补一条 |
| GET | `/api/admin/personas/preview/{conversationId}` | 该会话实际会发给模型的提示词：人设正文、`personaSource`（chat / default / bot）、记忆块、格式块、`personaChars` / `personaThin` / `switchNote` / `warnings` |
| GET | `/api/admin/memories?chatId=…` | 会话（群）共享记忆：滚动摘要 + 人工要点 |
| POST | `/api/admin/memories` | 新增人工记忆要点：`{"chatId","chatName","content","enabled"}` |
| PUT/DELETE | `/api/admin/memories/{id}` | 修改内容 / 启停 / 删除记忆条目 |
| POST | `/api/admin/memories/summaries?chatId=…` | 立即按现有历史重算一次滚动摘要（同步） |
| DELETE | `/api/admin/memories/summaries?chatId=…` | 清空摘要，下次重新压缩（人工要点保留） |
| GET/POST | `/api/admin/scheduled-messages` | 任务列表 / 新建。`?kind=REMINDER` 只看提醒；新建时 `kind` 为 `MESSAGE`（定时消息）或 `REMINDER`（提醒），后者可带 `title`、`intervalMinutes`、`maxTimes`、`stopWords`、`variants`、`finalMessage`。列表每行另带今天的状态：`todayStatus`（`PENDING` 待发送 / `SENT` 已发送 / `NUDGING` 催收中 / `DONE` 已完成 / `GAVE_UP` 已作罢 / `IDLE` 今天不发 / `STOPPED` / `FAILED`）、`todayText`、`todaySentCount`、`todayReplied`（`null` 表示这类任务不用回）、`todayRepliedText`，全部按 Asia/Shanghai 现算 |
| PUT | `/api/admin/scheduled-messages/{id}` | 编辑待发送的定时消息（时间、接收人、内容、重复规则）；提醒走补丁，可改 `title` / `intervalMinutes` / `maxTimes` / `stopWords` / `variants` / `finalMessage`，没传的字段保持原样 |
| POST | `/api/admin/scheduled-messages/{id}/cancel` | 取消待发送的定时消息 |
| POST | `/api/admin/scheduled-messages/{id}/stop` | 停止任务（消息和正在等回执的提醒都能停） |
| POST | `/api/admin/scheduled-messages/parse` | **一句话创建**：`{"instruction","botId"}` → 草稿（字段同新建，外加 `ok` / `message`），不落库 |
| DELETE | `/api/admin/scheduled-messages/{id}` | 删除任务记录 |
| PUT | `/api/admin/settings/media` | 媒体消息策略（enabled / replyMode / ackText） |
| PUT | `/api/admin/settings/pricing` | 用量计价（每千 token 单价，用于估算成本） |
| GET | `/api/admin/usage?days=7` | 用量统计：收发量、token、耗时、模型分布 |
| GET | `/api/admin/bots` | 机器人配置 |
| PUT | `/api/admin/bots/{id}` | 更新机器人配置 |
| POST | `/api/admin/debug/chat` | 在线调试（不发送微信）。可选 `quoteAuthor` / `quoteText` 模拟引用、`mediaKind` 模拟语音/图片等媒体消息 |
| GET | `/api/admin/disguise` | 伪装层状态、配额、最近决策 |
| POST | `/api/admin/disguise/preview` | 预览文风清洗、分条与延迟（不占配额） |
| GET | `/api/admin/send-policies` | 发信策略：默认策略生效值 + 所有按群覆盖 |
| GET | `/api/admin/send-policies/{chatId}` | 某个会话的生效发信策略 |
| PUT | `/api/admin/send-policies/default` | 保存默认发信策略（未单独配置的会话都用它） |
| PUT/DELETE | `/api/admin/send-policies/{chatId}` | 保存 / 删除按群发信策略（删除即恢复默认） |
| GET | `/api/admin/reply-policies` | 回复策略：默认策略生效值 + 所有按群覆盖 |
| GET | `/api/admin/reply-policies/{chatId}` | 某个群的生效回复策略 |
| PUT | `/api/admin/reply-policies/default` | 保存默认回复策略 |
| PUT/DELETE | `/api/admin/reply-policies/{chatId}` | 保存 / 删除按群回复策略（删除即恢复默认） |

具体请求/响应结构见 `server/src/main/java/com/example/wechatbot/web/dto/AdminDtos.java`。

### 模型 Provider 环境变量引导

启动时若提供了 `LLM_BASE_URL` / `LLM_MODEL`，后端会按同名 upsert 一个 provider，并把 `LLM_API_KEY`（以及可选的 `LLM_BACKUP_API_KEY`）加密落库。`GET /api/admin/providers/bootstrap` 返回本次启动的结果：

```json
{
  "enabled": true,
  "overwrite": true,
  "assignToBot": true,
  "providers": [
    {
      "role": "primary",
      "name": "deepseek",
      "baseUrl": "https://api.deepseek.com/v1",
      "model": "deepseek-chat",
      "priority": 10,
      "action": "created"
    }
  ],
  "assignedBots": 1,
  "warnings": []
}
```

`action` 取值：`created` / `updated` / `unchanged` / `skipped`。`warnings` 会给出“Base URL 不是 http(s)”“缺少 API Key”“现有密钥无法用当前 `APP_SECRET_KEY` 解密”等提示；API Key 本身永不回显。

### 联网搜索配置

联网搜索与聊天模型 Provider 完全分离。聊天 Provider 的 `baseUrl` / `apiKey` 不会用于搜索。

```http
GET /api/admin/search-settings
PUT /api/admin/search-settings
POST /api/admin/search-settings/test
```

`PUT` 可配置：

- `baseUrl`：搜索专用 Base URL，例如 `https://api.deepseek.com`
- `apiKey` / `backupApiKey`：搜索专用主/备 Key，加密保存
- `mode`：`OFF` / `HEURISTIC` / `ALWAYS`
- `count`、`maxResults`、`maxContentChars`、`timeoutSeconds`
- `needContent`：是否请求网页正文

密钥只返回掩码；留空表示保持现有 Key，`clearApiKey` / `clearBackupApiKey` 用于显式清除。
### 伪装层 API

`GET /api/admin/disguise` 返回当前在线状态、下次活跃时间、配额上限、累计计数与最近 40 次决策：

```json
{
  "enabled": true,
  "state": "ACTIVE",
  "zone": "Asia/Shanghai",
  "quietHours": "00:30-07:30",
  "nextActiveAt": "2026-10-29T10:20:00+08:00",
  "partsLimit": 3,
  "perMinute": 3,
  "per10Minutes": 8,
  "perHour": 20,
  "perDay": 120,
  "planned": 12,
  "dropped": 0,
  "deferred": 3,
  "repetitive": 1,
  "globalSentToday": 9
}
```

`GET /api/admin/disguise/config` 返回当前生效的发信延迟、频率、配额、睡眠窗口和分条参数。管理端“发信节奏”页面使用该接口逐项展示参数与说明。
`POST /api/admin/disguise/preview` 走完整条伪装链路但不写库、不占配额：

```json
{
  "chatId": "wxid_demo",
  "chatType": "PRIVATE",
  "text": "**好的**！首先，我为您整理如下：`code`"
}
```

```json
{
  "decision": "SEND",
  "note": "延迟 7 秒，分 1 条",
  "dropped": false,
  "aiScore": 4,
  "parts": [{ "text": "好的！整理如下：code", "delaySeconds": 7 }]
}
```

#### 按群发信策略

发信参数有三层，逐字段覆盖：**环境变量 `app.disguise.*` → 默认策略 → 该会话的策略**。
没在页面上配过的会话直接用默认策略，所以升级之后行为不会突然改变。

```json
{
  "readDelayMinMs": 1200,
  "readDelayMaxMs": 7000,
  "maxDelayMs": 90000,
  "splitEnabled": true,
  "maxParts": 3,
  "maxPartChars": 90,
  "perMinute": 3,
  "per10Minutes": 8,
  "perHour": 20,
  "perDay": 120,
  "minGapMs": 1200,
  "emojiLimit": 1,
  "dropRate": 0.0,
  "promptEnabled": true
}
```

- `GET /api/admin/send-policies` → `{"defaultPolicy":{…,"inherited":true},"defaultRow":null,"overrides":[…]}`：默认策略的生效值 + 所有按群覆盖
- `GET /api/admin/send-policies/{chatId}` → 该会话的生效值（`effective`）+ 它自己的覆盖行（`override`，没配过为 `null`）
- `PUT /api/admin/send-policies/default` 与 `PUT /api/admin/send-policies/{chatId}?chatName=群名`：只写非空字段，`null` 表示「这项继续沿用下一层」
- `DELETE /api/admin/send-policies/{chatId}`：删掉按群策略，该群立即回退默认

配额是两层同时生效：会话级 `perMinute` / `per10Minutes` / `perHour` / `perDay` / `minGapMs` 与账号级 `globalPerHour` / `globalPerDay`（账号级仍只在环境变量里配，避免某个群把整号配额顶满）。

#### 按群回复策略

决定「这个群要不要回」，同样是逐字段覆盖：**机器人配置的 `groupWakeWords` → 默认策略 → 该群的策略**。

```json
{
  "mode": "RANDOM",
  "wakeWords": "@机器人,小助手",
  "randomRate": 0.35,
  "humanCooldownSeconds": 60
}
```

- `mode` 取 `ALWAYS`（主动回复，每条都回）/ `RANDOM`（随机回复）/ `WAKE`（关键词唤醒）/ `HUMAN`（拟人回复，模型自己判断）；`null` 表示这项沿用下一层
- `RANDOM`：命中唤醒词一定回，其余按 `randomRate`（0~1）插话；**没命中也会入库**并标记「随机未命中 xx%」，保证之后接话时上下文完整
- `WAKE`：没命中的文本不入库（历史行为），避免把整群闲聊都收进来
- `HUMAN`：消息一律入库，再带完整对话问一次模型「这条要不要接」；模型输出 `[[NO_REPLY]]` 时标记为忽略（不发送、不占配额）。
  `humanCooldownSeconds`（0~3600，默认 60）是两次判定的最小间隔，间隔内的消息只入库不判定
- 只作用于群聊；私聊保持「收到就回」
- `GET /api/admin/reply-policies` → 默认生效值 + 所有按群覆盖 + 机器人配置的唤醒词（`botWakeWords`）
- `GET /api/admin/reply-policies/{chatId}` → 该群生效值（`effective`，含 `source`：`bot` / `default` / `chat`）+ 覆盖行（`override`）
- `PUT /api/admin/reply-policies/default`、`PUT /api/admin/reply-policies/{chatId}?chatName=群名`、`DELETE /api/admin/reply-policies/{chatId}`

### 自动回复规则与诊断

关键词规则命中后**不调用模型**，直接返回固定话术（仍走伪装层的延迟与配额）。规则按 `priority` 升序匹配，`scope` 支持 `ALL` / `PRIVATE` / `GROUP`：

```json
{
  "name": "价格咨询",
  "matchType": "CONTAINS",
  "pattern": "价格",
  "reply": "价格表：https://example.com/price",
  "scope": "ALL",
  "priority": 10,
  "enabled": true
}
```

`matchType` 取 `EXACT` / `CONTAINS` / `REGEX`；正则非法时保存直接返回 400。

人工接管：`POST /api/admin/conversations/{id}/takeover`，请求体 `{"paused": true}`。接管期间收到的消息以 `IGNORED` 入库并记录“人工接管中，未自动回复”，不会进入模型链路。

会话检索：`GET /api/admin/conversations?query=客户群&page=0&size=20`。

- 先按会话表里的 `chat_name` / `chat_id` 模糊匹配；
- **一条都没匹配上时**，再用会话目录（本机备注 › 联系人昵称 › 群名称 › 最近回调）按名字搜一遍，
  把命中的 `chat_id` 回表取会话。微信回调没带名字时 `chat_name` 就是 `xxx@chatroom`，
  光靠数据库 LIKE 永远搜不到中文群名，这一步是补这个洞；
- 补搜命中的会话，`chatName` 用目录里的名字显示（数据库里的名字本身就是有效名称时保持原样）；
- 目录依赖微信 Hook / 本地库，读不到时按「没搜到」处理，不影响会话列表本身。

诊断聚合示例（`GET /api/admin/diagnostics`）：

```json
{
  "generatedAt": "2026-10-30T02:02:07.562Z",
  "wechat": {
    "hook": { "baseUrl": "http://127.0.0.1:30001", "reachable": true, "instance": "0x7ff83bfc0000", "latencyMs": 370, "error": "" },
    "callback": { "total": 28, "accepted": 21, "ignored": 7, "lastEventAt": "2026-10-30T01:58:11.002+08:00" },
    "guard": { "configured": true, "present": true, "status": { "state": "running", "restarts": 0 } },
    "allowedChats": ["49878296302@chatroom"]
  },
  "recentCallbacks": [
    { "receivedAt": "2026-10-30T01:58:11+08:00", "status": "ACCEPTED", "reason": "消息已接收", "chatId": "wxid_demo", "payload": { "content": "在吗" } }
  ],
  "outbox": { "failedCount": 0, "failed": [] }
}
```

敏感词过滤：`PUT /api/admin/settings/filter`，请求体 `{"bannedWords":["违禁"],"mode":"rewrite"}`。`mode` 取 `off`（不改动）/ `rewrite`（\* 遮蔽）/ `block`（该条回复不发送，写入 `IGNORED` 记录）。

### 实时流水

```http
GET /api/admin/flow?since=0&limit=120
```

```json
{
  "generatedAt": "2026-11-04T15:22:50.120Z",
  "cursor": 1287,
  "capacity": 400,
  "counters": { "RECEIVED": 42, "SKIP": 5, "BUFFER": 0, "MERGE": 3, "QUOTA": 6,
                "PACING": 30, "LLM": 37, "OUTBOX": 37, "SENT": 35, "FAILED": 0 },
  "waiting": [
    { "chatId": "49878296302@chatroom", "chatName": "城南客户群", "stage": "QUOTA",
      "stageLabel": "配额排队", "reason": "每分钟上限 3 条",
      "since": "2026-11-04T15:22:37.010Z", "until": "2026-11-04T15:22:49.010Z" }
  ],
  "events": [
    { "seq": 1287, "at": "2026-11-04T15:22:49.900Z", "stage": "OUTBOX", "stageLabel": "进发送队列",
      "chatId": "49878296302@chatroom", "chatName": "城南客户群",
      "detail": "进发送队列：2 条，第一条 0.8 秒后发", "waitMs": 800, "durationMs": null,
      "preview": "这个周末有人去爬山吗" }
  ]
}
```

- `since` 传上次返回的 `cursor` 即只取新事件（管理端 1.5 秒轮询一次，增量追加，列表不跳动）
- 阶段固定为 `RECEIVED` / `SKIP` / `BUFFER` / `MERGE` / `QUOTA` / `PACING` / `LLM` / `OUTBOX` / `SENT` / `FAILED`
- `waitMs` 是排队/延迟时长，`durationMs` 是这一步本身耗时（目前只有模型调用有）
- `waiting` 只含「还在等」的会话，过期的自动消失；`QUOTA` 的 `reason` 会写明是哪条上限（每分钟 / 每天 / 最小间隔 / 连发冷却 / 全局上限）
- 全部在内存里：环形缓冲 400 条，重启即清空，不落库、不影响回复链路

### 媒体消息

Hook 推来的非文本消息（`type` 不是 `1`）不再丢弃，而是归类落库：

| `type` | `mediaKind` | 占位文本 |
| --- | --- | --- |
| 3 | `IMAGE` | `[图片]` |
| 34 | `VOICE` | `[语音 4″]`（能解析出 `voicelength` 时带时长） |
| 43 | `VIDEO` | `[视频]` |
| 47 | `STICKER` | `[表情]` |
| 49 | `FILE` / `LINK` | `[文件] 报价单.pdf` / `[链接] 标题`（按报文里是否有 `fileext`、`filename` 区分） |
| 10000 / 10002 | `SYSTEM` | `[系统消息] …` |
| 其它 | `OTHER` | `[消息 type=48]` |

`message_log` 同时记录 `media_kind`、`media_name`、`media_size`、`media_url`、`media_md5`、`media_aeskey`、`raw_type`，会话记录里以标签形式展示。

回复策略在管理端「运行策略 → 媒体消息」配置，或 `PUT /api/admin/settings/media`：

```json
{ "enabled": true, "replyMode": "llm", "ackText": "收到，我看下～" }
```

- `enabled=false`：媒体消息直接丢弃，不写记录；
- `replyMode=ignore`：只记录，不回复；
- `replyMode=ack`：回一句固定话术，同样经过敏感词过滤、伪装层与 outbox；
- `replyMode=llm`（默认）：把消息交给模型自然回应；图片会连图一起发（见下节）。

群聊里媒体消息没有文字、无法命中唤醒词，因此只记录不回复。

### 引用消息

微信「引用某条消息再回复」是 `type=49` 的 appmsg（子类型 57）：外层 `<title>` 是用户本次说的话，`<refermsg>` 里装着被引用那条消息的发送者和内容。这类消息按文本处理，且**识别优先级高于媒体识别**（否则 `type=49` 会被误判成链接）：

- `content` = 用户本次说的话，照常参与唤醒词匹配、上下文与回复；
- `quote_author` / `quote_text` = 被引用消息的发送者与被引用的内容。

被引用的不一定是文字，可能是图片、表情、文件、语音、链接、视频。这里按 `<refermsg>` 自己的 `type` 渲染成占位文本，不会把 XML 塞进提示词：

| 被引用的是 | `quote_text` |
| --- | --- |
| 文本 | 原文 |
| 图片 | `[图片]` |
| 语音 | `[语音 4″]`（能解析出 `voicelength` 时带时长） |
| 表情 | `[表情]` |
| 文件 | `[文件] 报价单.pdf` |
| 链接 | `[链接] 标题` |

送进模型时，当前这条消息会拼成：

```
[引用 张三：明天上午十点开会]
可以，我十点到
```

### 图片识别

`type=3` 的图片会真正交给模型看（语音走下面的「语音转文字」，视频、文件仍只有占位文本）。链路：

1. 从报文里取图片属性：`md5`、`aeskey`（原图）与 `cdnthumbmd5`、`cdnthumbaeskey`（缩略图），分别落库到 `media_md5`、`media_aeskey`、`media_thumb_md5`、`media_thumb_aeskey`；
2. 按 `md5(会话 id)` 直接算出本地目录 `%USERPROFILE%\Documents\xwechat_files\<wxid>\msg\attach\<md5(会话)>\<yyyy-MM>\Img\`（跨月时也看上一个月份）；
3. 依次尝试 `<md5>.dat`、`<md5>_h.dat`、`<md5>_b.dat`、`<md5>_t.dat`，以及同样一套 `cdnthumbmd5` 变体——本地文件名用的是报文里某个 md5（原图或缩略图），并不固定；都对不上时退回「同会话目录里消息时间前后 `LLM_VISION_MATCH_WINDOW_SECONDS` 秒内写盘的 `.dat`」。除了 `msg/attach/<hash>/<yyyy-MM>/Img/`，还会看 `cache/<yyyy-MM>/Message/<hash>/Bubble/`（气泡缓存里那份实测比 `_t.dat` 大得多）；
4. 解密用**本机密钥**，不是报文里的 `aeskey`（后者只用于从腾讯 CDN 下载原图）：微信 4.x 的 V2 容器是「15 字节头 + 前 `aes_size` 字节 AES-128-ECB + 尾部单字节 XOR」，密钥由 `%APPDATA%\Tencent\xwechat\net\kvcomm` 下的 code 与账号 wxid 派生（`md5(code + wxid)` 的前 16 位）；
5. 所有能解出来的副本都试一遍，**取像素最多的那份**：高清 `.dat` 常是微信自有的 `wxgf`(HEVC) 容器，JDK 解不了，此时自动落到气泡副本或 `_t.dat` 缩略图；只有缩略图时会在提示里告诉模型「画面受限」，避免它假装看清小字（想让它看清原图，先在电脑微信里打开该图片下载原图再重发）；
6. 长边缩放到 `LLM_VISION_MAX_EDGE` 内并统一转 JPEG，按文件指纹缓存到 `data/media`；
7. 以 `data:image/jpeg;base64,...` 作为 OpenAI 兼容的多段 content（`{type:text}` + `{type:image_url}`）随请求发出。

任何一步失败都退化成 `[图片]` 占位文本继续回复，不会中断链路；上游若明确拒绝带图请求（400/413/422），客户端会自动去掉图片、用纯文本重试一次。图片消息会打一条 INFO 日志（含报文原文与解析出的 md5/密钥），定位或解密出问题时直接看日志。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `LLM_VISION_ENABLED` | `true` | 关掉后图片只保留占位文本，不解码也不上传 |
| `LLM_VISION_MAX_EDGE` | `1024` | 长边像素上限，超过等比缩放 |
| `LLM_VISION_MAX_BYTES` | `1500000` | 单张图编码后字节上限 |
| `LLM_VISION_CACHE_DIR` | `data/media` | 解码后的 JPEG 缓存目录 |
| `LLM_VISION_KVCOMM_DIR` | 空 | 微信本机密钥目录；留空自动取 `%APPDATA%\Tencent\xwechat\net\kvcomm` |
| `LLM_VISION_MATCH_WINDOW_SECONDS` | `180` | 文件名对不上时的时间窗（秒） |
| `WECHAT_IMAGE_ROOT` | 空 | 微信数据目录；留空自动取 `%USERPROFILE%\Documents\xwechat_files` |

### 语音转文字

`type=34` 的语音会转成文字再交给模型。链路：

1. **首选：本地语音库**。微信 4.x 收到的语音不落盘，音频只在 `db_storage/message/media_*.db` 的 `VoiceInfo.voice_data`（SILK）。回调报文里的 `msgid` 就是库里的 `svr_id`，按它精确定位（实测一一对应）；报文没有 `msgid` 时按 `create_time` 在 `ASR_VOICE_WINDOW_SECONDS` 秒窗口内兜底。库是 SQLCipher 4 加密的，服务端自己逐页解密，明文不落盘；
2. 本地库没命中时，报文里能取到 `md5` 就按文件名在 `msg/attach/**/Voice`、`msg/attach/**/Audio` 下找，对不上再按「消息时间前后 `ASR_VOICE_WINDOW_SECONDS` 秒内写盘的 `.dat` / `.silk`」兜底；`.dat` 用本机 kvcomm 派生的密钥解 V2 容器（和图片同一套逻辑），只接受解密后是 SILK / WAV 的内容，避免把别的附件当语音；
3. 报文里带 `voiceurl` 时也试一下直连下载（能下到 SILK / WAV 才算）；
4. SILK 交给外部解码命令转 WAV（Java 侧没有 SILK 解码器）——`ASR_SILK_DECODER` 支持两种协议：JSON 进 JSON 出（stdin `{"b64":...}` → stdout `{"b64":...}`，与 WeChatAgent 的 `scripts/decode-wechat-voice.mjs` 一致）和文件模式（命令里带 `{input}`/`{output}`，例如 `ffmpeg -y -i {input} {output}`）；
5. 交给 ASR 接口，两种 provider：
   - **`bailian`（默认）**：阿里云百炼 `qwen-audio-3.1-asr-flash`，同步 HTTP
     `POST {ASR_BASE_URL}/api/v1/services/aigc/multimodal-generation/generation`，
     头部 `X-DashScope-SSE: disable`，音频以 **base64 data URL** 内联在请求体里
     （`input.messages[0].content[0] = {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}`，
     `parameters.format` 和 `parameters.sample_rate` 从字节头里认，不靠文件名），结果取 `output.text`
     （没有就取 `output.sentence.text`）。不需要公网 URL，也不用先传 OSS；
   - **`siliconflow`（备选）**：OpenAI 风格 `POST {ASR_BASE_URL}/v1/audio/transcriptions`，
     multipart（`file` + `model`），取响应里的 `text`；
6. 转写结果替换占位文本：模型看到 `[语音转写] 明天上午十点开会`，并附带一条「已经转成文字，别再说听不到」的系统提示。

任何一步失败都退回原来的 `[语音 5″]` + 「只能看到时长」说明，不影响回复。

#### 本地语音库的密钥

微信 4.x 的 `media_*.db` 是 SQLCipher 4：`salt` = 页 1 前 16 字节，
`key = PBKDF2-HMAC-SHA512(passphrase, salt, 256000, 32)`，
`mac_key = PBKDF2(key, salt ^ 0x3a, 2, 32)`，每页末尾 64 字节是 `HMAC-SHA512(mac_key, cipher‖iv‖页号)`。

密钥与账号绑定，**一次安装内不变**（换密钥要重加密所有库，微信不会这么干；实测 10-29 建的库今天照样能开）。
只有换账号、重装微信、清数据这类操作才会变。解页、读 B-tree 都由 `SqlCipherFile` / `SqliteReader` 自己做，
不依赖第三方 DLL。

密钥的维护是自动的：

1. **来源优先级**：`data/wechat-db-key.txt`（`WECHAT_DB_KEY_FILE`）> `.env` 的 `WECHAT_DB_KEY`。
   启动后如果只有环境变量里的密钥，会先把它落到文件里；之后一律以文件为准——截获到新密钥只要重写文件就生效，
   不需要改 `.env`、也不需要重启（按 mtime 热加载）。
2. **定时体检**：每 5 分钟（`WECHAT_DB_CHECK_INTERVAL_MS`）拿密钥去开一次 `media_*.db`，HMAC 不对就判定失效，
   随即自动重新截获（`WECHAT_DB_AUTO_CAPTURE`）。
3. **截获原理**：和 `D:\WeChatAgent-main` 的 `scripts/reader_setup.py` 一样——`wx_key.dll` 的
   `InitializeHook(pid)` + `PollKeyData()` 在微信**登录/开库那一刻**截口令，再用页 1 的 HMAC 验证，
   通过才写密钥文件。脚本是 `scripts/capture-wechat-db-key.ps1`（内含 P/Invoke 的 C# 片段，不用给 Java 加 JNA）。
4. **手动触发**：`POST /api/admin/asr/voice-db/capture`（先在微信里退出登录，点完再登录，默认等 180 秒）。
5. **完全不用管**：装一次看门狗，之后微信每次启动/重新登录都会自动截获：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-key-watchdog.ps1
   # 取消：加 -Remove
   ```

   `key-watchdog.ps1` 用 `-TimeoutSeconds 0`（一直等到密钥出现或微信退出），微信重启后自动重新挂钩。

`wx_key.dll` 是第三方二进制、不进仓库（`vendor/wxkey/*.dll` 已忽略）：
`powershell -File scripts\extract-wxkey.ps1` 会从 WeChatAgent 安装包（或上次留下的 `logs\key\wx_key.dll`）
取到 `vendor\wxkey\wx_key.dll`。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `ASR_ENABLED` | `true` | 关掉后不调接口 |
| `ASR_PROVIDER` | `bailian` | `bailian`（百炼同步接口）或 `siliconflow`（OpenAI 风格 multipart） |
| `ASR_BASE_URL` | `https://dashscope.aliyuncs.com` | 百炼地域域名；硅基流动填 `https://api.siliconflow.cn`（带不带 `/v1` 都行） |
| `ASR_API_KEY` | 空 | 服务密钥；**留空则用 `DASHSCOPE_API_KEY`**（一个 Key 管识别+合成） |
| `ASR_MODEL` | `qwen-audio-3.1-asr-flash` | 模型名（硅基流动那套是 `XingChenAGI/XingChenASR-V3.2-Ultra`） |
| `ASR_TRANSCRIBE_VOICE` | `true` | 是否自动转写收到的语音 |
| `ASR_SILK_DECODER` | 空 | SILK→WAV 解码命令；留空自动探测 `node` + WeChatAgent 的解码脚本 |
| `ASR_TIMEOUT` | `90s` | 单次转写超时 |
| `ASR_MAX_BYTES` | `52428800` | 上传大小上限（接口限制 50MB） |
| `ASR_VOICE_WINDOW_SECONDS` | `180` | 没有 `msgid` / md5 时按时间窗匹配语音 |
| `WECHAT_DB_ENABLED` | `true` | 是否读本地语音库 |
| `WECHAT_DB_KEY` | 空 | 本地库密钥（64 位 hex）；没配 = 不读本地库 |
| `WECHAT_DB_KEY_FILE` | `data/wechat-db-key.txt` | 密钥文件；自动截获的新密钥写这里，优先于环境变量 |
| `WECHAT_DB_AUTO_CAPTURE` | `true` | 密钥失效时是否自动重新截获 |
| `WECHAT_DB_CAPTURE_SCRIPT` | `scripts/capture-wechat-db-key.ps1` | 截获脚本路径 |
| `WECHAT_DB_KEY_DLL` | `vendor/wxkey/wx_key.dll` | 截获用的 DLL |
| `WECHAT_DB_CAPTURE_TIMEOUT_SECONDS` | `180` | 单次截获最多等多久 |
| `WECHAT_DB_CHECK_INTERVAL_MS` | `300000` | 密钥体检间隔 |
| `WECHAT_DB_DATA_DIR` | 空 | 微信数据目录；留空自动找 `%USERPROFILE%\Documents\xwechat_files\*\db_storage` |
| `WECHAT_DB_PAGE_SIZE` | `4096` | SQLCipher 页大小，微信 4.x 固定 4096 |

四个自测接口：

```powershell
$h = @{ 'X-Admin-Token' = $env:ADMIN_TOKEN }
# 状态：密钥打码、模型、解码器是否就绪、本地语音库概况
Invoke-RestMethod http://localhost:8080/api/admin/asr -Headers $h
# 只看本地语音库：keyValid / keySource / 分片数 / 语音条数 / 截获能力
Invoke-RestMethod http://localhost:8080/api/admin/asr/voice-db -Headers $h
# 某一条语音能不能取到（msgid 就是回调报文里的 msgid）
Invoke-RestMethod http://localhost:8080/api/admin/asr/voice-db/8007476072464367940 -Headers $h
# 立刻重新截获一次密钥（先退出微信登录，点完再登录）
Invoke-RestMethod -Method Post http://localhost:8080/api/admin/asr/voice-db/capture -Headers $h
# 用本机文件跑一遍完整链路（支持 SILK / 加密 .dat）
Invoke-RestMethod -Method Post http://localhost:8080/api/admin/asr/test -Headers $h -ContentType 'application/json' `
  -Body '{"path":"D:/wechat_bot/logs/voice.silk"}'
# 也可以直接上传音频
curl.exe -s -X POST http://localhost:8080/api/admin/asr/test -H "X-Admin-Token: $env:ADMIN_TOKEN" -F "file=@D:/tmp/a.wav"
```

响应形如 `{"ok":true,"text":"今天天气不错，我们下午去公园吧。","model":"qwen-audio-3.1-asr-flash","latencyMs":2214,"bytes":169134,"silk":false}`；失败时 `{"ok":false,"error":"..."}`，错误一律是可读中文（密钥缺失、接口 4xx/5xx、解码器不可用等）。

### 语音合成（TTS）

模型用阿里云百炼的 **`qwen-audio-3.1-tts-flash`**（Qwen-Audio-TTS），走 WebSocket 双工事件流，
不是普通的 HTTP 接口。一次合成就一条连接，顺序固定：

```
客户端 → run-task（模型 / 音色 / 采样率 / 格式）      → 服务端 task-started
客户端 → continue-task（待合成文本）→ finish-task      → 服务端 二进制音频帧… → task-finished
```

三个事件的 `task_id` 必须一致（客户端生成 UUID）。音频是**二进制帧**（`pcm` 是裸数据，需要自己补 WAV 头）；
服务端按句合成，所以帧数不固定。请求头要 `Authorization: bearer <API Key>`，
域名北京是 `wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`
（老域名 `wss://dashscope.aliyuncs.com/api-ws/v1/inference` 也能用）。

实现：`QwenTtsClient`（JDK 自带 `java.net.http.WebSocket`，不加第三方依赖）+ `TtsService`（补 WAV 头、落盘 `data/tts/`、只留最近 50 个）。

**SILK**：微信语音消息要的是 16 kHz 单声道 SILK v3，文件以 `0x02` 开头接 `#!SILK_V3`，每帧前置 2 字节小端长度。
开了 `TTS_SILK`（默认开）就用 `scripts/encode-wechat-voice.mjs`（silk-wasm 的 wasm 编码器）顺手生成一个 `.silk`，
并且**按 16 kHz 合成**，省一次重采样。注意 WAV 自带采样率——拿 24 kHz 的 WAV 按 16 kHz 编码会让时长变成 1.5 倍，
脚本里已经改成「WAV 输入就按它自己的采样率编」。

**发语音还不行**：当前 Hook（`version.dll` 4.1.10.27）只开放 `/SendTextMsg` 和 `/SendImgMsg`，
`/SendVoiceMsg`、`/SendAudioMsg`、`/SendFileMsg`、`/UploadVoice`、`/SendSilk` 全是 404。
`POST /api/admin/tts/send` 会按约定去试 `/SendVoiceMsg`（`{"wxidorgid":..,"path":"<abs .silk>"}`），
明确返回 `{"status":"unsupported"}` 而不是假装成功；换到带这个接口的 Hook 后不用改代码。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `TTS_ENABLED` | `true` | 关掉后接口一律拒绝 |
| `DASHSCOPE_API_KEY` | 空 | 百炼 API Key；留空 = 未配置（`TTS_API_KEY` 也接受） |
| `TTS_BASE_URL` | `wss://dashscope.aliyuncs.com/api-ws/v1/inference` | 建议换业务空间专属域名 |
| `TTS_MODEL` | `qwen-audio-3.1-tts-flash` | 模型名 |
| `TTS_VOICE` | `longanhuan_v3.1` | 音色，见 Qwen-Audio-TTS 音色列表 |
| `TTS_FORMAT` | `pcm` | `pcm` / `wav` / `mp3` / `opus`；pcm 由本地补头，时长最准 |
| `TTS_SAMPLE_RATE` | `24000` | 8000 / 16000 / 22050 / 24000 / 44100 / 48000 |
| `TTS_VOLUME` | `50` | 0–100 |
| `TTS_RATE` | `1.0` | 语速 0.5–2.0 |
| `TTS_PITCH` | `1.0` | 音调 0.5–2.0 |
| `TTS_TIMEOUT` | `60s` | 单次合成超时 |
| `TTS_MAX_CHARS` | `500` | 单次最大字数（按字数计费） |
| `TTS_OUTPUT_DIR` | `data/tts` | 合成结果落盘目录 |
| `TTS_SILK` | `true` | 是否顺手生成微信语音用的 `.silk` |
| `TTS_SILK_ENCODER` | 空 | WAV→SILK 命令；留空自动用 `node scripts/encode-wechat-voice.mjs`（node 优先从 `ASR_SILK_DECODER` 借） |
| `TTS_SILK_SAMPLE_RATE` | `16000` | 生成 SILK 时用的合成采样率（微信是 16k） |

```powershell
$h = @{ 'X-Admin-Token' = $env:ADMIN_TOKEN }
# 状态：模型 / 音色 / 格式 / 密钥 / SILK 编码器 / 最近的合成结果
Invoke-RestMethod http://localhost:8080/api/admin/tts -Headers $h
# 合成一段话（同时得到 WAV 和 SILK）
Invoke-RestMethod -Method Post http://localhost:8080/api/admin/tts -Headers $h -ContentType 'application/json' `
  -Body '{"text":"你好，我是小助手","voice":"longanhuan_v3.1"}'
# 浏览器里直接听（inline，audio/wav）
Start-Process "http://localhost:8080/api/admin/tts/files/<上一步返回的 file>"
# 试着让 Hook 发出去（当前 Hook 会返回 status=unsupported）
Invoke-RestMethod -Method Post http://localhost:8080/api/admin/tts/send -Headers $h -ContentType 'application/json' `
  -Body '{"chatId":"wxid_xxx","file":"<上一步返回的 silk>"}'
```

响应形如
`{"ok":true,"file":"tts-20261102-142033-7c1929.wav","url":"/api/admin/tts/files/tts-20261102-142033-7c1929.wav","bytes":93804,"format":"wav","sampleRate":16000,"durationMs":2930,"chunks":7,"latencyMs":572,"model":"qwen-audio-3.1-tts-flash","voice":"longanhuan_v3.1","silk":"tts-20261102-142033-7c1929.silk","silkUrl":"/api/admin/tts/files/tts-20261102-142033-7c1929.silk","silkBytes":10833,"silkFrames":147}`；
失败时 `{"ok":false,"error":"..."}`（未配 key、文本超长、`task-failed` 带 `error_code`/`error_message` 等）。
SILK 单独失败不影响 WAV，会多一个 `silkError` 字段说明原因。

### 用量统计

`GET /api/admin/usage?days=7`（`days` 取值 1–90，超出按 90 计）：

```json
{
  "days": 7,
  "from": "2026-10-24T00:00:00+08:00",
  "to": "2026-10-30T11:47:04+08:00",
  "totals": {
    "inbound": 37, "outbound": 64, "replied": 64, "ignored": 0, "failed": 0,
    "promptTokens": 34853, "completionTokens": 11264,
    "avgLatencyMs": 4491, "activeConversations": 7
  },
  "estimatedCost": 0.0,
  "currency": "CNY",
  "daily": [{ "date": "2026-10-29", "inbound": 23, "outbound": 43, "promptTokens": 21546, "completionTokens": 9582 }],
  "providers": [{ "provider": "deepseek", "model": "deepseek-v4.1-flash", "replies": 64,
                  "promptTokens": 34853, "completionTokens": 11264, "avgLatencyMs": 4491 }]
}
```

数据全部来自 `message_log`，不做额外埋点。预估成本需要先在「运行策略」填写每千 token 单价（`PUT /api/admin/settings/pricing`，`{"promptPricePerK":0.001,"completionPricePerK":0.002}`），未填写时返回 0。

### 人设（提示词模板）

人设就是一段系统提示词，支持自定义增删改，也支持「全局默认 + 按群单独指定」：

- 生效顺序：**会话/群绑定的人设 → 全局默认人设 → 机器人 `systemPrompt`**
- 全局最多一个默认人设（数据库唯一索引保证），首次启动会内置一份「大肥鱼」，可直接编辑
- 绑定/解绑按会话：`POST /api/admin/conversations/{id}/persona`，`{"promptTemplateId":"0b1f…"}`，传 `null` 表示回退默认
- 按 chatId 绑定（会话还没出现过也行）：`POST /api/admin/personas/bind`，`{"chatId":"wxid_x","promptTemplateId":"0b1f…"}`，
  `@chatroom` 结尾按群聊建会话，其余按私聊
- 生效预览：`GET /api/admin/personas/preview/{conversationId}` 返回人设正文、`personaSource`（`chat` / `default` / `bot`）、
  记忆块与风格块 —— 就是这条会话真正会发给模型的那几段系统提示词；另带 `personaChars` / `personaThin`（人设是否短到
  模型看不出区别）、`switchNote`（换人设后注入的「别学旧语气」）、`warnings`（页面直接列出来的坑）
- 会话列表的 `effectivePersonaName` 会显示实际生效的人设（继承默认时带「（默认）」后缀）
- **让聊天对象自己选**：群里/私聊里发「换人设」，或者自然语言说「换成客服语气」「有哪些人设」都可以。
  点名了人设就直接换，没点名就回一份人设名称列表（一行一个编号），对方回数字或说名字即改绑该会话
  （等价于上面那条按会话绑定），下一条消息立刻生效。名单 5 分钟内有效，回别的话就当普通消息；
  开关是 `PERSONA_SWITCH_ENABLED`
- 换人设时记下时间点（`conversation.persona_switched_at`）：之后只要对话窗口里还有切换前的旧回复，
  生成时就会额外注入一句「那些是旧人设说的，只当事实、别学语气」；窗口滚完自动失效，不需要清理
- 换人设的**确认语按新人设的口气生成**（模型不可用时退回固定模板），刚切完就能听出差别
- 名单这类控制回复走 `generateVerbatimReply`：**原样单条**发出，不清洗文风、不按行拆条，
  所以编号与换行在手机上不会丢（普通回复仍然走文风清洗 + 分条）

```json
{
  "name": "大肥鱼",
  "description": "一条爱吃爱睡的胖鱼，嘴贫心软",
  "content": "你是「大肥鱼」，一条住在微信里的胖鱼……"
}
```

删除默认人设时，会自动把最近更新的一条顶上，避免机器人突然失去人设。

### 共享记忆（群内所有成员共用）

上下文窗口只装最近 N 条消息，活跃群聊里前面定过的事很快滚出窗口，机器人就会「各说各的」。共享记忆把滚出窗口的历史压成滚动摘要，再叠上人工要点，一起注入系统提示词：

- 摘要：模型自动生成，每个会话一条（`kind = SUMMARY`），只处理「窗口之外、且上次没压缩过」的消息
- 要点：管理端手工维护（`kind = NOTE`），可启停，`enabled=false` 时不进提示词
- 按 `chat_id` 存：同一个群的所有成员共用；私聊只属于那一个人，不会串味

```json
{ "chatId": "49878296302@chatroom", "chatName": "客户群", "content": "群里管张伟叫老张；周五 16:00 站会", "enabled": true }
```

触发条件与长度上限在 `app.memory.*`（见 `.env.example` 的 `MEMORY_*`）：默认新增 24 条历史才压一次，两次之间至少间隔 5 分钟，摘要上限 600 字，注入要点最多 8 条。

### 定时消息

定时消息到点后由后端调度器（默认 20 秒一轮）复制到 `outbox`，沿用同一套重试、租约与伪装节奏，因此：

```json
{
  "chatId": "49878296302@chatroom",
  "chatName": "客户群",
  "content": "明天上午十点的例会挪到下午三点\n记得带电脑",
  "scheduledAt": "2026-11-01T10:00:00+08:00",
  "repeatRule": "CRON",
  "cronExpression": "0 9 * * 1-5",
  "repeatUntil": null,
  "splitLines": true
}
```

- `scheduledAt` 不能早于当前时间；`chatId` 支持 `wxid` 或 `@chatroom`，页面可按群名 / 好友昵称搜索（`GET /api/admin/conversations?query=`），也能直接粘贴
- `repeatRule`：`NONE` / `DAILY` / `WEEKLY` / `CRON`。重复消息发完不结束，而是把 `scheduledAt` 推到未来最近的一次继续排队；停机错过的轮次不会补发
- `cronExpression`：`repeatRule=CRON` 时必填。「分 时 日 月 周」5 段会自动补秒位（存 6 段也直接认），周一到周日写 `1-7` 或 `MON-FRI`；表达式非法直接 400 并提示正确写法。此时 `scheduledAt` 是起点，若落在过去会自动顺延到下一次触发
- `repeatUntil`：重复截止时间，留空表示一直重复
- `splitLines=true`：内容按行拆成多条依次入队（最多 10 条），适合群通知分条发
- 状态机：`PENDING`（待发送）→ `SENT`（已转投递队列）/ `CANCELLED` / `FAILED`（命中敏感词拦截或入队异常）；重复消息在两次发送之间保持 `PENDING`
- 只有 `PENDING` 可以编辑或取消；已入队的消息请到诊断中心的失败投递里重试

### 提醒任务（等回执的定时催促）

同一条表、同一套投递链路，只是 `kind=REMINDER` 时语义从「到点发一条」变成「到点开始催，收到回执才停」：

```json
{
  "kind": "REMINDER",
  "chatId": "wxid_c345asxv4df322",
  "chatName": "深海",
  "title": "打卡",
  "scheduledAt": "2026-11-05T18:00:00+08:00",
  "intervalMinutes": 5,
  "maxTimes": 6,
  "stopWords": "已打卡,打完了,好了",
  "variants": "到点了，今天的卡打了吗？\n还没打吧？回「已打卡」我就不催了。",
  "finalMessage": "算了，6 次都没回，我先不催了。",
  "repeatRule": "DAILY"
}
```

- **只支持私聊**：`chatId` 是 `@chatroom` 直接 400（群里谁都在说话，提醒会变成广播，也判不清该谁回执）
- `intervalMinutes`：催促间隔，服务端夹在 `[REMINDER_MIN_INTERVAL_MINUTES, 1440]`
- `maxTimes`：最多催几次，服务端夹在 `[1, REMINDER_HARD_MAX_TIMES]`；催满（且 `REMINDER_FINAL_MESSAGE=true`）会补一句 `finalMessage` 然后 `EXPIRED`
- `stopWords`：逗号分隔，命中即停；`REMINDER_NL_ACK=true` 时还有模型兜底判断（「刚弄完了」也算）
- `variants`：多行文案，第 N 次催用第 N 行（不够就循环），留空则用 `content` + 模板兜底
- 每个私聊同时最多 `REMINDER_MAX_WAITING_PER_CHAT` 个提醒在等回执、每天最多新建 `REMINDER_MAX_PER_DAY_PER_CHAT` 个，超了直接 400 并说明原因
- **免打扰时段（00:30–07:30）不发送也不消耗次数**：直接把 `scheduledAt` 推到下一个可发送时间（带随机抖动）
- 状态机：`PENDING` → `ACKED`（对方回话，`ackAt` / `ackContent` 记下原话）/ `EXPIRED`（催满）/ `CANCELLED`（停止）/ `FAILED`；重复提醒收到回执时只结束当轮，`sentCount` 归零并推下一次
- 停止：`POST /{id}/stop`；聊天里说「别催了」走的是同一段逻辑（`stopWaiting`，同会话所有等待中的提醒一起取消）

**一句话创建**：`POST /api/admin/scheduled-messages/parse`

```json
{ "botId": "可省略，默认取第一个启用的机器人", "instruction": "每天下午六点提醒我打卡，没回就五分钟催一次，最多六次" }
```

```json
{
  "ok": true,
  "kind": "REMINDER",
  "title": "打卡",
  "firstAt": "2026-11-05T18:00:00+08:00",
  "repeatRule": "DAILY",
  "intervalMinutes": 5,
  "maxTimes": 6,
  "stopWords": "已打卡,打完了,好了",
  "variants": "到点了，今天的卡打了吗？\n还没打吧？回「已打卡」我就不催了。",
  "finalMessage": "算了，6 次都没回，我先不催了。",
  "message": null
}
```

- 只解析、不落库：确认后再用同一个 JSON 调 `POST /api/admin/scheduled-messages`（`kind=REMINDER`）
- 缺时间时返回 `ok=false` + `message`（反问内容），不会瞎猜；聊天里建的走同样的解析，`source=CHAT` 且保留 `rawInstruction`
- 没提「没回就催」时只建**定时消息**（`kind=MESSAGE`），不会自作主张变成催命循环

**聊天里改 / 删任务**（私聊，只对 `source=CHAT` 的任务生效）

同一句话入口（`REMINDER_CREATE_IN_CHAT`）现在也认 `update` / `delete` 两种意图：

```text
「把打卡改到明天早上九点」   → intent=update，change.firstAt
「打卡改成每十分钟催一次」   → intent=update，change.everyMinutes
「少催两次」「停止词改成已提交」→ intent=update，change.maxTimes / change.stopWords
「删掉打卡」                → intent=delete
```

- 模型只返回**要改的那几项**（`change` 里没出现的键 = 保持原样），后端用
  `ScheduledMessageService.patch(id, ReminderPatch)` 局部更新，不会把标题、停止词、重复规则清空
- 定位任务：先按 `target` 关键词匹配标题（双向包含），再按 `targetIndex`；都对不上且只有一条就直接用它，
  多条则回一份编号清单，对方回数字（`2` / `第 2 个` / `第二个`）直接落，不再调模型
- 等序号期间 `awaitingChoice` 为真，回执识别会让路，避免把「2」当成「我回话了」把提醒收掉
- 说的时间已经过去 → 反问补一个之后的时间，不硬改
- 后台建的任务（`source=ADMIN`）不给改：回一句「没有能改的提醒」；要停说「别再催了」（`stopWaiting`）
- 删除 = `CANCELLED`（记录留档，管理端可再真删）
- 管理端 `PUT /api/admin/scheduled-messages/{id}` 现在也能改提醒（同样走补丁，只改填过的字段）


## 4. 外部集成 API

保留用于迁移、联调和外部桥接。默认 Java Hook 链路不会调用这些接口。

### POST `/api/agent/events`

接收统一事件。事件 ID 用于 Redis 去重，会话策略、群唤醒词、限流与 AI 编排仍由 Java 执行。

```json
{
  "eventId": "wxh_xxx",
  "botId": null,
  "type": "message",
  "chatId": "wxid_friend",
  "chatName": "朋友A",
  "chatType": "PRIVATE",
  "senderId": "wxid_friend",
  "senderName": "朋友A",
  "content": "你好",
  "sentAt": "2026-10-28T10:00:00+08:00",
  "fromSelf": false
}
```

### GET `/api/agent/outbox?limit=20`

租约领取待发送回复，并增加尝试次数。与 Java 内置调度器同时启用时可能竞争，生产环境不建议混用。

### POST `/api/agent/outbox/{id}/ack`

```json
{
  "success": true,
  "error": null
}
```

## 5. 错误格式

```json
{
  "code": 503,
  "message": "无法连接微信 Hook: http://127.0.0.1:30001",
  "timestamp": "2026-10-28T10:00:00+08:00"
}
```

- `400`：参数或状态不满足
- `401`：管理 Token/Agent Token 错误
- `404`：资源不存在
- `503`：Hook 不可连接、未登录或版本不匹配
- `500`：未预期服务器错误



