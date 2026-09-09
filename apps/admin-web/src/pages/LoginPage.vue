<script setup lang="ts">
/**
 * 后台登录页。
 *
 * 两点非显而易见的行为：
 * 1. 登录走 session store 的 `login`，由它完成「取 CSRF 令牌 → 提交表单 → 回写会话」，
 *    本页不直接碰 Cookie 与令牌；失败时 store 已把 401 归一成中文文案，这里原样展示。
 * 2. 路由守卫拦下未登录访问时会把原目标塞进 `redirect` 查询参数，登录成功后必须跳回去，
 *    否则管理员每次会话过期都被丢回概览页。参数类型不可信，只接受字符串。
 */
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
/** 与 App 图标同一套；放在 public/，经 Vite base 发布为 /admin/icon-192.png。 */
const brandMarkSrc = `${import.meta.env.BASE_URL}icon-192.png`;

const username = ref('');
const password = ref('');
const error = ref('');
const submitting = ref(false);

async function submit(): Promise<void> {
  error.value = '';
  submitting.value = true;
  try {
    await session.login(username.value, password.value);
    const redirect = route.query.redirect;
    await router.push(typeof redirect === 'string' ? redirect : { name: 'overview' });
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="login-shell">
    <form class="login-card" @submit.prevent="submit">
      <img class="brand-mark lg" :src="brandMarkSrc" width="56" height="56" alt="上岸" />
      <h1>上岸 · 管理后台</h1>
      <p class="lead">仅限管理员访问。学习端账号请使用 App 登录。</p>

      <div class="wlabel">用户名</div>
      <input v-model="username" class="winput" autocomplete="username" required />

      <div class="wlabel">密码</div>
      <input
        v-model="password"
        class="winput"
        type="password"
        autocomplete="current-password"
        required
      />

      <p v-if="error" class="field-error">{{ error }}</p>

      <button class="wbtn" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>
