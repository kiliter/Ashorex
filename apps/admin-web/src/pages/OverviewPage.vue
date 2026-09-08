<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { api } from '@/api/client';
import { formatBytes, formatDuration, formatInstant, presenceLabel } from '@/api/format';

/** 结构严格对应原型 8-1：4 个指标卡 + 用户进度表 + 依赖健康表 + 近 14 天柱状图。 */
interface LearnerRow {
  userId: string;
  username: string;
  displayName: string;
  state: string;
  lastHeartbeatAt: string | null;
  idleMinutes: number;
  totalCount: number;
  doneCount: number;
  totalMs: number;
}

interface HealthSnapshot {
  databaseSizeBytes: number;
  walSizeBytes: number;
  embyStatus: string;
  embyOk: boolean;
  embyConfigured: boolean;
  embyLatencyMs: number;
}

interface Overview {
  learners: LearnerRow[];
  onlineCount: number;
  userCount: number;
  totalTodos: number;
  doneTodos: number;
  completionPercent: number;
  watchedMs: number;
  focusedMs: number;
  awaitingNags: number;
  respondedNags: number;
  lastScanAt: string | null;
  health: HealthSnapshot;
  orphans: Record<string, number>;
  expiredArchives: number;
}

const data = ref<Overview | null>(null);
const error = ref('');
let timer: number | undefined;

async function load(): Promise<void> {
  try {
    data.value = await api.get<Overview>('/overview');
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  }
}

// 原型标注「刷新间隔 30 秒」。
onMounted(async () => {
  await load();
  timer = window.setInterval(load, 30_000);
});
onUnmounted(() => {
  if (timer) window.clearInterval(timer);
});

/** 完成率升序，最需要关注的排最前（与原型「按完成率升序」一致）。 */
const learnersByCompletion = computed(() => {
  const rows = [...(data.value?.learners ?? [])];
  return rows.sort((left, right) => {
    const leftRate = left.totalCount === 0 ? 0 : left.doneCount / left.totalCount;
    const rightRate = right.totalCount === 0 ? 0 : right.doneCount / right.totalCount;
    return leftRate - rightRate;
  });
});

/**
 * 依赖健康按原型 8-1 的语义分行，而不是机械展开快照字段。
 *
 * <p>三态而非布尔：Emby 是可选依赖，「未配置」是中性状态（idle），不是故障（off）。 把未配置渲染成红色「异常」会让管理员以为线上坏了。
 * 注意：数据库路径不在快照里，页面也绝不渲染文件系统绝对路径。
 */
const healthRows = computed(() => {
  const health = data.value?.health;
  if (!health) return [];
  return [
    {
      key: 'Emby',
      // 三态：未配置=中性、已配置且可用=正常、已配置但探测失败=故障。
      tone: !health.embyConfigured ? 'idle' : health.embyOk ? 'on' : 'off',
      // 原型 8-1 的「可用 · 128ms」：耗时只在已配置时有意义，未配置时探测不发请求，
      // 此时 embyLatencyMs 恒为 0，直接渲染 0ms 会被误读成「极快」。
      label: !health.embyConfigured
        ? '未配置'
        : `${health.embyStatus} · ${health.embyLatencyMs}ms`,
      detail: !health.embyConfigured
        ? '可选依赖，未配置时课程库为空'
        : health.embyOk
          ? 'System/Info 可读'
          : '已配置但探测失败，请到运行配置重测连接',
    },
    {
      key: 'SQLite',
      tone: 'on',
      label: '正常',
      detail: `主库 ${formatBytes(health.databaseSizeBytes)} · WAL ${formatBytes(health.walSizeBytes)}`,
    },
    {
      key: '催办扫描',
      // 调度器随应用启动，未到第一个周期时不算故障，用中性态展示。
      tone: data.value?.lastScanAt != null ? 'on' : 'idle',
      label: data.value?.lastScanAt != null ? '正常' : '等待首扫',
      detail:
        data.value?.lastScanAt != null
          ? `上次 ${formatInstant(data.value.lastScanAt)}`
          : '已启动，等待首个扫描周期',
    },
  ];
});

/** 孤儿自检字段的中文名；未知字段回退原名，避免静默漏项。 */
const ORPHAN_LABELS: Record<string, string> = {
  orphanTodos: '待办',
  orphanProgressEvents: '进度事件',
  orphanAttachments: '附件',
  orphanWatchStates: '观看状态',
  orphanNags: '催办',
};

const orphanSummary = computed(() => {
  const entries = Object.entries(data.value?.orphans ?? {}).filter(([, value]) => value > 0);
  if (entries.length === 0) {
    return '各表均无孤儿数据';
  }
  return entries.map(([key, value]) => `${ORPHAN_LABELS[key] ?? key} ${value}`).join(' · ');
});

function dotClass(state: string): string {
  if (state === 'ONLINE') return 'on';
  if (state === 'IDLE') return 'idle';
  return 'off';
}
</script>

<template>
  <h1 style="font-size: 19px; font-weight: 800; margin: 0 0 4px">概览</h1>
  <p class="lead" style="font-size: 12.5px; color: var(--muted); margin: 0 0 18px; line-height: 1.7">
    数据口径为各用户本地时区的「今天」。刷新间隔 30 秒。
  </p>

  <p v-if="error" class="notice danger">{{ error }}</p>
  <p v-else-if="!data" class="loading">加载中…</p>

  <template v-else>
    <div class="wgrid c4">
      <div class="wcard">
        <div class="wlabel">在线用户</div>
        <div class="row" style="gap: 8px; align-items: baseline">
          <b class="metric">{{ data.onlineCount }}</b>
          <span class="muted" style="font-size: 12px">/ {{ data.userCount }} 位</span>
        </div>
        <div class="dotstate on mt8"><i></i>心跳正常</div>
      </div>

      <div class="wcard">
        <div class="wlabel">今日完成率</div>
        <div class="row" style="gap: 8px; align-items: baseline">
          <b class="metric">{{ data.completionPercent }}%</b>
          <span class="muted" style="font-size: 12px">
            {{ data.doneTodos }} / {{ data.totalTodos }}
          </span>
        </div>
        <div class="bar thin mt8"><i :style="{ width: `${data.completionPercent}%` }"></i></div>
      </div>

      <div class="wcard">
        <div class="wlabel">今日累计学习</div>
        <div class="row" style="gap: 8px; align-items: baseline">
          <b class="metric">{{ formatDuration(data.watchedMs + data.focusedMs) }}</b>
          <span class="muted" style="font-size: 12px">小时</span>
        </div>
        <div class="muted mt8" style="font-size: 11.5px">
          观看 {{ formatDuration(data.watchedMs) }} · 专注 {{ formatDuration(data.focusedMs) }}
        </div>
      </div>

      <div class="wcard" :class="{ danger: data.awaitingNags > 0 }">
        <div class="wlabel">待处理催办</div>
        <div class="row" style="gap: 8px; align-items: baseline">
          <b class="metric">{{ data.awaitingNags }}</b>
          <span class="muted" style="font-size: 12px">未回应</span>
        </div>
        <div class="mt8">
          <RouterLink
            :to="{ name: 'nags' }"
            class="wbtn sm"
            :class="data.awaitingNags > 0 ? 'red' : 'ghost'"
          >
            {{ data.awaitingNags > 0 ? '立即查看' : '查看记录' }}
          </RouterLink>
        </div>
      </div>
    </div>

    <div class="wgrid c2 mt16">
      <div class="wcard">
        <div class="between">
          <b style="font-size: 14px">今日各用户进度</b>
          <span class="muted" style="font-size: 11.5px">按完成率升序</span>
        </div>
        <table class="wt mt10">
          <thead>
            <tr><th>用户</th><th>状态</th><th>完成</th><th>时长</th><th>最近上报</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in learnersByCompletion" :key="row.userId">
              <td><b>{{ row.username }}</b></td>
              <td>
                <span class="dotstate" :class="dotClass(row.state)">
                  <i></i>{{ presenceLabel(row.state)
                  }}{{ row.state === 'IDLE' ? ` ${row.idleMinutes} 分` : '' }}
                </span>
              </td>
              <td>
                <span
                  :style="
                    row.doneCount === 0 && row.totalCount > 0
                      ? 'color:var(--red);font-weight:700'
                      : ''
                  "
                >
                  {{ row.doneCount }} / {{ row.totalCount }}
                </span>
              </td>
              <td class="mono">{{ formatDuration(row.totalMs) }}</td>
              <td class="muted mono">{{ formatInstant(row.lastHeartbeatAt) }}</td>
            </tr>
            <tr v-if="learnersByCompletion.length === 0">
              <td colspan="5" class="empty">还没有活跃用户</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="wcard">
        <div class="between">
          <b style="font-size: 14px">系统与依赖</b>
          <span class="muted" style="font-size: 11.5px">30 秒自动刷新</span>
        </div>
        <table class="wt mt10">
          <tbody>
            <tr v-for="row in healthRows" :key="row.key">
              <td>{{ row.key }}</td>
              <td>
                <span class="dotstate" :class="row.tone">
                  <i></i>{{ row.label }}
                </span>
              </td>
              <td class="muted">{{ row.detail }}</td>
            </tr>
            <tr>
              <td>孤儿数据</td>
              <td>
                <span
                  class="dotstate"
                  :class="Object.values(data.orphans).some((n) => n > 0) ? 'idle' : 'on'"
                >
                  <i></i>{{ Object.values(data.orphans).some((n) => n > 0) ? '需清理' : '干净' }}
                </span>
              </td>
              <td class="muted">{{ orphanSummary }}</td>
            </tr>
            <tr>
              <td>可彻底删除的归档</td>
              <td>
                <span class="dotstate" :class="data.expiredArchives > 0 ? 'idle' : 'on'">
                  <i></i>{{ data.expiredArchives }} 项
                </span>
              </td>
              <td class="muted">到期后可在归档区清理</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </template>
</template>
