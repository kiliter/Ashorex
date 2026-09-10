<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api, ApiError } from '@/api/client';
import { formatBytes, formatInstant } from '@/api/format';

/** 后台只读查看用户手动上报的诊断日志，对应原型 8-13。 */
interface DiagnosticLogRow {
  id: string;
  userId: string;
  username: string;
  sizeBytes: number;
  appVersion: string;
  platform: string;
  uploadedAt: string;
}

const rows = ref<DiagnosticLogRow[]>([]);
const selected = ref<DiagnosticLogRow | null>(null);
const content = ref('');
const loading = ref(true);
const error = ref('');

async function load(): Promise<void> {
  loading.value = true;
  try {
    rows.value = await api.get<DiagnosticLogRow[]>('/diagnostic-logs');
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function open(row: DiagnosticLogRow): Promise<void> {
  selected.value = row;
  content.value = '正在读取…';
  try {
    content.value = await api.getText(`/diagnostic-logs/${row.id}/content`);
  } catch (cause) {
    content.value = cause instanceof ApiError ? cause.message : '读取失败';
  }
}

onMounted(load);
</script>

<template>
  <div class="page-head">
    <h1>诊断日志</h1>
    <p class="lead">
      学员在「我的 → 关于」手动上报的本机日志。只读查看，不含密码、Token 和查询串。不是崩溃聚合平台。
    </p>
  </div>

  <p v-if="error" class="notice danger">{{ error }}</p>
  <p v-if="loading" class="muted">正在加载…</p>

  <div class="wcard flush mt12">
    <div class="table-wrap">
      <table class="wt">
        <thead>
          <tr>
            <th>时间</th>
            <th>用户</th>
            <th>版本</th>
            <th>平台</th>
            <th class="right">大小</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.id"
            :class="{ on: selected?.id === row.id }"
            style="cursor: pointer"
            @click="open(row)"
          >
            <td class="mono">{{ formatInstant(row.uploadedAt) }}</td>
            <td><b>{{ row.username }}</b></td>
            <td>{{ row.appVersion || '—' }}</td>
            <td>{{ row.platform || '—' }}</td>
            <td class="right mono">{{ formatBytes(row.sizeBytes) }}</td>
          </tr>
          <tr v-if="!loading && rows.length === 0">
            <td colspan="5" class="muted">还没有上报记录</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="selected" class="wcard mt12">
    <div class="wcard-head">
      <h2>{{ selected.username }} · {{ selected.appVersion || '未知版本' }}</h2>
      <span class="muted">{{ formatInstant(selected.uploadedAt) }}</span>
    </div>
    <pre class="log-body">{{ content }}</pre>
  </div>
</template>

<style scoped>
.log-body {
  margin: 0;
  max-height: min(60vh, 640px);
  overflow: auto;
  padding: 12px;
  background: var(--ink-soft);
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.45;
}
tr.on td {
  background: var(--blue-soft);
}
</style>
