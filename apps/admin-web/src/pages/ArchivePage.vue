<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, ApiError } from '@/api/client';
import { formatBytes, formatDuration, formatInstant } from '@/api/format';

/**
 * 归档区没有独立原型屏，结构复用 8-9 / 8-10 的 wcard + wt 表格：
 * 状态 Tab → 归档对象表 → 彻底删除预检（级联清单 + 名称确认）→ 孤儿自检 → 最近审计。
 *
 * 关键边界：归档可逆；彻底删除是唯一真正 DELETE 数据的入口，必须先看预检清单，
 * 再输入与实体名称完全一致的确认串，否则后端返回 ARCHIVE_LABEL_MISMATCH。
 */
interface ArchivedCourse {
  id: string;
  title: string;
  status: string;
  sourceMissing: boolean;
}

interface ArchivedUser {
  id: string;
  username: string;
  displayName: string;
  timezone: string;
  status: string;
  roles: string[];
  supervisor: boolean;
}

interface OrphanReport {
  orphanTodos: number;
  orphanProgressEvents: number;
  orphanAttachments: number;
  orphanWatchStates: number;
  orphanNags: number;
}

interface DeletionAudit {
  id: string;
  entityType: string;
  entityId: string;
  entityLabel: string;
  actor: string;
  rowCountsJson: string;
  createdAt: string;
}

interface ArchiveResponse {
  archivedCourses: ArchivedCourse[];
  archivedUsers: ArchivedUser[];
  orphans: OrphanReport;
  audits: DeletionAudit[];
}

/** `POST /archive/scan-orphans` 的结果：即时重扫，不做任何修复。 */
interface OrphanScanResult {
  orphans: OrphanReport;
  clean: boolean;
  total: number;
  scannedAt: string;
}

interface CascadeStep {
  order: number;
  table: string;
  rowCount: number;
  description: string;
}

interface CascadePlan {
  entityType: string;
  entityId: string;
  entityLabel: string;
  steps: CascadeStep[];
  attachmentBytes: number;
  affectedUserCount: number;
  affectedWatchedMs: number;
}

type Tab = 'COURSE' | 'USER';

const data = ref<ArchiveResponse | null>(null);
const tab = ref<Tab>('COURSE');
const error = ref('');
const notice = ref('');
const busy = ref(false);
const scanning = ref(false);
/** 手动自检结果；非空时表格优先展示它，因为它比页面加载时的快照更新。 */
const lastScan = ref<OrphanScanResult | null>(null);

/** 预检结果与确认串；`plan` 非空即代表当前处于「彻底删除待确认」状态。 */
const plan = ref<CascadePlan | null>(null);
const planType = ref<Tab>('COURSE');
const confirmLabel = ref('');
const confirmError = ref('');

async function load(): Promise<void> {
  try {
    data.value = await api.get<ArchiveResponse>('/archive');
    // 整页快照已刷新，手动自检结果失去意义。
    lastScan.value = null;
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载归档区失败';
  }
}

onMounted(load);

/**
 * 立即重跑孤儿自检。只读扫描，不删除也不修复；发现残留仍需走归档区的彻底删除流程处理。
 */
async function scanOrphans(): Promise<void> {
  scanning.value = true;
  error.value = '';
  notice.value = '';
  try {
    const result = await api.post<OrphanScanResult>('/archive/scan-orphans', {});
    lastScan.value = result;
    notice.value = result.clean
      ? '孤儿自检完成：库内没有残留行'
      : `孤儿自检完成：发现 ${result.total} 条孤儿行，请按下表逐项核对`;
  } catch (cause) {
    reportFailure(cause, '孤儿自检失败');
  } finally {
    scanning.value = false;
  }
}

const courses = computed(() => data.value?.archivedCourses ?? []);
const users = computed(() => data.value?.archivedUsers ?? []);

const orphanRows = computed(() => {
  const report = lastScan.value?.orphans ?? data.value?.orphans;
  return [
    { label: '无主 todos', value: report?.orphanTodos ?? 0, hint: '所属用户已不存在的待办' },
    {
      label: '无主进度流水',
      value: report?.orphanProgressEvents ?? 0,
      hint: '指向已删 Todo 的上报',
    },
    { label: '无主附件行', value: report?.orphanAttachments ?? 0, hint: '数据库行与磁盘文件不一致' },
    {
      label: '无主课时状态',
      value: report?.orphanWatchStates ?? 0,
      hint: '指向已删学习资源的累计状态',
    },
    { label: '无主催办', value: report?.orphanNags ?? 0, hint: '指向已删用户的催办记录' },
  ];
});

const orphanTotal = computed(() => orphanRows.value.reduce((sum, row) => sum + row.value, 0));

const planTotalRows = computed(() =>
  (plan.value?.steps ?? []).reduce((sum, step) => sum + step.rowCount, 0),
);

const confirmMatched = computed(
  () => plan.value !== null && confirmLabel.value.trim() === plan.value.entityLabel,
);

/** 审计只保存表名→行数的 JSON，页面聚合为总行数，避免展示内部明细。 */
function auditRowTotal(rowCountsJson: string): number {
  try {
    const parsed: unknown = JSON.parse(rowCountsJson);
    if (parsed && typeof parsed === 'object') {
      return Object.values(parsed as Record<string, number>).reduce(
        (sum, value) => sum + (typeof value === 'number' ? value : 0),
        0,
      );
    }
  } catch {
    // 审计 JSON 异常不影响页面渲染。
  }
  return 0;
}

function entityTypeLabel(type: string): string {
  if (type === 'COURSE') return '课程';
  if (type === 'USER') return '用户';
  if (type === 'LEARNING_RESOURCE') return '课时';
  if (type === 'SUPERVISION') return '督学关系';
  return type;
}

function closePlan(): void {
  plan.value = null;
  confirmLabel.value = '';
  confirmError.value = '';
}

function reportFailure(cause: unknown, fallback: string): void {
  error.value = cause instanceof Error ? cause.message : fallback;
}

async function restore(type: Tab, entityId: string, label: string): Promise<void> {
  busy.value = true;
  notice.value = '';
  error.value = '';
  try {
    await api.post('/archive/restore', { type, entityId });
    closePlan();
    await load();
    notice.value = `已恢复${entityTypeLabel(type)}「${label}」，学习端重新可见`;
  } catch (cause) {
    reportFailure(cause, '恢复失败');
  } finally {
    busy.value = false;
  }
}

/** 远端失联但仍处于 ACTIVE 的课程需要先归档，才能进入彻底删除流程。 */
async function archiveCourse(course: ArchivedCourse): Promise<void> {
  busy.value = true;
  notice.value = '';
  error.value = '';
  try {
    await api.post('/archive/course', { entityId: course.id });
    await load();
    notice.value = `已归档课程「${course.title}」`;
  } catch (cause) {
    reportFailure(cause, '归档失败');
  } finally {
    busy.value = false;
  }
}

async function openPreflight(type: Tab, entityId: string): Promise<void> {
  busy.value = true;
  notice.value = '';
  error.value = '';
  confirmLabel.value = '';
  confirmError.value = '';
  try {
    planType.value = type;
    plan.value = await api.get<CascadePlan>(
      `/archive/preflight?type=${type}&entityId=${encodeURIComponent(entityId)}`,
    );
  } catch (cause) {
    plan.value = null;
    reportFailure(cause, '预检失败');
  } finally {
    busy.value = false;
  }
}

async function purge(): Promise<void> {
  if (!plan.value) return;
  busy.value = true;
  confirmError.value = '';
  error.value = '';
  const label = plan.value.entityLabel;
  try {
    await api.post('/archive/purge', {
      type: planType.value,
      entityId: plan.value.entityId,
      confirmLabel: confirmLabel.value.trim(),
    });
    closePlan();
    await load();
    notice.value = `已彻底删除「${label}」，级联数据与附件文件同时清理完成`;
  } catch (cause) {
    // 确认串不一致是最常见的失败，单独在输入框下方给出可操作文案。
    if (cause instanceof ApiError && cause.problem.errorCode === 'ARCHIVE_LABEL_MISMATCH') {
      confirmError.value = `确认串与实体名称不一致，请逐字输入「${label}」`;
    } else {
      reportFailure(cause, '彻底删除失败');
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="page-head">
    <h1>归档区</h1>
    <p class="lead">
      状态机：<code>ACTIVE → ARCHIVED → 彻底删除</code>。归档对象对学习端不可见、不可新建引用，历史统计仍可查；彻底删除是唯一会真正 <code>DELETE</code> 数据的入口，必须先看级联清单再逐项输入名称确认，删除后<b>不可恢复</b>。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <div class="wtabs">
    <span :class="{ on: tab === 'COURSE' }" @click="tab = 'COURSE'">课程 {{ courses.length }}</span>
    <span :class="{ on: tab === 'USER' }" @click="tab = 'USER'">用户 {{ users.length }}</span>
  </div>

  <div class="wcard flush">
    <div class="table-wrap">
      <table v-if="tab === 'COURSE'" class="wt">
        <thead>
          <tr>
            <th>对象</th>
            <th>类型</th>
            <th>状态</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="course in courses" :key="course.id">
            <td>
              <b>{{ course.title }}</b>
              <div v-if="course.sourceMissing" class="cell-sub">Emby 失联，保留上次可用快照</div>
            </td>
            <td><span class="badge b-ink">课程</span></td>
            <td>
              <span class="dotstate" :class="course.status === 'ARCHIVED' ? 'idle' : 'on'">
                <i></i>{{ course.status === 'ARCHIVED' ? '已归档' : '在用' }}
              </span>
              <div v-if="course.sourceMissing" class="cell-sub" style="color: var(--red)">
                来源失联
              </div>
            </td>
            <td style="text-align: right">
              <template v-if="course.status === 'ARCHIVED'">
                <button
                  class="wbtn ghost sm"
                  :disabled="busy"
                  @click="restore('COURSE', course.id, course.title)"
                >
                  恢复
                </button>
                <button
                  class="wbtn red sm"
                  :disabled="busy"
                  @click="openPreflight('COURSE', course.id)"
                >
                  彻底删除
                </button>
              </template>
              <button v-else class="wbtn ghost sm" :disabled="busy" @click="archiveCourse(course)">
                归档
              </button>
            </td>
          </tr>
          <tr v-if="courses.length === 0">
            <td colspan="4" class="empty">
              没有已归档或失联的课程。课程库里执行「归档」后会出现在这里。
            </td>
          </tr>
        </tbody>
      </table>

      <table v-else class="wt">
        <thead>
          <tr>
            <th>对象</th>
            <th>类型</th>
            <th>时区</th>
            <th>状态</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>
              <b>{{ user.username }}</b>
              <div class="cell-sub">{{ user.displayName }}</div>
            </td>
            <td><span class="badge b-ink">用户</span></td>
            <td class="muted">{{ user.timezone }}</td>
            <td>
              <span class="dotstate idle"><i></i>已归档</span>
              <div class="cell-sub">不能登录、不参与催办扫描</div>
            </td>
            <td style="text-align: right">
              <button
                class="wbtn ghost sm"
                :disabled="busy"
                @click="restore('USER', user.id, user.username)"
              >
                恢复
              </button>
              <button class="wbtn red sm" :disabled="busy" @click="openPreflight('USER', user.id)">
                彻底删除
              </button>
            </td>
          </tr>
          <tr v-if="users.length === 0">
            <td colspan="5" class="empty">没有已归档用户。用户页执行「归档」后会出现在这里。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="plan" class="wcard danger mt16">
    <b style="font-size: 14px">
      彻底删除预检 · {{ entityTypeLabel(plan.entityType) }}「{{ plan.entityLabel }}」
    </b>
    <p class="lead" style="margin: 6px 0 12px">
      下列数据会在<b>一个事务</b>内按顺序删除，不留孤儿行；附件磁盘文件与数据库行一起清理，任一步失败整体回滚。删除后<b>不可恢复</b>。
    </p>

    <div class="table-wrap">
      <table class="wt">
        <thead>
          <tr>
            <th style="width: 56px">顺序</th>
            <th>表 / 资源</th>
            <th style="width: 88px">行数</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="step in plan.steps" :key="step.order">
            <td class="mono muted">{{ step.order }}</td>
            <td><code>{{ step.table }}</code></td>
            <td class="mono" :style="step.rowCount > 0 ? 'font-weight:700' : 'color:var(--muted)'">
              {{ step.rowCount }}
            </td>
            <td class="muted">{{ step.description }}</td>
          </tr>
          <tr>
            <td class="mono muted">合计</td>
            <td class="muted">共 {{ plan.steps.length }} 张表</td>
            <td class="mono" style="font-weight: 800">{{ planTotalRows }}</td>
            <td class="muted">另写入 1 条删除审计（保留，不含明细内容）</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="notice danger mt12" style="margin-bottom: 0">
      <template v-if="planType === 'USER'">
        影响面：该账号的全部学习数据、附件目录与登录会话一并清除；作为督学人的绑定同时解除，被督学学员的历史事件仍保留督学人姓名快照。若只想让账号停用，保持「已归档」即可。
      </template>
      <template v-else>
        影响面：{{ plan.affectedUserCount }} 名用户的历史统计将减少
        {{ formatDuration(plan.affectedWatchedMs) }} 观看时长，同时清理
        {{ formatBytes(plan.attachmentBytes) }} 附件文件。若只想让课程对学习端消失，保持「已归档」即可。
      </template>
    </p>

    <div class="wlabel" style="margin-top: 14px">
      输入{{ planType === 'USER' ? '用户名' : '课程名' }}以确认：{{ plan.entityLabel }}
    </div>
    <input
      v-model="confirmLabel"
      class="winput mono"
      style="border-color: var(--red)"
      :placeholder="plan.entityLabel"
    />
    <div v-if="confirmError" class="field-error">{{ confirmError }}</div>

    <div class="wbtn-row mt12">
      <button class="wbtn red" :disabled="busy || !confirmMatched" @click="purge">
        {{ busy ? '删除中…' : '确认彻底删除' }}
      </button>
      <button class="wbtn ghost" :disabled="busy" @click="closePlan">取消</button>
      <span v-if="!confirmMatched" class="muted" style="font-size: 11.5px">
        名称一致后才会开放删除按钮
      </span>
    </div>
  </div>

  <div class="wgrid c2 mt16">
    <div class="wcard">
      <div class="wcard-head">
        <h2>孤儿数据自检</h2>
        <span class="dotstate" :class="orphanTotal === 0 ? 'on' : 'off'">
          <i></i>{{ orphanTotal === 0 ? '0 条孤儿行' : `${orphanTotal} 条孤儿行` }}
        </span>
      </div>
      <table class="wt">
        <thead>
          <tr>
            <th>自检项</th>
            <th style="width: 76px">残留</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in orphanRows" :key="row.label">
            <td><b>{{ row.label }}</b></td>
            <td
              class="mono"
              :style="row.value > 0 ? 'color:var(--red);font-weight:700' : 'color:var(--muted)'"
            >
              {{ row.value }}
            </td>
            <td class="muted">{{ row.hint }}</td>
          </tr>
        </tbody>
      </table>
      <div class="wbtn-row mt12">
        <button class="wbtn ghost sm" :disabled="busy || scanning" @click="scanOrphans">
          {{ scanning ? '扫描中…' : '立即扫描' }}
        </button>
        <span v-if="lastScan" class="muted" style="font-size: 11.5px">
          扫描于 {{ formatInstant(lastScan.scannedAt) }}
        </span>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        每日备份后自动跑一次，结果写入运行日志。归档保留期到期只提醒，<b>不会自动删除</b>。
      </div>
    </div>

    <div class="wcard">
      <div class="wcard-head">
        <h2>最近删除审计</h2>
        <span class="muted" style="font-size: 11.5px">最多 20 条</span>
      </div>
      <div class="table-wrap">
        <table class="wt">
          <thead>
            <tr>
              <th>时间</th>
              <th>对象</th>
              <th>类型</th>
              <th>操作人</th>
              <th style="width: 84px">删除行数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="audit in data?.audits ?? []" :key="audit.id">
              <td class="mono muted">{{ formatInstant(audit.createdAt) }}</td>
              <td><b>{{ audit.entityLabel }}</b></td>
              <td><span class="badge b-ink">{{ entityTypeLabel(audit.entityType) }}</span></td>
              <td class="muted">{{ audit.actor }}</td>
              <td class="mono">{{ auditRowTotal(audit.rowCountsJson) }}</td>
            </tr>
            <tr v-if="(data?.audits ?? []).length === 0">
              <td colspan="5" class="empty">还没有执行过彻底删除，审计为空。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        审计只记录对象名称、操作人与各表删除行数，删除后即便实体不存在也可追溯，不保留业务明细内容。
      </div>
    </div>
  </div>
</template>
