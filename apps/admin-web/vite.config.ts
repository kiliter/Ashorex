import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

// 后台挂在 /admin 下，构建产物直接写入 Spring Boot 的 classpath 静态目录。
// dev 模式通过代理把 /admin/api 与登录请求转发到本机 Spring Boot，避免跨域与 CSRF 失效。
// 只在 Node 配置阶段读取根目录环境文件；不向浏览器注入服务端密钥。
const localEnv = loadEnv('development', fileURLToPath(new URL('../..', import.meta.url)), '');
const serverPort = process.env.SERVER_PORT || localEnv.SERVER_PORT || '18080';
const SERVER_ORIGIN = process.env.ADMIN_DEV_PROXY || localEnv.ADMIN_DEV_PROXY || `http://127.0.0.1:${serverPort}`;

export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../server/src/main/resources/static/admin',
    emptyOutDir: true,
    // sourcemap 默认关闭：`.js.map` 的 sourcesContent 内嵌完整 Vue/TS 源码，
    // 而 /admin/assets/** 按 SecurityConfiguration 是匿名可读的，打开后等于把后台
    // 全部内部接口路径、请求体结构与业务判断逻辑暴露给未登录访问者。
    // 本机排查问题时用 ADMIN_SOURCEMAP=1 npm run build 临时打开，不要提交默认值。
    sourcemap: (process.env.ADMIN_SOURCEMAP || localEnv.ADMIN_SOURCEMAP) === '1',
    assetsInlineLimit: 0,
  },
  server: {
    port: 5273,
    strictPort: true,
    proxy: {
      '/admin/api': { target: SERVER_ORIGIN, changeOrigin: false },
      '/actuator': { target: SERVER_ORIGIN, changeOrigin: false },
    },
  },
});
