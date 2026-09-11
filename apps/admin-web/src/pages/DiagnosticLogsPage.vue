<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api, ApiError } from '@/api/client';
import { formatBytes, formatInstant } from '@/api/format';

/** 后台查看并删除用户手动上报的诊断日志，对应原型 8-13。 */
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
const pendingDelete = ref<DiagnosticLogRow | null>(null);
const content = ref('');
const loading = ref(true);
const deleting = ref(false);
const error = ref('');
const notice = ref('');

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

function askDelete(row: DiagnosticLogRow, event?: Event): void {
  event?.stopPropagation();
  pendingDelete.value = row;
}

async function confirmDelete(): Promise<void> {
  const row = pendingDelete.value;
  if (!row || deleting.value) return;
  deleting.value = true;
  try {
    await api.delete(`/diagnostic-logs/${row.id}`);
    notice.value = `已删除 ${row.username} 在 ${formatInstant(row.uploadedAt)} 上报的日志`;
    error.value = '';
    pendingDelete.value = null;
    if (selected.value?.id === row.id) {
      selected.value = null;
      content.value = '';
    }
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除失败';
  } finally {
    deleting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page-head">
    <h1>诊断日志</h1>
    <p class="lead">
      学员在「我的 → 关于」手动上报的本机日志。可查看正文，确认后删除台账与磁盘文件。不含密码、Token 和查询串。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
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
            <th style="text-align: right">操作</th>
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
            <td style="text-align: right">
              <button class="wbtn ghost sm" @click="askDelete(row, $event)">删除</button>
            </td>
          </tr>
          <tr v-if="!loading && rows.length === 0">
            <td colspan="6" class="muted">还没有上报记录</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="selected" class="wcard mt12">
    <div class="wcard-head">
      <h2>{{ selected.username }} · {{ selected.appVersion || '未知版本' }}</h2>
      <button class="wbtn ghost sm" @click="askDelete(selected)">删除这份日志</button>
    </div>
    <p class="muted" style="margin: 0 0 10px">{{ formatInstant(selected.uploadedAt) }}</p>
    <pre class="log-body">{{ content }}</pre>
  </div>

  <div v-if="pendingDelete" class="modal-backdrop">
    <div class="modal danger">
      <h3>删除诊断日志？</h3>
      <p class="sub">
        将删除 {{ pendingDelete.username }} 在 {{ formatInstant(pendingDelete.uploadedAt) }} 上报的这份日志，台账和磁盘文件一起清掉，不能恢复。
      </p>
      <div class="modal-foot">
        <button class="wbtn ghost" :disabled="deleting" @click="pendingDelete = null">取消</button>
        <button class="wbtn red" :disabled="deleting" @click="confirmDelete">
          {{ deleting ? '删除中…' : '确认删除' }}
        </button>
      </div>
    </div>
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
