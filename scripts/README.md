# Windows 启动脚本

所有环境变量都集中在仓库根目录唯一的 `.env`（基础设施 + 后端 + 前端 Vite 共用），首次使用先复制模板：

```powershell
Copy-Item .env.example .env
```

`.env` 已被 `.gitignore` 忽略；变量留空表示不注入，使用程序内置默认值。

## 生产/联调启动

```powershell
# 1. PostgreSQL + Redis（需要 Docker Desktop）
.\scripts\start-infra.ps1

# 2. 安装并启动微信 Hook（见 windows-agent/README.md）
.\windows-agent\install-hook.ps1 -WeChatDirectory "D:\WeChat-4.1.10.27" -ConfigureLoopbackFirewall

# 3. 启动 Java 单体（同时托管已构建的 Vue 管理端）
.\scripts\start-server.ps1
```

访问 `http://localhost:8080`。Java 进程会直接轮询微信 Hook 并消费发件箱。

## 前端开发

仅在修改 Vue 源码时使用：

```powershell
.\scripts\start-web.ps1
```

Vite 开发地址为 `http://localhost:5173`。生产构建：

```powershell
cd web
npm ci
npm run build
```

随后执行 `mvn -f server/pom.xml package`，Maven 会把 `web/dist` 放入 Spring Boot JAR。


脚本会读取根目录 `.env`。数据库连接只需提供 `POSTGRES_HOST`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`，JDBC 连接串由后端自动拼装；Redis 同理只需 `REDIS_HOST`、`REDIS_PORT`、`REDIS_USERNAME`、`REDIS_PASSWORD`、`REDIS_DATABASE`（`REDIS_USERNAME` 为空即传统密码认证，`REDIS_DATABASE` 为库号，默认 0）。

`start-web.ps1` 不需要单独的前端配置文件：`web/vite.config.ts` 通过 `envDir` 直接读取根目录 `.env` 中的 `VITE_API_BASE_URL`、`VITE_DEV_PORT`、`VITE_PROXY_TARGET`。

