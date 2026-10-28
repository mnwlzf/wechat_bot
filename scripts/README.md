# Windows 启动脚本

先在仓库根目录准备配置：

```powershell
Copy-Item .env.example .env
```

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


脚本会读取根目录 `.env`。若使用本机 PostgreSQL 16/Redis 7，请在 `.env` 中调整连接参数。

