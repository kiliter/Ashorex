import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { useSessionStore } from '@/stores/session';

/** 侧边导航分组；order 决定展示顺序，登录页不出现在导航里。 */
export interface NavMeta {
  title: string;
  group?: '监控' | '内容' | '人员' | '系统';
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginPage.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/components/AdminLayout.vue'),
    children: [
      {
        path: '',
        name: 'overview',
        component: () => import('@/pages/OverviewPage.vue'),
        meta: { title: '概览', group: '监控' },
      },
      {
        path: 'presence',
        name: 'presence',
        component: () => import('@/pages/PresencePage.vue'),
        meta: { title: '在线监控', group: '监控' },
      },
      {
        path: 'nags',
        name: 'nags',
        component: () => import('@/pages/NagsPage.vue'),
        meta: { title: '催办记录', group: '监控' },
      },
      {
        path: 'nag-policy',
        name: 'nag-policy',
        component: () => import('@/pages/NagPolicyPage.vue'),
        meta: { title: '催办策略', group: '监控' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/pages/CoursesPage.vue'),
        meta: { title: '课程管理', group: '内容' },
      },
      {
        path: 'emby-sync',
        name: 'emby-sync',
        component: () => import('@/pages/EmbySyncPage.vue'),
        meta: { title: 'Emby 同步', group: '内容' },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/pages/UsersPage.vue'),
        meta: { title: '用户管理', group: '人员' },
      },
      {
        path: 'supervisions',
        name: 'supervisions',
        component: () => import('@/pages/SupervisionsPage.vue'),
        meta: { title: '督学关系', group: '人员' },
      },
      {
        path: 'archive',
        name: 'archive',
        component: () => import('@/pages/ArchivePage.vue'),
        meta: { title: '归档区', group: '系统' },
      },
      {
        path: 'upgrades',
        name: 'upgrades',
        component: () => import('@/pages/UpgradesPage.vue'),
        meta: { title: '版本与升级', group: '系统' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/pages/SettingsPage.vue'),
        meta: { title: '运行配置', group: '系统' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

export const router = createRouter({
  // 与 vite base 一致；SPA fallback 由服务端 forward 到 index.html。
  history: createWebHistory('/admin/'),
  routes,
});

router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (!session.resolved) {
    await session.refresh();
  }
  if (to.meta.public) {
    // 已登录时不再停留在登录页。
    return session.authenticated ? { name: 'overview' } : true;
  }
  if (!session.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  return true;
});
