/**
 * 管理后台整页截图工具，仅用于本机视觉验证，不参与 npm run build 与生产构建。
 *
 * 依赖 playwright-core，刻意不写进 package.json：admin-web 的 package.json 会被
 * Maven 的 npm ci 使用，把浏览器驱动列为依赖会拖慢并污染服务端打包。请按需临时安装：
 *
 *   mkdir -p /tmp/shotkit && cd /tmp/shotkit
 *   npm init -y && npm install playwright-core
 *   NODE_PATH=/tmp/shotkit/node_modules \
 *   CHROME_BIN="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
 *   node /path/to/apps/admin-web/shot.mjs
 *
 * 凭据从环境变量读取，不在仓库里写死密码：
 *   ADMIN_USER（默认 admin）、ADMIN_PASSWORD（必填）。
 */

import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
// 优先用隔离安装的 playwright-core；同时兼容已装完整 playwright 的环境。
const { chromium } = (() => {
  for (const candidate of ['playwright-core', 'playwright']) {
    try {
      return require(candidate);
    } catch {
      // 继续尝试下一个候选包。
    }
  }
  throw new Error(
    '未找到 playwright-core。请按文件头注释在临时目录安装，并通过 NODE_PATH 指向它。',
  );
})();

const BASE = process.env.BASE ?? 'http://127.0.0.1:18091';
const OUT = process.env.OUT ?? '/tmp/shots';
const USER = process.env.ADMIN_USER ?? 'admin';
const PASSWORD = process.env.ADMIN_PASSWORD;

if (!PASSWORD) {
  console.error('缺少 ADMIN_PASSWORD 环境变量：不要把后台密码写进仓库。');
  process.exit(1);
}

const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN });
const page = await browser.newPage({ viewport: { width: 1400, height: 1100 } });

await page.goto(`${BASE}/admin/login`, { waitUntil: 'networkidle' });
await page.fill('input[autocomplete="username"]', USER);
await page.fill('input[autocomplete="current-password"]', PASSWORD);
await page.click('button[type="submit"]');
await page.waitForURL(/\/admin\/?$/, { timeout: 10000 });
await page.waitForLoadState('networkidle');

// 支持用 ROUTES 环境变量指定要截图的页面：ROUTES="users:/admin/users,settings:/admin/settings"
const routes = process.env.ROUTES
  ? process.env.ROUTES.split(',').map((entry) => entry.split(':').map((part) => part.trim()))
  : [
      ['overview', '/admin/'],
      ['presence', '/admin/presence'],
      ['nag-policy', '/admin/nag-policy'],
      ['nags', '/admin/nags'],
      ['courses', '/admin/courses'],
      ['emby-sync', '/admin/emby-sync'],
      ['supervisions', '/admin/supervisions'],
      ['users', '/admin/users'],
      ['archive', '/admin/archive'],
      ['settings', '/admin/settings'],
    ];
for (const [name, path] of routes) {
  await page.goto(`${BASE}${path}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
  console.log('已截图', name);
}
await browser.close();
