import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    allowedHosts: ['.']  // 允许任意 Host 访问
  },
  optimizeDeps: {
    include: ['vue', 'vue-router']
  }
});