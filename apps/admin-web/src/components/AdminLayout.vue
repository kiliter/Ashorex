<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';

const router = useRouter();
const session = useSessionStore();
/** 与 App 图标同一套；放在 public/，经 Vite base 发布为 /admin/icon-192.png。 */
const brandMarkSrc = `${import.meta.env.BASE_URL}icon-192.png`;

/**
 * 导航顺序与图标严格照原型 8-1 的 .web-nav：
 * 概览 → 在线与进度 → 催办策略 → 催办记录 → 课程库 → Emby 同步 → 督学关系 → 用户 → 归档区 → 运行配置。
 * 原型没有分组标签，不要自行添加。
 */
const NAV_ITEMS = [
  { name: 'overview', title: '概览', icon: 'i-chart' },
  { name: 'presence', title: '在线与进度', icon: 'i-pulse' },
  { name: 'nag-policy', title: '催办策略', icon: 'i-bell' },
  { name: 'nags', title: '催办记录', icon: 'i-alert' },
  { name: 'courses', title: '课程库', icon: 'i-book' },
  { name: 'emby-sync', title: 'Emby 同步', icon: 'i-user' },
  { name: 'supervisions', title: '督学关系', icon: 'i-star' },
  { name: 'users', title: '用户', icon: 'i-lock' },
  { name: 'archive', title: '归档区', icon: 'i-archive' },
  { name: 'settings', title: '运行配置', icon: 'i-server' },
  { name: 'upgrades', title: '版本与升级', icon: 'i-server' },
  { name: 'diagnostic-logs', title: '诊断日志', icon: 'i-log' },
] as const;

async function signOut(): Promise<void> {
  await session.logout();
  await router.push({ name: 'login' });
}
</script>

<template>
  <div class="admin-shell" :class="{ 'bounded-shell': ['nag-policy', 'nags', 'emby-sync', 'settings', 'upgrades', 'diagnostic-logs'].includes(String($route.name)) }">
    <nav class="admin-nav">
      <div class="brand">
        <img class="brand-mark" :src="brandMarkSrc" width="28" height="28" alt="" />
        上岸 · 管理后台
      </div>

      <RouterLink
        v-for="item in NAV_ITEMS"
        :key="item.name"
        :to="{ name: item.name }"
        :class="{ on: $route.name === item.name }"
      >
        <svg class="icon s18"><use :href="`#${item.icon}`" /></svg>
        {{ item.title }}
      </RouterLink>

      <div class="nav-foot">
        <div>{{ session.username ?? '—' }}</div>
        <button class="wbtn ghost sm" style="margin-top: 8px" @click="signOut">退出登录</button>
      </div>
    </nav>
    <main class="admin-main">
      <RouterView />
    </main>
  </div>
</template>
