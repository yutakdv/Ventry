import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// dev 서버에서 /api 요청은 로컬 백엔드(:8080)로 프록시.
// 프로덕션(nginx)에서는 nginx.conf가 api 컨테이너로 프록시한다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
