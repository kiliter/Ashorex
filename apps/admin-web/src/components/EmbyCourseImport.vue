<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { api, ApiError } from '@/api/client';

/** 同步页与课程库复用选择器；浏览只读，导入按选择快照逐门串行执行。 */
interface Candidate {
  id: string;
  name: string;
  itemType: string;
  hasPrimaryImage: boolean;
  courseId: string | null;
  status: 'NEW' | 'ACTIVE' | 'ARCHIVED';
}
interface ImportResult { courseId: string; title: string; status: string }
interface ResultRow { id: string; name: string; ok: boolean; message: string }
const props = withDefaults(defineProps<{ refreshKey?: number }>(), { refreshKey: 0 });
const emit = defineEmits<{ imported: []; busy: [value: boolean] }>();
const candidates = ref<Candidate[]>([]);
const selected = ref<string[]>([]);
const query = ref('');
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
const importing = ref(false);
const error = ref('');
const results = ref<ResultRow[]>([]);
const progress = ref('');
const counts = ref<Record<string, number | 'error'>>({});
const coverFailed = ref<Record<string, boolean>>({});
let generation = 0;
let disposed = false;
const filtered = computed(() => candidates.value.filter(row => row.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())));
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)));
const visible = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize));
const failures = computed(() => results.value.filter(row => !row.ok));

/** 只向用户展示标准错误消息，不拼接请求内容。 */
function message(cause: unknown): string {
  return cause instanceof ApiError ? cause.message : '请求失败，请刷新或重试';
}

/** 失败时清空失效候选，避免继续提交旧绑定范围；每轮令牌隔离过期的计数请求。 */
async function load(): Promise<void> {
  if (importing.value) return;
  const current = ++generation;
  loading.value = true;
  error.value = '';
  selected.value = [];
  results.value = [];
  progress.value = '';
  counts.value = {};
  coverFailed.value = {};
  candidates.value = [];
  page.value = 1;
  try {
    const rows = await api.get<Candidate[]>('/emby-import/candidates');
    if (!disposed && current === generation) candidates.value = rows;
  } catch (cause) {
    if (current === generation) error.value = message(cause);
  } finally {
    if (current === generation) loading.value = false;
  }
}

/** 全选作用于全部搜索结果而非当前页，已导入和归档行始终排除。 */
function selectFiltered(): void {
  selected.value = [...new Set([...selected.value, ...filtered.value.filter(row => row.status === 'NEW').map(row => row.id)])];
}

/** 当前页计数最多两个并行请求，翻页后停止旧队列，不批量拉取所有课程课时。 */
async function loadCounts(rows: Candidate[]): Promise<void> {
  const current = generation;
  const ids = rows.filter(row => counts.value[row.id] === undefined).map(row => row.id);
  async function worker(): Promise<void> {
    while (ids.length && current === generation && !disposed) {
      const id = ids.shift();
      if (!id || !visible.value.some(row => row.id === id)) continue;
      try {
        const detail = await api.get<{resourceCount: number}>(`/emby-import/details?sourceId=${encodeURIComponent(id)}`);
        if (current === generation) counts.value[id] = detail.resourceCount;
      } catch {
        if (current === generation) counts.value[id] = 'error';
      }
    }
  }
  await Promise.all([worker(), worker()]);
}

/** 一门一请求保留逐项结果；重放由服务端按来源身份幂等处理。 */
async function runImport(ids: string[]): Promise<void> {
  if (!ids.length || importing.value) return;
  importing.value = true;
  emit('busy', true);
  results.value = [];
  error.value = '';
  try {
    for (const [index, id] of ids.entries()) {
      const row = candidates.value.find(item => item.id === id);
      if (!row || row.status !== 'NEW') continue;
      progress.value = `正在导入 ${index + 1} / ${ids.length}：${row.name}`;
      try {
        const result = await api.post<ImportResult>('/emby-import', { sourceId: id });
        row.courseId = result.courseId;
        row.status = result.status === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE';
        results.value.push({ id, name: row.name, ok: true, message: result.status === 'IMPORTED' ? '导入成功' : result.status === 'ARCHIVED' ? '已归档，请到归档区恢复' : '已导入，未重复创建' });
        selected.value = selected.value.filter(item => item !== id);
      } catch (cause) {
        results.value.push({ id, name: row.name, ok: false, message: message(cause) });
      }
    }
    progress.value = `导入结束：成功或已存在 ${results.value.filter(row => row.ok).length} 门 · 失败 ${failures.value.length} 门`;
  } finally {
    importing.value = false;
    emit('busy', false);
    emit('imported');
  }
}
watch(query, () => { page.value = 1; });
watch(visible, rows => { void loadCounts(rows); });
watch(() => props.refreshKey, () => { void load(); });
// 导入期间留在当前页，避免用户误以为切页会取消已经提交的课程。
onBeforeRouteLeave(() => !importing.value);
onMounted(load);
onBeforeUnmount(() => { disposed = true; generation++; });
</script>

<template>
  <section class="wcard mt16" aria-label="从媒体库导入课程" :aria-busy="loading || importing">
    <div class="between">
      <b>从媒体库导入课程</b>
      <button class="wbtn ghost sm" :disabled="loading || importing" @click="load">刷新课程</button>
    </div>
    <p class="lead">展示已保存媒体库内的课程。勾选后导入，已导入课程在课程库同步更新；已归档课程请到归档区恢复。</p>
    <div class="row import-actions">
      <input v-model="query" class="winput" placeholder="搜索课程名称" aria-label="搜索课程名称" :disabled="loading || importing" />
      <button class="wbtn ghost" :disabled="loading || importing || !filtered.length" @click="selectFiltered">全选未导入课程</button>
      <button class="wbtn ghost" :disabled="importing || !selected.length" @click="selected = []">清空选择</button>
      <button class="wbtn" :disabled="loading || importing || !selected.length" @click="runImport([...selected])">导入所选（{{ selected.length }}）</button>
    </div>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取已绑定媒体库的课程…</p>
    <div v-else class="table-wrap mt12">
      <table class="wt">
        <thead><tr><th>选择</th><th>封面</th><th>课程</th><th>课时</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="row in visible" :key="row.id">
            <td><label class="select-target"><input v-model="selected" type="checkbox" :value="row.id" :disabled="importing || row.status !== 'NEW'" :aria-label="`选择${row.name}`" /></label></td>
            <td><img v-if="row.hasPrimaryImage && !coverFailed[row.id]" class="import-cover" :src="`/admin/api/emby-import/cover?sourceId=${encodeURIComponent(row.id)}`" :alt="`${row.name}封面`" loading="lazy" @error="coverFailed[row.id] = true" /><span v-else class="muted">暂无封面</span></td>
            <td><b>{{ row.name }}</b><div class="cell-sub">{{ row.itemType === 'Series' ? '剧集课程' : '单视频课程' }}</div></td>
            <td>{{ counts[row.id] === 'error' ? '暂不可用' : counts[row.id] ?? '读取中…' }}</td>
            <td>{{ row.status === 'NEW' ? '未导入' : row.status === 'ARCHIVED' ? '已归档' : '已导入' }}</td>
          </tr>
          <tr v-if="!filtered.length"><td colspan="5" class="empty">{{ candidates.length ? '没有匹配的课程' : '暂无可导入课程，请先保存视频媒体库绑定，并确认 Emby 中存在剧集或电影。' }}</td></tr>
        </tbody>
      </table>
    </div>
    <div class="between mt12"><span>共 {{ filtered.length }} 门 · 已选 {{ selected.length }} 门 · 第 {{ page }} / {{ pages }} 页</span><div class="row"><button class="wbtn ghost sm" :disabled="page <= 1 || importing" @click="page--">上一页</button><button class="wbtn ghost sm" :disabled="page >= pages || importing" @click="page++">下一页</button></div></div>
    <p v-if="progress" role="status" aria-live="polite">{{ progress }}</p>
    <div v-if="results.length" class="import-results">
      <p v-for="row in results" :key="row.id" :class="{ 'field-error': !row.ok }">{{ row.name }}：{{ row.message }}</p>
    </div>
    <button v-if="failures.length" class="wbtn ghost" :disabled="importing" @click="runImport(failures.map(row => row.id))">重试失败项（{{ failures.length }}）</button>
  </section>
</template>

<style scoped>
/* 沿用后台表格样式，小屏操作区换行，复选框点击区域保持 44px。 */
.import-actions { flex-wrap: wrap; gap: 8px; }
.import-actions .winput { flex: 1; min-width: 160px; }
.select-target { display: inline-flex; width: 44px; height: 44px; align-items: center; justify-content: center; }
.import-cover { width: 48px; height: 64px; object-fit: cover; border-radius: 6px; }
.import-results { max-height: 240px; overflow: auto; }
</style>
