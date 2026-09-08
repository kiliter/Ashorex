<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import EmbyCourseImport from '@/components/EmbyCourseImport.vue';

/** 两个后台入口复用同一弹窗；原生 dialog 约束焦点，滚动只发生在内容区。 */
const emit = defineEmits<{ close: []; imported: []; busy: [value: boolean] }>();
const dialog = ref<HTMLDialogElement | null>(null);
const importing = ref(false);
let previousOverflow = '';

/** 导入期间禁止关闭，保留逐项执行状态与失败重试结果。 */
function close(): void {
  if (!importing.value) emit('close');
}

/** 将运行状态同步给页面，禁用与当前导入相冲突的操作。 */
function updateBusy(value: boolean): void {
  importing.value = value;
  emit('busy', value);
}

onMounted(() => {
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = 'hidden';
  dialog.value?.showModal();
});
onBeforeUnmount(() => {
  dialog.value?.close();
  document.body.style.overflow = previousOverflow;
});
</script>

<template>
  <dialog ref="dialog" class="import-dialog" aria-labelledby="import-dialog-title" @cancel.prevent="close">
    <header class="between import-dialog-head">
      <h3 id="import-dialog-title">从 Emby 导入课程</h3>
      <button class="wbtn ghost" :disabled="importing" @click="close">关闭</button>
    </header>
    <div class="import-dialog-content">
      <EmbyCourseImport @busy="updateBusy" @imported="emit('imported')" />
    </div>
  </dialog>
</template>

<style scoped>
/* 固定标题和关闭入口，大列表不拉长后台页面；窄屏仍保留弹窗边距。 */
.import-dialog { width: min(1080px, 95vw); max-width: 95vw; max-height: 90vh; padding: 0; border: 1px solid #ddd; border-radius: 16px; color: inherit; background: white; }
.import-dialog[open] { display: flex; flex-direction: column; }
.import-dialog::backdrop { background: rgb(0 0 0 / 35%); }
.import-dialog-head { flex-shrink: 0; padding: 16px 20px; border-bottom: 1px solid #eee; }
.import-dialog-head h3 { margin: 0; }
.import-dialog-content { min-height: 0; overflow: auto; overscroll-behavior: contain; padding: 0 20px 20px; }
</style>
