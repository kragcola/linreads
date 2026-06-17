import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const CALIBRE_URL = process.env.VITE_CALIBRE_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/calibre': { target: CALIBRE_URL, rewrite: (p) => p.replace(/^\/calibre/, ''), changeOrigin: true },
    },
  },
})
