<script setup lang="ts">
import { onMounted } from 'vue';
import { useSessionStore } from '@/stores/session';
import IconSprite from '@/components/IconSprite.vue';

const session = useSessionStore();

// 启动时先探测会话并取回 CSRF Cookie，路由守卫依赖 resolved 标记。
onMounted(async () => {
  if (!session.resolved) {
    await session.refresh();
  }
});
</script>

<template>
  <!-- 图标精灵必须挂在根节点，供各页 <use href="#i-xxx"> 引用。 -->
  <IconSprite />
  <RouterView />
</template>
