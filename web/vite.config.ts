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

  return {
    plugins: [vue()],
    envDir,
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
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
