# Windows 微信 Hook 接入

本目录提供微信 `4.1.10.27` 的原生 Hook 资产和安装工具。生产运行链路是：

```text
WeChat 4.1.10.27 + version.dll -> Spring Boot Java 单体
```

Java 进程直接通过本机 HTTP 使用 Hook，不需要额外的桥接运行时或脚本。

## 1. 预编译 DLL

- 文件：`prebuilt/version.dll`
- 清单：`prebuilt/manifest.json`
- 上游：`https://github.com/aixed/WeChat-Hook` release `v411027_`
- 目标版本：`4.1.10.27`
- 大小：`606208`
- SHA-256：`23948e7d933ff40735ddc0cc516641fd9f935d67ffdec476bb8db61b79940f29`
- Authenticode：未签名

该 DLL 不是腾讯或微软签名文件。信任依据是固定的上游发布版本和 SHA-256，而不是数字签名。不要使用来源或哈希不一致的 DLL。上游仓库未声明明确许可证，对外分发或商用前必须单独确认授权。

校验或重新下载：

```powershell
.\windows-agent\prepare-prebuilt-hook.ps1
```

## 2. 安装

准备一个独立的、带有效腾讯签名的微信 `4.1.10.27` 客户端目录：

```text
D:\WeChat-4.1.10.27\Weixin.exe
```

退出正在运行的匹配微信，然后执行：

```powershell
.\windows-agent\install-hook.ps1 `
  -WeChatDirectory "D:\WeChat-4.1.10.27" `
  -ConfigureLoopbackFirewall
```

脚本会：

- 校验微信文件版本为 `4.1.10.27`
- 校验腾讯 Authenticode 签名
- 校验预编译 DLL 大小和 SHA-256
- 备份现有 `version.dll`
- 安装 DLL 和 `wechatbot-hook-install.json`
- 可选创建仅允许 `127.0.0.1` / `::1` 访问 Hook 端口的防火墙规则
- 使用 `StartPort=30001` 启动微信

只安装、不启动：

```powershell
.\windows-agent\install-hook.ps1 -WeChatDirectory "D:\WeChat-4.1.10.27" -NoLaunch
```

## 3. 安全边界

官方预编译 DLL：

- 无 Bearer Token
- 二进制内部绑定 `0.0.0.0`
- 由 Windows 防火墙阻止局域网访问
- 只应监听本机端口 `30001`

建议检查：

```powershell
Get-NetTCPConnection -LocalPort 30001 -State Listen
Get-NetFirewallRule -DisplayName "WeChatBot Hook 30001 loopback"
```

不要在公网、共享办公网或不受控设备上暴露该端口。若使用自定义覆盖层构建，则应启用 64 位 Bearer Token 并保持回环绑定。

## 4. 验证 Hook

登录微信并等待消息数据库同步，然后使用 Java 管理 API：

```powershell
$headers = @{ Authorization = "Bearer $env:ADMIN_TOKEN" }
Invoke-RestMethod http://localhost:8080/api/admin/wechat/hook -Headers $headers
Invoke-RestMethod http://localhost:8080/api/admin/wechat/sessions -Headers $headers
```

返回 `connected=true`、`version=4.1.10.27` 后，把目标会话写入 `.env`：

```dotenv
WECHAT_ALLOWED_CHATS=wxid_friend,123456@chatroom
WECHAT_HOOK_SKIP_HISTORY=true
```

重启 Spring Boot 后，Java 会从 PostgreSQL 游标开始增量接收消息。

## 5. 协议差异

后端自动探测：

| 协议 | 能力 |
|---|---|
| `UPSTREAM_PREBUILT` | `/QueryDB/status`、`/QueryDB/GetAllDBName`、`/QueryDB/execute`、`/GetSelfProfile`、`/SendTextMsg` |
| `OVERLAY` | `/WeChatAgent/health`、`/databases`、`/self`、`/query`、`/SendTextMsg`，支持 Bearer Token |

如果官方端点不正确，后端会在第一次健康探测时自动回退。协议识别结果可通过管理 API 查看。

## 6. 源码和旧构建脚本

- `vendor/WeChat-Hook-src/`：上游源码归档
- `native-hook/`：带鉴权和只读查询的覆盖层源码
- `build-hook.ps1`：高级用户自行编译覆盖层时使用
- `state/`：本地运行数据，不参与生产安装

默认部署不需要 Visual Studio、C++ Build Tools 或任何脚本解释器。

## 7. 常见问题

| 现象 | 排查 |
|---|---|
| `WeChat version mismatch` | 客户端必须为 `4.1.10.27` |
| `DLL hash mismatch` | 重新执行 `prepare-prebuilt-hook.ps1` |
| `connection refused` | 微信未使用 `StartPort=30001` 启动，或 DLL 未加载 |
| `微信尚未登录` | 登录微信并等待数据库同步 |
| `session.db 尚未打开` | 登录后等待会话列表加载 |
| `message_*.db` 为空 | 至少打开一次目标聊天窗口 |
| 端口可从局域网访问 | 立即启用 Windows 防火墙回环规则 |



