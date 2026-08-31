import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// در حالت توسعه، درخواست‌های /api به بک‌اند اسپرینگ (پورت ۸۰۸۰) پروکسی می‌شوند
// تا مرورگر با CORS درگیر نشود و آدرس API در کد hard-code نباشد.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
