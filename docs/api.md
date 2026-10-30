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

> 官方预编译 DLL 的 `session.db` / `contact.db` 只有在微信登录并完成本地同步后才存在。

## 3. 管理 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/ping` | 连通性检查 |
| GET | `/api/admin/dashboard` | 仪表盘统计 |
| GET | `/api/admin/conversations` | 会话分页列表 |
| GET | `/api/admin/conversations/{id}/messages` | 会话消息 |
| GET/POST/PUT/DELETE | `/api/admin/providers` | 模型提供方 |
| GET | `/api/admin/providers/bootstrap` | 本次启动的环境变量引导结果（密钥掩码） |
| POST | `/api/admin/providers/{id}/test` | 模型连通测试（主 Key 失败会自动用备用 Key） |
| POST | `/api/admin/providers/{id}/search?q=` | 用该提供方的主/备 Key 调 DeepSeek 官方搜索 |
| GET | `/api/admin/settings` | 运行时策略快照（值 + 来源 db/env） |
| PUT | `/api/admin/settings/wechat` | 会话白名单 / 全量接收 / 群聊开关，立即生效 |
| PUT | `/api/admin/settings/llm` | 备用链路与联网搜索开关，立即生效 |
| PUT | `/api/admin/settings/filter` | 敏感词词表与过滤模式（off / rewrite / block），立即生效 |
| POST | `/api/admin/conversations/{id}/takeover` | 人工接管开关：`{"paused":true}` 期间只记录不自动回复 |
| GET/POST/PUT/DELETE | `/api/admin/auto-replies` | 关键词 / 正则自动回复规则 |
| GET | `/api/admin/diagnostics` | 诊断聚合：钩子探活 + 回调计数 + 最近回调 + 失败投递 |
| GET | `/api/admin/diagnostics/outbox` | 失败投递列表 |
| POST | `/api/admin/diagnostics/outbox/{id}/retry` | 手动重试失败投递（attempts 归零重新入队） |
| GET/POST/PUT/DELETE | `/api/admin/prompt-templates` | 提示词模板（可复用人格） |
| POST | `/api/admin/conversations/{id}/persona` | 会话绑定提示词模板：`{"promptTemplateId":"…"}`，传 `null` 回退默认 |
| GET/POST | `/api/admin/scheduled-messages` | 定时消息列表 / 新建 |
| POST | `/api/admin/scheduled-messages/{id}/cancel` | 取消待发送的定时消息 |
| DELETE | `/api/admin/scheduled-messages/{id}` | 删除定时消息记录 |
| GET | `/api/admin/bots` | 机器人配置 |
| PUT | `/api/admin/bots/{id}` | 更新机器人配置 |
| POST | `/api/admin/debug/chat` | 在线调试（不发送微信） |
| GET | `/api/admin/disguise` | 伪装层状态、配额、最近决策 |
| POST | `/api/admin/disguise/preview` | 预览文风清洗、分条与延迟（不占配额） |

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

### 提示词模板与定时消息

提示词模板用于按会话切换人格，绑定时 `POST /api/admin/conversations/{id}/persona`：

```json
{ "promptTemplateId": "0b1f…" }
```

绑定的模板会**替代**机器人默认 `systemPrompt` 参与上下文拼装；解绑传 `null`。模板内容变更后，下一次回复即生效。

定时消息到点后由后端调度器（默认 20 秒一轮）复制到 `outbox`，沿用同一套重试、租约与伪装节奏，因此：

```json
{
  "chatId": "49878296302@chatroom",
  "chatName": "客户群",
  "content": "明天上午十点的例会挪到下午三点",
  "scheduledAt": "2026-11-01T10:00:00+08:00"
}
```

- `scheduledAt` 不能早于当前时间；`chatId` 支持 `wxid` 或 `@chatroom`；
- 状态机：`PENDING`（待发送）→ `SENT`（已转投递队列）/ `CANCELLED` / `FAILED`（命中敏感词拦截或入队异常）；
- 只有 `PENDING` 可以取消，已入队的消息请到诊断中心的失败投递里重试。


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


