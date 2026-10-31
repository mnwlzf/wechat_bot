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
| GET/POST/PUT/DELETE | `/api/admin/prompt-templates` | 提示词模板（可复用人格） |
| POST | `/api/admin/conversations/{id}/persona` | 会话绑定提示词模板：`{"promptTemplateId":"…"}`，传 `null` 回退默认 |
| GET/POST | `/api/admin/scheduled-messages` | 定时消息列表 / 新建 |
| POST | `/api/admin/scheduled-messages/{id}/cancel` | 取消待发送的定时消息 |
| DELETE | `/api/admin/scheduled-messages/{id}` | 删除定时消息记录 |
| PUT | `/api/admin/settings/media` | 媒体消息策略（enabled / replyMode / ackText） |
| PUT | `/api/admin/settings/pricing` | 用量计价（每千 token 单价，用于估算成本） |
| GET | `/api/admin/usage?days=7` | 用量统计：收发量、token、耗时、模型分布 |
| GET | `/api/admin/bots` | 机器人配置 |
| PUT | `/api/admin/bots/{id}` | 更新机器人配置 |
| POST | `/api/admin/debug/chat` | 在线调试（不发送微信）。可选 `quoteAuthor` / `quoteText` 模拟引用、`mediaKind` 模拟语音/图片等媒体消息 |
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

`type=34` 的语音会转成文字再交给模型（对标 `POST /v1/audio/transcriptions`）。链路：

1. 报文里能取到 `md5` 就按文件名在 `msg/attach/**/Voice`、`msg/attach/**/Audio` 下找，对不上再按「消息时间前后 `ASR_VOICE_WINDOW_SECONDS` 秒内写盘的 `.dat` / `.silk`」兜底；
2. `.dat` 用本机 kvcomm 派生的密钥解 V2 容器（和图片同一套逻辑），只接受解密后是 SILK / WAV 的内容，避免把别的附件当语音；
3. SILK 交给外部解码命令转 WAV（Java 侧没有 SILK 解码器）——`ASR_SILK_DECODER` 支持两种协议：JSON 进 JSON 出（stdin `{"b64":...}` → stdout `{"b64":...}`，与 WeChatAgent 的 `scripts/decode-wechat-voice.mjs` 一致）和文件模式（命令里带 `{input}`/`{output}`，例如 `ffmpeg -y -i {input} {output}`）；
4. 以 multipart（字段 `file` + `model`）上传到 `{ASR_BASE_URL}/audio/transcriptions`，取响应里的 `text`；
5. 转写结果替换占位文本：模型看到 `[语音转写] 明天上午十点开会`，并附带一条「已经转成文字，别再说听不到」的系统提示。

任何一步失败都退回原来的 `[语音 5″]` + 「只能看到时长」说明，不影响回复。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `ASR_ENABLED` | `true` | 关掉后不调接口 |
| `ASR_BASE_URL` | `https://api.siliconflow.cn/v1` | 兼容 OpenAI transcriptions 的服务地址 |
| `ASR_API_KEY` | 空 | 服务密钥；留空视为未配置，语音只显示时长 |
| `ASR_MODEL` | `XingChenAGI/XingChenASR-V3.2-Ultra` | 模型名 |
| `ASR_TRANSCRIBE_VOICE` | `true` | 是否自动转写收到的语音 |
| `ASR_SILK_DECODER` | 空 | SILK→WAV 解码命令；留空自动探测 `node` + WeChatAgent 的解码脚本 |
| `ASR_TIMEOUT` | `90s` | 单次转写超时 |
| `ASR_MAX_BYTES` | `52428800` | 上传大小上限（接口限制 50MB） |
| `ASR_VOICE_WINDOW_SECONDS` | `180` | 没有 md5 时按时间窗匹配语音文件 |

两个自测接口：

```powershell
$h = @{ 'X-Admin-Token' = $env:ADMIN_TOKEN }
# 状态：密钥打码、模型、解码器是否就绪
Invoke-RestMethod http://localhost:8080/api/admin/asr -Headers $h
# 用本机文件跑一遍完整链路（支持 SILK / 加密 .dat）
Invoke-RestMethod -Method Post http://localhost:8080/api/admin/asr/test -Headers $h -ContentType 'application/json' `
  -Body '{"path":"D:/wechat_bot/logs/voice.silk"}'
# 也可以直接上传音频
curl.exe -s -X POST http://localhost:8080/api/admin/asr/test -H "X-Admin-Token: $env:ADMIN_TOKEN" -F "file=@D:/tmp/a.wav"
```

响应形如 `{"ok":true,"text":"今天天气不错，我们下午去公园吧。","model":"XingChenAGI/XingChenASR-V3.2-Ultra","latencyMs":2214,"bytes":169134,"silk":false}`；失败时 `{"ok":false,"error":"..."}`，错误一律是可读中文（密钥缺失、接口 4xx/5xx、解码器不可用等）。

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



