import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In dev (`npm run dev`), Vite forwards API and WebSocket calls to the Spring Boot app,
// so the browser only ever talks to one origin, the same as behind nginx in Docker.
const backend = process.env.BACKEND_URL ?? 'http://localhost:8090'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/ws': { target: backend, ws: true },
    },
  },
})
