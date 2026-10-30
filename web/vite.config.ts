import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 仓库根目录持有唯一的 .env（后端 / Docker / 前端共用）。
const envDir = fileURLToPath(new URL('..', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, envDir, '')
  const backendPort = env.SERVER_PORT || '8080'
  const proxyTarget = env.VITE_PROXY_TARGET || `http://127.0.0.1:${backendPort}`
  const devPort = Number(env.VITE_DEV_PORT || 5173)
  // 显式绑 IPv4：Vite 默认的 localhost 在 Windows 上只会监听 ::1，
  // 导致 127.0.0.1:5173 连不上（脚本、探活、部分工具都按 IPv4 访问）。
  // 需要局域网访问时设 VITE_DEV_HOST=0.0.0.0。
  const devHost = env.VITE_DEV_HOST || '127.0.0.1'

  return {
    plugins: [vue()],
    envDir,
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: devHost,
      port: devPort,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
        '/actuator': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
