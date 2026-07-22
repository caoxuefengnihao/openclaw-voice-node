import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 默认前端 5174 / 后端 8090，避免与已有项目（5173 / 8080）冲突
// 可用环境变量覆盖：FRONTEND_PORT=xxxx BACKEND_PORT=xxxx
const backendPort = process.env.BACKEND_PORT || '8090'
const frontendPort = Number(process.env.FRONTEND_PORT) || 5174

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: frontendPort,
    proxy: {
      // Vite dev 时把 WS 代理到 Java 后端，避免 CORS
      '/ws': {
        target: `ws://localhost:${backendPort}`,
        ws: true,
      },
      '/api': {
        target: `http://localhost:${backendPort}`,
        changeOrigin: true,
      },
    },
  },
})
