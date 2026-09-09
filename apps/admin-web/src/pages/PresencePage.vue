<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { api, ApiError } from '@/api/client';
import { formatDuration, formatInstant, presenceLabel, todoTypeLabel } from '@/api/format';

/**
 * 结构严格对应原型 8-2：
 * 顶部状态筛选 Tab + 全宽用户表 + 下方两列（选中用户今日明细 / 常驻手动催办表单）。
 * 手动催办在原型里是右侧常驻卡片，不是弹窗。
 */
interface PresenceView {
  state: string;
  lastHeartbeatAt: string | null;
  lastEffectiveActionAt: string | null;
  idleMinutes: number;
  activity?: { pageLabel: string; stateLabel: string; todoTitle: string | null;
    updatedAt: string | null; background: boolean; stale: boolean };
}

interface PresenceRow {
  userId: string;
  username: string;
  displayName: string;
  timezone: string;
  presence: PresenceView;
  totalCount: number;
  doneCount: number;
  totalMs: number;
  todayNagCount: number;
  unansweredNags: number;
}

interface TodoItem {
  id: string;
  todoType: string;
  title: string;
  status: string;
  progressPermille: number;
  watchedMs: number;
  focusedMs: number;
}

interface DayView {
  date: string;
  todos: TodoItem[];
}

interface PresenceResponse {
  rows: PresenceRow[];
  selectedUserId: string | null;
  selectedDisplayName: string | null;
  selectedDay: DayView | null;
}

type Filter = 'ALL' | 'ONLINE' | 'IDLE' | 'OFFLINE';

const rows = ref<PresenceRow[]>([]);
const filter = ref<Filter>('ALL');
const selected = ref<PresenceRow | null>(null);
const selectedDay = ref<DayView | null>(null);
const error = ref('');
const notice = ref('');
const nagMessage = ref('');
const nagTitle = ref('');
const nagChannel = ref<'AUTO' | 'SERVERCHAN' | 'FULLSCREEN' | 'BARK'>('AUTO');
const submitting = ref(false);
/** 四种渠道共用单选控件；个人 Bark 是渠道选择，不是立即发送按钮。 */
const nagChannels = [
  { value: 'AUTO', label: '自动选择' },
  { value: 'SERVERCHAN', label: 'Server 酱' },
  { value: 'FULLSCREEN', label: '客户端全屏' },
  { value: 'BARK', label: '个人 Bark' },
] as const;
/** 按所选渠道说明收件范围，不把系统异常通知目的地混入个人催办。 */
const channelHint = computed(() => {
  if (nagChannel.value === 'BARK') return '使用该用户在 App 中配置并启用的个人 Bark；其免打扰时段内发送普通通知，其余时间发送重要通知。';
  if (nagChannel.value === 'SERVERCHAN') return '使用 Server 酱投递本次催办。';
  if (nagChannel.value === 'FULLSCREEN') return '通过客户端全屏催办提醒该用户。';
  return '在线时优先客户端全屏；离线或全屏超时后，个人 Bark 已启用则使用 Bark，否则使用 Server 酱。';
});

async function load(): Promise<void> {
  if (loading) return;
  loading = true;
  try {
    const data = await api.get<PresenceResponse>('/presence');
    rows.value = data.rows;
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally { loading = false; }
}

// 只在可见标签页定时更新当前快照，卸载时释放，不积压慢请求。
let refreshTimer: ReturnType<typeof setInterval> | undefined;
let loading = false;
onMounted(() => {
  void load();
  refreshTimer = setInterval(() => { if (!document.hidden) void load(); }, 15000);
});
onUnmounted(() => clearInterval(refreshTimer));

const counts = computed(() => ({
  ALL: rows.value.length,
  ONLINE: rows.value.filter((row) => row.presence.state === 'ONLINE').length,
  IDLE: rows.value.filter((row) => row.presence.state === 'IDLE').length,
  OFFLINE: rows.value.filter((row) => row.presence.state === 'OFFLINE').length,
}));

const visibleRows = computed(() =>
  filter.value === 'ALL'
    ? rows.value
    : rows.value.filter((row) => row.presence.state === filter.value),
);

/** 点「详情」时按用户拉当天明细，避免首屏就把所有人的明细一起查出来。 */
async function openDetail(row: PresenceRow): Promise<void> {
  selected.value = row;
  selectedDay.value = null;
  try {
    const data = await api.get<PresenceResponse>(
      `/presence?userId=${encodeURIComponent(row.userId)}`,
    );
    selectedDay.value = data.selectedDay;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载明细失败';
  }
}

/** 显式点击立即投递才提交，切换渠道只修改表单草稿。 */
async function sendNag(): Promise<void> {
  if (!selected.value || submitting.value) return;
  submitting.value = true;
  error.value = '';
  try {
    await api.post('/presence/nag', {
      userId: selected.value.userId,
      message: nagMessage.value || null,
      title: nagTitle.value || null,
      channel: nagChannel.value,
    });
    notice.value = `已向 ${selected.value.username} 投递一次手动催办`;
    nagMessage.value = '';
    nagTitle.value = '';
    await load();
  } catch (cause) {
    error.value =
      cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : '投递失败';
  } finally {
    submitting.value = false;
  }
}

function dotClass(state: string): string {
  if (state === 'ONLINE') return 'on';
  if (state === 'IDLE') return 'idle';
  return 'off';
}

/** 完成数为 0 标红、全完成标绿，与原型一致。 */
function doneStyle(row: PresenceRow): string {
  if (row.totalCount === 0) return '';
  if (row.doneCount === 0) return 'color:var(--red);font-weight:700';
  if (row.doneCount === row.totalCount) return 'color:var(--green);font-weight:700';
  return '';
}

function barStyle(row: PresenceRow): Record<string, string> {
  if (row.totalCount === 0) return { width: '0%' };
  const percent = Math.round((row.doneCount / row.totalCount) * 100);
  return row.doneCount === 0
    ? { width: '2%', background: 'var(--red)' }
    : { width: `${percent}%` };
}

function statusText(todo: TodoItem): { text: string; color: string } {
  if (todo.status === 'DONE') return { text: '已完成', color: 'var(--green)' };
  if (todo.status === 'IN_PROGRESS') return { text: '进行中', color: 'var(--course)' };
  return { text: todo.todoType === 'TASK' ? '未完成' : '未开始', color: 'var(--red)' };
}
</script>

<template>
  <h1 style="font-size: 19px; font-weight: 800; margin: 0 0 4px">在线与进度</h1>
  <p class="lead" style="font-size: 12.5px; color: var(--muted); margin: 0 0 18px; line-height: 1.7">
    在线判定：<code>now - last_heartbeat_at &lt;= 在线宽限期</code>。空闲判定用「最近一次有效操作」；心跳<b>不算</b>有效操作，只有进度上报、完成、勾选、附件上传、增删 Todo 与催办回应会刷新它。
  </p>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <div class="wtabs">
    <span :class="{ on: filter === 'ALL' }" @click="filter = 'ALL'">全部 {{ counts.ALL }}</span>
    <span :class="{ on: filter === 'ONLINE' }" @click="filter = 'ONLINE'">
      在线 {{ counts.ONLINE }}
    </span>
    <span :class="{ on: filter === 'IDLE' }" @click="filter = 'IDLE'">空闲 {{ counts.IDLE }}</span>
    <span :class="{ on: filter === 'OFFLINE' }" @click="filter = 'OFFLINE'">
      离线 {{ counts.OFFLINE }}
    </span>
  </div>

  <div class="wcard flush">
    <div class="table-wrap">
      <table class="wt">
        <thead>
          <tr>
            <th>用户</th>
            <th>在线状态</th>
            <th>App 当前位置</th>
            <th>最近心跳</th>
            <th>最近有效操作</th>
            <th>今日 Todo</th>
            <th>今日时长</th>
            <th>今日催办</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.userId">
            <td>
              <b>{{ row.username }}</b>
              <div class="muted" style="font-size: 11px">{{ row.timezone }}</div>
            </td>
            <td>
              <span class="dotstate" :class="dotClass(row.presence.state)">
                <i></i>{{ presenceLabel(row.presence.state)
                }}{{ row.presence.state === 'IDLE' ? ` ${row.presence.idleMinutes} 分` : '' }}
              </span>
            </td>
            <td style="min-width: 180px; max-width: 280px; white-space: normal">
              <template v-if="row.presence.activity">
                <div>{{ row.presence.activity.stale ? '最后上报 · ' : '' }}{{ row.presence.activity.background ? 'App 已切后台 · ' : '' }}{{ row.presence.activity.pageLabel }} · {{ row.presence.activity.stateLabel }}</div>
                <div v-if="row.presence.activity.todoTitle">{{ row.presence.activity.todoTitle }}</div>
                <div class="muted" style="font-size: 11px">更新于 {{ formatInstant(row.presence.activity.updatedAt) }}</div>
              </template>
              <span v-else class="muted">尚未上报位置</span>
            </td>
            <td
              class="mono"
              :class="{ muted: row.presence.state !== 'OFFLINE' }"
              :style="row.presence.state === 'OFFLINE' ? 'color:var(--red)' : ''"
            >
              {{ formatInstant(row.presence.lastHeartbeatAt) }}
            </td>
            <td class="mono muted">{{ formatInstant(row.presence.lastEffectiveActionAt) }}</td>
            <td>
              <span :style="doneStyle(row)">{{ row.doneCount }} / {{ row.totalCount }}</span>
              <div class="bar thin mt6" style="width: 88px"><i :style="barStyle(row)"></i></div>
            </td>
            <td class="mono">{{ formatDuration(row.totalMs) }}</td>
            <td>
              <span v-if="row.unansweredNags > 0" class="badge b-red">
                {{ row.unansweredNags }} 未回应
              </span>
              <span v-else class="muted">{{ row.todayNagCount }}</span>
            </td>
            <td style="text-align: right">
              <button class="wbtn ghost sm" @click="openDetail(row)">详情</button>
            </td>
          </tr>
          <tr v-if="visibleRows.length === 0">
            <td colspan="9" class="empty">没有符合条件的用户</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="selected" class="wgrid c2 mt16 nag-detail-grid">
    <div class="wcard">
      <b style="font-size: 14px">{{ selected.username }} · 今日 Todo 明细</b>
      <table class="wt mt10">
        <thead>
          <tr><th>类型</th><th>标题</th><th>状态</th><th>进度 / 时长</th></tr>
        </thead>
        <tbody>
          <tr v-for="todo in selectedDay?.todos ?? []" :key="todo.id">
            <td>
              <span
                class="badge"
                :class="{
                  'b-course': todo.todoType === 'COURSE',
                  'b-focus': todo.todoType === 'FOCUS',
                  'b-task': todo.todoType === 'TASK',
                }"
              >
                {{ todoTypeLabel(todo.todoType) }}
              </span>
            </td>
            <td>{{ todo.title }}</td>
            <td>
              <span :style="{ color: statusText(todo).color }">{{ statusText(todo).text }}</span>
            </td>
            <td class="mono muted">
              {{ Math.round(todo.progressPermille / 10) }}% ·
              {{ formatDuration(todo.watchedMs + todo.focusedMs) }}
            </td>
          </tr>
          <tr v-if="(selectedDay?.todos ?? []).length === 0">
            <td colspan="4" class="empty">今天还没有待办</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="wcard">
      <b style="font-size: 14px">手动催办</b>
      <p class="lead" style="margin: 6px 0 12px">
        收件人：{{ selected.displayName || selected.username }}。选择渠道并填写内容后，点击“立即投递”。
      </p>

      <fieldset class="nag-channel-group" :disabled="submitting" aria-describedby="nag-channel-hint">
        <legend class="wlabel">投递渠道</legend>
        <div class="nag-channel-options">
          <label v-for="channel in nagChannels" :key="channel.value" class="pill nag-channel" :class="{ on: nagChannel === channel.value }">
            <input v-model="nagChannel" type="radio" name="personal-nag-channel" :value="channel.value" />
            {{ channel.label }}
          </label>
        </div>
      </fieldset>
      <p id="nag-channel-hint" class="muted nag-channel-hint" aria-live="polite">{{ channelHint }}</p>

      <label for="personal-nag-title" class="wlabel nag-field-label">催办标题（可选）</label>
      <input id="personal-nag-title" v-model="nagTitle" :disabled="submitting" class="winput" maxlength="80" placeholder="不填写时使用默认标题" />
      <label for="personal-nag-message" class="wlabel nag-field-label">附加说明（可选）</label>
      <textarea
        id="personal-nag-message"
        v-model="nagMessage"
        :disabled="submitting"
        maxlength="1000"
        class="winput"
        style="min-height: 64px"
        placeholder="今天一项都没开始，先把第一项做完。"
      />

      <div class="wbtn-row mt12">
        <button class="wbtn" :disabled="submitting" @click="sendNag">
          {{ submitting ? '投递中…' : '立即投递' }}
        </button>
        <button class="wbtn ghost" :disabled="submitting" @click="selected = null">取消</button>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        手动催办同样写入催办记录，标记 <code>trigger=MANUAL</code> 与操作管理员，并计入每日上限。
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 渠道与操作分区，四个选项共享选中态；窄屏允许换行，避免 Bark 挤入操作按钮。 */
.nag-channel-group { margin: 0; padding: 0; border: 0; min-width: 0; }
.nag-channel-options { display: flex; flex-wrap: wrap; gap: 8px; }
.nag-channel { min-height: 44px; gap: 7px; }
.nag-channel input { margin: 0; width: 15px; height: 15px; accent-color: var(--blue); }
.nag-channel:focus-within { outline: 2px solid var(--blue); outline-offset: 2px; }
.nag-channel-group:disabled .nag-channel { cursor: wait; opacity: .65; }
.nag-channel-hint { font-size: 12px; line-height: 1.6; margin: 10px 0 0; }
.nag-field-label { display: block; margin-top: 14px; }
.nag-detail-grid > .wcard { min-width: 0; }
@media (max-width: 900px) { .nag-detail-grid.wgrid.c2 { grid-template-columns: minmax(0, 1fr); } }
</style>
