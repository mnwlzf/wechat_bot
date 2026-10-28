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
| POST | `/api/admin/providers/{id}/test` | 模型连通测试 |
| GET | `/api/admin/bots` | 机器人配置 |
| PUT | `/api/admin/bots/{id}` | 更新机器人配置 |
| POST | `/api/admin/debug/chat` | 在线调试（不发送微信） |

具体请求/响应结构见 `server/src/main/java/com/example/wechatbot/web/dto/AdminDtos.java`。

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

