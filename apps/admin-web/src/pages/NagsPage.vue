<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api } from '@/api/client';
import { formatDuration, formatInstant, nagStatusLabel, todoTypeLabel } from '@/api/format';

/**
 * 两个 Tab 合并两屏原型：
 * - 「催办记录」对应 8-4：筛选条 + 状态分布 + 催办流水表。
 * - 「删除台账」对应 8-8：指标卡 + 原因分布 / 按学员 + 删除流水表。
 *
 * 两屏共用 GET /nags 一次返回的数据（催办、用户名映射、删除台账、原因计数、状态计数），
 * 因此筛选全部在客户端完成，不额外打接口。
 */
interface NagRecord {
  id: string;
  userId: string;
  localDate: string;
  thresholdLevel: number;
  trigger: string;
  triggeredByUserId: string | null;
  idleMinutes: number;
  pendingCount: number;
  title?: string;
  message: string;
  requireReason: boolean;
  status: string;
  deliveredAt: string | null;
  respondedAt: string | null;
  reasonTag: string | null;
  reasonText: string | null;
  supervisorUserIdSnapshot: string | null;
  createdAt: string;
}

interface DeletionRecord {
  id: string;
  userId: string;
  todoId: string;
  todoType: string;
  localDate: string;
  titleSnapshot: string;
  resourceId: string | null;
  progressSnapshotJson: string | null;
  reasonTag: string;
  reasonText: string;
  supervisorUserIdSnapshot: string | null;
  deletedAt: string;
}

interface StatusCount {
  status: string;
  count: number;
}

/**
 * 一次渠道投递尝试，来自服务端 nag_deliveries。
 *
 * `detail` 是渠道写入的脱敏原因，不含 SendKey、目标地址与堆栈，可直接展示。
 */
interface DeliveryAttempt {
  id: string;
  channel: string;
  status: string;
  detail: string;
  createdAt: string;
}

/** 管理员取消与重投历史，操作人与时间取服务端。 */
interface AdminAction { nagId: string; action: string; actor: string; createdAt: string }
interface NagRecordsResponse {
  nags: NagRecord[];
  usernames: Record<string, string>;
  deletions: DeletionRecord[];
  reasonCounts: Record<string, number>;
  statusCounts: StatusCount[];
  deliveryAttempts: Record<string, DeliveryAttempt[]>;
  adminActions: AdminAction[];
}

/** 删除时进度快照，由服务端写入 todo_deletions.progress_snapshot_json。 */
interface ProgressSnapshot {
  status?: string;
  positionMs?: number;
  page?: number;
  watchedMs?: number;
  focusedMs?: number;
  focusState?: string;
  targetPermille?: number | null;
}

const TRIGGER_LABELS: Record<string, string> = {
  AUTO: '自动',
  MANUAL: '手动',
  SUPERVISOR: '督学',
};

const REASON_LABELS: Record<string, string> = {
  TOO_MANY_PLANNED: '计划排太多',
  TEMP_BUSY: '今天临时有事',
  ADDED_BY_MISTAKE: '加错了',
  SWITCHED_TO_OTHER: '改到别的课时',
  GAVE_UP: '不想学了',
};

const STATUS_ORDER = ['PENDING', 'DELIVERED', 'RESPONDED', 'EXPIRED', 'CANCELLED'];

const CHANNEL_LABELS: Record<string, string> = {
  FULLSCREEN: '全屏',
  SERVERCHAN: 'Server 酱',
  BARK: '个人 Bark',
};

/** nag_deliveries.status 只有这三档：SENT 已发出、SHOWN 客户端已展示、FAILED 投递失败。 */
const ATTEMPT_STATUS_LABELS: Record<string, string> = {
  SENT: '已发出',
  SHOWN: '已展示',
  FAILED: '失败',
};

const nags = ref<NagRecord[]>([]);
const deletions = ref<DeletionRecord[]>([]);
const usernames = ref<Record<string, string>>({});
const reasonCounts = ref<Record<string, number>>({});
const statusCounts = ref<StatusCount[]>([]);
const deliveryAttempts = ref<Record<string, DeliveryAttempt[]>>({});
const loading = ref(true);
const error = ref('');
const notice = ref('');
const operationId = ref('');
const adminActions = ref<AdminAction[]>([]);

/** 只有全部失败的待投递项可操作，服务端仍会重新裁决。 */
function canManage(nag: NagRecord): boolean {
  const attempts = attemptsOf(nag);
  return nag.status === 'PENDING' && attempts.length > 0 && attempts.every(row => row.status === 'FAILED');
}

/** 使用最后一次流水作为版本，避免刷新前重复发送同一轮请求。 */
async function manage(nag: NagRecord, action: 'cancel' | 'retry'): Promise<void> {
  if (operationId.value || !canManage(nag)) return;
  if (action === 'cancel' && !window.confirm('确认取消这条失败催办的投递？取消后不再投递，历史记录保留。')) return;
  operationId.value = nag.id;
  notice.value = '';
  try {
    await api.post(`/nags/${encodeURIComponent(nag.id)}/${action}`, { latestAttemptId: attemptsOf(nag).at(-1)?.id });
    await load();
    const current = nags.value.find(row => row.id === nag.id);
    notice.value = action === 'cancel' ? '已取消投递，历史记录已保留' : current?.status === 'DELIVERED' ? '重新投递成功' : '重新投递仍未成功，请查看最新失败原因';
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '操作失败';
    await load();
    error.value = message;
  } finally { operationId.value = ''; }
}

const tab = ref<'NAGS' | 'DELETIONS'>('NAGS');
const nagUserFilter = ref('ALL');
const nagTriggerFilter = ref('ALL');
const nagAnswerFilter = ref<'ALL' | 'ANSWERED' | 'UNANSWERED'>('ALL');
const deletionUserFilter = ref('ALL');
const deletionReasonFilter = ref('ALL');

async function load(): Promise<void> {
  try {
    const data = await api.get<NagRecordsResponse>('/nags');
    nags.value = data.nags;
    deletions.value = data.deletions;
    usernames.value = data.usernames;
    reasonCounts.value = data.reasonCounts;
    statusCounts.value = data.statusCounts;
    deliveryAttempts.value = data.deliveryAttempts ?? {};
    adminActions.value = data.adminActions ?? [];
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

/** 记录里只有 userId，展示一律走服务端下发的 usernames 映射。 */
function username(userId: string | null): string {
  if (!userId) {
    return '—';
  }
  return usernames.value[userId] ?? userId.slice(0, 8);
}

function triggerLabel(trigger: string): string {
  return TRIGGER_LABELS[trigger] ?? trigger;
}

function reasonLabel(tag: string | null): string {
  if (!tag) {
    return '—';
  }
  return REASON_LABELS[tag] ?? tag;
}

/** 未回应 = 状态仍为 PENDING 或 DELIVERED，与后端 Nag.awaitingResponse() 同口径。 */
function awaiting(nag: NagRecord): boolean {
  return nag.status === 'PENDING' || nag.status === 'DELIVERED';
}

/** 回应时延取「创建 → 回应」间隔，格式 `M:SS`；未回应显示破折号。 */
function latency(nag: NagRecord): string {
  if (!nag.respondedAt) {
    return '—';
  }
  const seconds = Math.max(
    0,
    Math.round((new Date(nag.respondedAt).getTime() - new Date(nag.createdAt).getTime()) / 1000),
  );
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/** 某条催办的投递尝试，按服务端返回顺序（created_at 升序）展示。 */
function attemptsOf(nag: NagRecord): DeliveryAttempt[] {
  return deliveryAttempts.value[nag.id] ?? [];
}

function channelLabel(channel: string): string {
  return CHANNEL_LABELS[channel] ?? channel;
}

function attemptStatusLabel(status: string): string {
  return ATTEMPT_STATUS_LABELS[status] ?? status;
}

/** 单条尝试展示为「全屏：失败（渠道不可用）」；成功时不重复原因，避免行高失控。 */
function attemptText(attempt: DeliveryAttempt): string {
  const head = `${channelLabel(attempt.channel)}：${attemptStatusLabel(attempt.status)}`;
  if (attempt.status === 'FAILED' && attempt.detail) {
    return `${head}（${attempt.detail}）`;
  }
  return head;
}

/**
 * 投递状态点。
 *
 * 关键区分：`PENDING` 且已有失败尝试说明渠道全试过且都失败，不能再显示成「待投递」，
 * 否则管理员无法把「还没轮到扫描」和「两个渠道都打不通」分开。
 */
function deliveryDot(nag: NagRecord): { cls: string; text: string } {
  const attempts = attemptsOf(nag);
  const allFailed = attempts.length > 0 && attempts.every((attempt) => attempt.status === 'FAILED');
  if (nag.status === 'PENDING') {
    if (allFailed) {
      return { cls: 'off', text: `投递失败（${attempts.length} 次）` };
    }
    return { cls: 'idle', text: '待投递' };
  }
  if (nag.status === 'CANCELLED') return { cls: 'off', text: '已取消' };
  if (nag.status === 'EXPIRED') {
    return { cls: 'off', text: '已过期' };
  }
  return { cls: 'on', text: '已投递' };
}

/** 至少一个渠道失败就上红：这类记录需要管理员去查 Server 酱配置或学员在线状态。 */
function hasFailedAttempt(nag: NagRecord): boolean {
  return attemptsOf(nag).some((attempt) => attempt.status === 'FAILED');
}

const failedDeliveryCount = computed(
  () => nags.value.filter((nag) => nag.status === 'PENDING' && hasFailedAttempt(nag)).length,
);

const nagUserOptions = computed(() => {
  const ids = new Set(nags.value.map((nag) => nag.userId));
  return [...ids].map((id) => ({ id, name: username(id) }));
});

const visibleNags = computed(() =>
  nags.value.filter((nag) => {
    if (nagUserFilter.value !== 'ALL' && nag.userId !== nagUserFilter.value) {
      return false;
    }
    if (nagTriggerFilter.value !== 'ALL' && nag.trigger !== nagTriggerFilter.value) {
      return false;
    }
    if (nagAnswerFilter.value === 'ANSWERED' && awaiting(nag)) {
      return false;
    }
    if (nagAnswerFilter.value === 'UNANSWERED' && !awaiting(nag)) {
      return false;
    }
    return true;
  }),
);

const awaitingCount = computed(() => nags.value.filter(awaiting).length);
const respondedNags = computed(() => nags.value.filter((nag) => nag.respondedAt !== null));

/** 平均回应时延只统计已回应记录；无样本时展示破折号。 */
const averageLatency = computed(() => {
  if (respondedNags.value.length === 0) {
    return '—';
  }
  const total = respondedNags.value.reduce(
    (sum, nag) => sum + (new Date(nag.respondedAt as string).getTime() - new Date(nag.createdAt).getTime()),
    0,
  );
  const seconds = Math.round(total / respondedNags.value.length / 1000);
  return `${Math.floor(seconds / 60)} 分 ${String(seconds % 60).padStart(2, '0')} 秒`;
});

const statusTotal = computed(() => statusCounts.value.reduce((sum, item) => sum + item.count, 0));

/** 状态分布固定按状态机顺序展示四档，缺失的档位补 0，避免顺序随数据抖动。 */
const statusDistribution = computed(() =>
  STATUS_ORDER.map((status) => {
    const count = statusCounts.value.find((item) => item.status === status)?.count ?? 0;
    const percent = statusTotal.value === 0 ? 0 : Math.round((count / statusTotal.value) * 100);
    return { status, count, percent };
  }),
);

function statusBarClass(status: string): string {
  if (status === 'RESPONDED') return 'green';
  if (status === 'EXPIRED') return 'red';
  if (status === 'PENDING') return 'ochre';
  return '';
}

/**
 * 分布卡里「待投递」有多少条其实是「渠道全试过且都失败」。
 *
 * 口径说明：投递失败没有 FAILED 终态，管理员取消才进入 CANCELLED，
 * 所以投递失败的催办在 `nags.status` 上仍然是 PENDING。分布卡按状态聚合、流水行按投递尝试
 * 判定，两处口径不同，因此在分布卡标注失败条数；取消后从待投递数量中移除。
 *
 * 数据来源与流水表一致（服务端返回的近期催办 + deliveryAttempts），因此可能少于分布卡按全量
 * 状态聚合出的 PENDING 总数，标注文案里点明「其中」而不是等号。
 */
const pendingAllFailedCount = computed(
  () =>
    nags.value.filter((nag) => {
      if (nag.status !== 'PENDING') {
        return false;
      }
      const attempts = attemptsOf(nag);
      return attempts.length > 0 && attempts.every((attempt) => attempt.status === 'FAILED');
    }).length,
);

const deletionUserOptions = computed(() => {
  const ids = new Set(deletions.value.map((item) => item.userId));
  return [...ids].map((id) => ({ id, name: username(id) }));
});

const deletionReasonOptions = computed(() => Object.keys(reasonCounts.value));

const visibleDeletions = computed(() =>
  deletions.value.filter((item) => {
    if (deletionUserFilter.value !== 'ALL' && item.userId !== deletionUserFilter.value) {
      return false;
    }
    if (deletionReasonFilter.value !== 'ALL' && item.reasonTag !== deletionReasonFilter.value) {
      return false;
    }
    return true;
  }),
);

const deletionTotal = computed(() => deletions.value.length);
const gaveUpCount = computed(() => reasonCounts.value.GAVE_UP ?? 0);

/** 按类型拆分删除数，对应原型「课程 x · 专注 y · 待办 z」。 */
const deletionTypeBreakdown = computed(() => {
  const counts: Record<string, number> = { COURSE: 0, FOCUS: 0, TASK: 0 };
  for (const item of deletions.value) {
    if (item.todoType in counts) {
      counts[item.todoType] += 1;
    }
  }
  return counts;
});

const reasonDistribution = computed(() => {
  const entries = Object.entries(reasonCounts.value);
  const total = entries.reduce((sum, [, count]) => sum + count, 0);
  return entries
    .sort((left, right) => right[1] - left[1])
    .map(([tag, count]) => ({
      tag,
      count,
      percent: total === 0 ? 0 : Math.round((count / total) * 100),
    }));
});

/** 按学员聚合删除数与最高频原因，用于识别「排太多」还是「不想学」。 */
const deletionByLearner = computed(() => {
  const grouped = new Map<string, { userId: string; count: number; reasons: Record<string, number> }>();
  for (const item of deletions.value) {
    const entry = grouped.get(item.userId) ?? { userId: item.userId, count: 0, reasons: {} };
    entry.count += 1;
    entry.reasons[item.reasonTag] = (entry.reasons[item.reasonTag] ?? 0) + 1;
    grouped.set(item.userId, entry);
  }
  return [...grouped.values()]
    .map((entry) => {
      const top = Object.entries(entry.reasons).sort((left, right) => right[1] - left[1])[0];
      return {
        userId: entry.userId,
        count: entry.count,
        topReason: top ? top[0] : null,
        gaveUp: entry.reasons.GAVE_UP ?? 0,
      };
    })
    .sort((left, right) => right.count - left.count);
});

const affectedLearners = computed(() => deletionByLearner.value.length);

function parseSnapshot(json: string | null): ProgressSnapshot | null {
  if (!json) {
    return null;
  }
  try {
    return JSON.parse(json) as ProgressSnapshot;
  } catch {
    return null;
  }
}

/** 删除时进度：课程按千分比与观看时长，专注按已专注时长，待办只有完成状态。 */
function snapshotText(item: DeletionRecord): { text: string; dim: boolean } {
  const snapshot = parseSnapshot(item.progressSnapshotJson);
  if (!snapshot) {
    return { text: '—', dim: true };
  }
  const done = snapshot.status === 'DONE';
  if (item.todoType === 'FOCUS') {
    const focused = snapshot.focusedMs ?? 0;
    return {
      text: `${done ? '已完成' : '未完成'} · 已专注 ${formatDuration(focused)}`,
      dim: focused === 0,
    };
  }
  if (item.todoType === 'TASK') {
    return { text: done ? '已完成' : '未完成', dim: !done };
  }
  const watched = snapshot.watchedMs ?? 0;
  const permille = snapshot.targetPermille ?? 0;
  if (watched === 0 && permille === 0) {
    return { text: '0% · 未开始', dim: true };
  }
  return { text: `${Math.round(permille / 10)}% · ${formatDuration(watched)}`, dim: false };
}

function typeBadgeClass(todoType: string): string {
  if (todoType === 'COURSE') return 'b-course';
  if (todoType === 'FOCUS') return 'b-focus';
  return 'b-task';
}

/** 「不想学了」标红，其余用中性色，避免只靠颜色区分时误读。 */
function reasonBadgeClass(tag: string): string {
  return tag === 'GAVE_UP' ? 'b-red' : 'b-ink';
}
</script>

<template>
  <p v-if="notice" class="notice success" role="status">{{ notice }}</p>
  <div class="page-head">
    <h1>催办与删除</h1>
    <p class="lead">
      催办记录保留每条催办的触发条件、投递结果、用户原因与回应时延；删除台账保留被删 Todo 的进度快照与必填说明。两者都是永久留痕，用于复盘与督学。
    </p>
  </div>

  <p v-if="error" class="notice danger">{{ error }}</p>
  <p v-if="loading" class="loading">加载中…</p>

  <template v-if="!loading">
    <div class="wtabs">
      <span :class="{ on: tab === 'NAGS' }" @click="tab = 'NAGS'">催办记录 {{ nags.length }}</span>
      <span :class="{ on: tab === 'DELETIONS' }" @click="tab = 'DELETIONS'">
        删除台账 {{ deletions.length }}
      </span>
    </div>

    <template v-if="tab === 'NAGS'">
      <p class="lead" style="margin: 0 0 16px">
        共 {{ nags.length }} 条 · 未回应 {{ awaitingCount }} 条 · 已回应 {{ respondedNags.length }} 条 ·
        平均回应时延 {{ averageLatency }}。
        <template v-if="failedDeliveryCount > 0">
          其中
          <b style="color: var(--red)">{{ failedDeliveryCount }} 条所有渠道都投递失败</b>
          ，请检查学员在线状态与 Server 酱配置。
        </template>
      </p>

      <div class="wgrid c3">
        <div>
          <div class="wlabel">用户</div>
          <select v-model="nagUserFilter" class="winput">
            <option value="ALL">全部</option>
            <option v-for="option in nagUserOptions" :key="option.id" :value="option.id">
              {{ option.name }}
            </option>
          </select>
        </div>
        <div>
          <div class="wlabel">触发方式</div>
          <select v-model="nagTriggerFilter" class="winput">
            <option value="ALL">全部</option>
            <option value="AUTO">自动</option>
            <option value="MANUAL">手动</option>
            <option value="SUPERVISOR">督学</option>
          </select>
        </div>
        <div>
          <div class="wlabel">回应状态</div>
          <select v-model="nagAnswerFilter" class="winput">
            <option value="ALL">全部</option>
            <option value="ANSWERED">已回应</option>
            <option value="UNANSWERED">未回应</option>
          </select>
        </div>
      </div>

      <div class="wcard mt16">
        <b style="font-size: 14px">状态分布</b>
        <div class="mt12">
          <div v-for="item in statusDistribution" :key="item.status" class="mt10">
            <div class="between" style="font-size: 12.5px; font-weight: 700">
              <span>{{ nagStatusLabel(item.status) }}</span>
              <span class="mono">{{ item.count }} · {{ item.percent }}%</span>
            </div>
            <div class="bar thin mt6">
              <i :class="statusBarClass(item.status)" :style="{ width: `${item.percent}%` }"></i>
            </div>
            <div
              v-if="item.status === 'PENDING' && pendingAllFailedCount > 0"
              class="muted mt6"
              style="font-size: 11.5px"
            >
              其中 {{ pendingAllFailedCount }} 条已尝试投递但全部失败，流水表显示为「投递失败」；催办状态机没有失败终态，所以仍计入待投递；可在记录中取消或重新投递。
            </div>
          </div>
        </div>
        <div v-if="statusTotal === 0" class="empty mt10">还没有任何催办记录</div>
      </div>

      <div class="wcard flush mt16">
        <div class="table-wrap">
          <table class="wt">
            <thead>
              <tr>
                <th>触发时间</th>
                <th>用户</th>
                <th>触发</th>
                <th>触发条件</th>
                <th>投递</th>
                <th>回应</th>
                <th>原因</th>
                <th style="text-align: right">时延</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="nag in visibleNags" :key="nag.id">
                <td class="mono">
                  {{ formatInstant(nag.createdAt) }}
                  <div class="cell-sub mono">{{ nag.localDate }}</div>
                </td>
                <td>
                  <b>{{ username(nag.userId) }}</b>
                  <div v-if="nag.supervisorUserIdSnapshot" class="cell-sub">
                    督学 {{ username(nag.supervisorUserIdSnapshot) }}
                  </div>
                </td>
                <td>
                  <span class="badge" :class="nag.trigger === 'AUTO' ? 'b-ink' : 'b-focus'">
                    {{ triggerLabel(nag.trigger) }}
                  </span>
                  <div v-if="nag.triggeredByUserId" class="cell-sub">
                    by {{ username(nag.triggeredByUserId) }}
                  </div>
                </td>
                <td>
                  <template v-if="nag.trigger === 'AUTO'">
                    无操作 {{ nag.idleMinutes }} 分
                    <div class="cell-sub">未完成 {{ nag.pendingCount }} 项 · 第 {{ nag.thresholdLevel }} 档</div>
                  </template>
                  <template v-else>
                    {{ nag.title || (nag.trigger === 'SUPERVISOR' ? '督学发起' : '管理员发起') }}
                    <div class="cell-sub">{{ nag.message }}</div>
                    <div class="cell-sub">未完成 {{ nag.pendingCount }} 项</div>
                  </template>
                </td>
                <td>
                  <span class="dotstate" :class="deliveryDot(nag).cls">
                    <i></i>{{ deliveryDot(nag).text }}
                  </span>
                  <div v-if="nag.deliveredAt" class="cell-sub mono">{{ formatInstant(nag.deliveredAt) }}</div>
                  <!-- 逐次渠道尝试直接摊开在同一格里：新增独立列会把这张八列表挤到横向滚动 -->
                  <div
                    v-for="attempt in attemptsOf(nag)"
                    :key="attempt.createdAt + attempt.channel"
                    class="cell-sub"
                    :style="attempt.status === 'FAILED' ? { color: 'var(--red)' } : {}"
                    :title="`${formatInstant(attempt.createdAt)} ${attemptText(attempt)}`"
                  >
                    {{ attemptText(attempt) }}
                  </div>
                  <div v-if="attemptsOf(nag).length === 0" class="cell-sub muted">尚无投递尝试</div>
                  <div v-for="(entry, index) in adminActions.filter(row => row.nagId === nag.id)" :key="index" class="cell-sub">
                    {{ entry.action === 'CANCEL' ? '取消投递' : '重新投递' }} · {{ entry.actor }} · {{ formatInstant(entry.createdAt) }}
                  </div>
                  <div v-if="canManage(nag)" class="row mt8" style="gap: 8px; flex-wrap: wrap">
                    <button class="wbtn ghost sm" :disabled="!!operationId" @click="manage(nag, 'retry')">{{ operationId === nag.id ? '处理中…' : '重新投递' }}</button>
                    <button class="wbtn ghost sm" :disabled="!!operationId" @click="manage(nag, 'cancel')">取消投递</button>
                  </div>
                </td>
                <td>
                  <span v-if="awaiting(nag)" style="color: var(--red); font-weight: 700">未回应</span>
                  <span v-else-if="nag.status === 'RESPONDED'" style="color: var(--green); font-weight: 700">
                    已回应
                  </span>
                  <span v-else class="muted" style="font-weight: 700">{{ nagStatusLabel(nag.status) }}</span>
                </td>
                <td>
                  <span v-if="nag.reasonTag" class="badge" :class="reasonBadgeClass(nag.reasonTag)">
                    {{ reasonLabel(nag.reasonTag) }}
                  </span>
                  <div v-if="nag.reasonText" class="cell-sub">{{ nag.reasonText }}</div>
                  <span v-if="!nag.reasonTag && !nag.reasonText" class="muted">—</span>
                </td>
                <td class="mono" :class="{ muted: !nag.respondedAt }" style="text-align: right">
                  {{ latency(nag) }}
                </td>
              </tr>
              <tr v-if="visibleNags.length === 0">
                <td colspan="8" class="empty">没有符合条件的催办记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        自动催办幂等键 <code>(user_id, local_date, threshold_level)</code>，同一天同一档不会重复生成；手动与督学催办不参与幂等键，但同样计入每日上限。
      </div>
    </template>

    <template v-else>
      <p class="lead" style="margin: 0 0 16px">
        删除是硬操作，Todo 行会被移除，但删除记录与当时的进度快照永久保留，可用于复盘与督学。以下展示最近 100 条删除。
      </p>

      <div class="wgrid c4">
        <div class="wcard">
          <div class="wlabel">删除记录</div>
          <div class="mono" style="font-size: 22px; font-weight: 800">{{ deletionTotal }}</div>
          <div class="muted mt8" style="font-size: 11.5px">
            课程 {{ deletionTypeBreakdown.COURSE }} · 专注 {{ deletionTypeBreakdown.FOCUS }} · 待办
            {{ deletionTypeBreakdown.TASK }}
          </div>
        </div>
        <!-- 只有真的出现「不想学了」才上红：0 条却常驻红卡会让管理员对告警色失去敏感度 -->
        <div class="wcard" :class="{ danger: gaveUpCount > 0 }">
          <div class="wlabel">「不想学了」</div>
          <div
            class="mono"
            style="font-size: 22px; font-weight: 800"
            :style="gaveUpCount > 0 ? { color: 'var(--red)' } : {}"
          >
            {{ gaveUpCount }}
          </div>
          <div class="muted mt8" style="font-size: 11.5px">
            {{ gaveUpCount > 0 ? '按策略立即通知主督学人' : '暂无此类删除' }}
          </div>
        </div>
        <div class="wcard">
          <div class="wlabel">涉及学员</div>
          <div class="mono" style="font-size: 22px; font-weight: 800">{{ affectedLearners }}</div>
          <div class="muted mt8" style="font-size: 11.5px">按学员聚合见右侧</div>
        </div>
        <div class="wcard">
          <div class="wlabel">最近一次删除</div>
          <div class="mono" style="font-size: 22px; font-weight: 800">
            {{ deletions.length > 0 ? formatInstant(deletions[0].deletedAt) : '—' }}
          </div>
          <div class="muted mt8" style="font-size: 11.5px">删除必须填标签与说明</div>
        </div>
      </div>

      <div class="wgrid c2 mt16">
        <div class="wcard">
          <b style="font-size: 14px">原因分布</b>
          <div class="mt12">
            <div v-for="item in reasonDistribution" :key="item.tag" class="mt10">
              <div class="between" style="font-size: 12.5px; font-weight: 700">
                <span>{{ reasonLabel(item.tag) }}</span>
                <span class="mono">{{ item.count }} · {{ item.percent }}%</span>
              </div>
              <div class="bar thin mt6">
                <i :class="item.tag === 'GAVE_UP' ? 'red' : ''" :style="{ width: `${item.percent}%` }"></i>
              </div>
            </div>
          </div>
          <div v-if="reasonDistribution.length === 0" class="empty mt10">还没有删除记录</div>
          <div class="muted mt12" style="font-size: 11.5px">
            触发督学提醒的规则（单日删除 ≥ 3 条、原因含「不想学」、删除已完成 50% 以上的课程 Todo）在「催办策略」页维护。
          </div>
        </div>

        <div class="wcard">
          <b style="font-size: 14px">按学员</b>
          <div class="table-wrap mt10">
            <table class="wt">
              <thead>
                <tr>
                  <th>学员</th>
                  <th>删除数</th>
                  <th>最多原因</th>
                  <th style="text-align: right">「不想学了」</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in deletionByLearner" :key="row.userId">
                  <td><b>{{ username(row.userId) }}</b></td>
                  <td class="mono" :style="row.count >= 3 ? 'color:var(--red);font-weight:700' : ''">
                    {{ row.count }}
                  </td>
                  <td>{{ reasonLabel(row.topReason) }}</td>
                  <td style="text-align: right">
                    <span v-if="row.gaveUp > 0" class="badge b-red">{{ row.gaveUp }}</span>
                    <span v-else class="muted">0</span>
                  </td>
                </tr>
                <tr v-if="deletionByLearner.length === 0">
                  <td colspan="4" class="empty">还没有学员产生删除记录</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div class="wcard mt16">
        <div class="between">
          <b style="font-size: 14px">删除流水</b>
          <div class="row" style="gap: 8px">
            <select v-model="deletionUserFilter" class="winput" style="width: auto">
              <option value="ALL">全部学员</option>
              <option v-for="option in deletionUserOptions" :key="option.id" :value="option.id">
                {{ option.name }}
              </option>
            </select>
            <select v-model="deletionReasonFilter" class="winput" style="width: auto">
              <option value="ALL">全部原因</option>
              <option v-for="tag in deletionReasonOptions" :key="tag" :value="tag">
                {{ reasonLabel(tag) }}
              </option>
            </select>
          </div>
        </div>
        <div class="table-wrap mt10">
          <table class="wt">
            <thead>
              <tr>
                <th>删除时间</th>
                <th>学员</th>
                <th>类型</th>
                <th>Todo</th>
                <th>删除时进度</th>
                <th>原因标签</th>
                <th>说明</th>
                <th style="text-align: right">督学人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in visibleDeletions" :key="item.id">
                <td class="mono">
                  {{ formatInstant(item.deletedAt) }}
                  <div class="cell-sub mono">{{ item.localDate }}</div>
                </td>
                <td>{{ username(item.userId) }}</td>
                <td>
                  <span class="badge" :class="typeBadgeClass(item.todoType)">
                    {{ todoTypeLabel(item.todoType) }}
                  </span>
                </td>
                <td>{{ item.titleSnapshot }}</td>
                <td class="mono" :class="{ muted: snapshotText(item).dim }">
                  {{ snapshotText(item).text }}
                </td>
                <td>
                  <span class="badge" :class="reasonBadgeClass(item.reasonTag)">
                    {{ reasonLabel(item.reasonTag) }}
                  </span>
                </td>
                <td class="muted">{{ item.reasonText }}</td>
                <td style="text-align: right">
                  <span v-if="item.supervisorUserIdSnapshot">
                    {{ username(item.supervisorUserIdSnapshot) }}
                  </span>
                  <span v-else class="muted">未绑定</span>
                </td>
              </tr>
              <tr v-if="visibleDeletions.length === 0">
                <td colspan="8" class="empty">没有符合条件的删除记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        原因用于识别「排太多」还是「不想学」：删除记录与进度快照永久保留，Todo 行本身已被物理删除。
      </div>
    </template>
  </template>
</template>
