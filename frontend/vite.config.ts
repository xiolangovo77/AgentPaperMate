import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: env.API_PROXY_TARGET || 'http://localhost:8123',
          changeOrigin: true,
          timeout: 0,
          proxyTimeout: 0,
        },
      },
    },
    build: { chunkSizeWarningLimit: 700 },
  }
})
