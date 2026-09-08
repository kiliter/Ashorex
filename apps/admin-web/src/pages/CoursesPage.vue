<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import EmbyCourseImportDialog from '@/components/EmbyCourseImportDialog.vue';
import { api, ApiError } from '@/api/client';
import { formatDuration } from '@/api/format';

/**
 * 结构对应原型 8-6：
 * 顶部筛选行（搜索 + 流派 / 人物 / 标签下拉）与右侧「同步全部 / 新增课程」，
 * 中间贴边课程表，底部两列（课程详情只读元数据 + 课时明细）。
 *
 * 边界：流派、人物、标签、名称全部是 Emby 只读投影，本页只提供展示与筛选，不给编辑入口；
 * 本地可写的只有「列表排序」；课程只能归档，彻底删除在归档区完成。
 */
interface CourseRow {
  id: string;
  title: string;
  status: string;
  sourceMissing: boolean;
  sortOrder: number;
  genres: string[];
  tags: string[];
  people: string[];
  resourceCount: number;
  totalDurationMs: number;
  unavailableCount: number;
}

interface CoursePerson {
  name: string;
  role: string | null;
}

interface ResourceRow {
  id: string;
  title: string;
  resourceType: string;
  sortIndex: number;
  durationMs: number | null;
  pageCount: number | null;
  available: boolean;
}

interface CourseDetail {
  id: string;
  title: string;
  status: string;
  sourceMissing: boolean;
  sortOrder: number;
  genres: string[];
  tags: string[];
  people: CoursePerson[];
  resources: ResourceRow[];
}

interface SyncResultView {
  succeeded: boolean;
  inPlaceCount: number;
  createdCount: number;
  unavailableCount: number;
  ambiguousCount: number;
  error: string | null;
}



const rows = ref<CourseRow[]>([]);
const loading = ref(true);
const error = ref('');
const notice = ref('');

const keyword = ref('');
const genreFilter = ref('');
const peopleFilter = ref('');
const tagFilter = ref('');

const detail = ref<CourseDetail | null>(null);
const detailLoading = ref(false);
/** 详情卡里正在编辑的本地排序值，未保存前不影响列表。 */
const sortDraft = ref(0);

const createOpen = ref(false);


const archiveTarget = ref<CourseRow | null>(null);
const busy = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    rows.value = await api.get<CourseRow[]>('/courses');
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

/** 筛选维度全部由已加载课程的 Emby 快照聚合而来，后台不维护这些主数据。 */
function distinct(pick: (row: CourseRow) => string[]): string[] {
  const all = new Set<string>();
  for (const row of rows.value) {
    for (const value of pick(row)) {
      if (value) all.add(value);
    }
  }
  return [...all].sort((left, right) => left.localeCompare(right, 'zh-Hans-CN'));
}

const genreOptions = computed(() => distinct((row) => row.genres));
const peopleOptions = computed(() => distinct((row) => row.people));
const tagOptions = computed(() => distinct((row) => row.tags));

const visibleRows = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return rows.value.filter((row) => {
    if (text && !`${row.title} ${row.people.join(' ')}`.toLowerCase().includes(text)) return false;
    if (genreFilter.value && !row.genres.includes(genreFilter.value)) return false;
    if (peopleFilter.value && !row.people.includes(peopleFilter.value)) return false;
    if (tagFilter.value && !row.tags.includes(tagFilter.value)) return false;
    return true;
  });
});

const totalResourceCount = computed(() =>
  rows.value.reduce((sum, row) => sum + row.resourceCount, 0),
);

function message(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) return cause.message;
  return cause instanceof Error ? cause.message : fallback;
}

/** 状态列：归档与失联正交，失联优先提示，因为它需要去 Emby 同步页重绑。 */
function stateOf(row: CourseRow): { cls: string; text: string } {
  if (row.status === 'ARCHIVED') return { cls: 'off', text: '已归档' };
  if (row.sourceMissing) return { cls: 'off', text: '来源失联' };
  if (row.unavailableCount > 0) return { cls: 'idle', text: `${row.unavailableCount} 课时不可用` };
  return { cls: 'on', text: '已同步' };
}

async function openDetail(row: CourseRow): Promise<void> {
  detailLoading.value = true;
  detail.value = null;
  try {
    const data = await api.get<CourseDetail>(
      `/courses/detail?courseId=${encodeURIComponent(row.id)}`,
    );
    detail.value = data;
    sortDraft.value = data.sortOrder;
    error.value = '';
  } catch (cause) {
    error.value = message(cause, '加载课程详情失败');
  } finally {
    detailLoading.value = false;
  }
}

/** 本地唯一可写字段：列表排序。 */
async function saveSort(): Promise<void> {
  if (!detail.value) return;
  busy.value = true;
  try {
    await api.post('/courses/sort', {
      courseId: detail.value.id,
      sortOrder: Number(sortDraft.value),
    });
    notice.value = `已把「${detail.value.title}」的列表排序改为 ${sortDraft.value}`;
    error.value = '';
    await load();
    detail.value = { ...detail.value, sortOrder: Number(sortDraft.value) };
  } catch (cause) {
    error.value = message(cause, '保存排序失败');
  } finally {
    busy.value = false;
  }
}

function describeSync(title: string, result: SyncResultView): string {
  if (!result.succeeded) {
    return `「${title}」同步失败：${result.error ?? '未知原因，已保留上次可用快照'}`;
  }
  return `「${title}」同步完成：原位 ${result.inPlaceCount} · 新增 ${result.createdCount} · 下架 ${result.unavailableCount} · 待确认 ${result.ambiguousCount}`;
}

/** 单门手动同步；详情卡打开的是同一门课时顺带刷新明细。 */
async function syncOne(courseId: string, title: string): Promise<void> {
  busy.value = true;
  notice.value = '';
  try {
    const result = await api.post<SyncResultView>('/courses/sync', { courseId });
    if (result.succeeded) {
      notice.value = describeSync(title, result);
      error.value = '';
    } else {
      error.value = describeSync(title, result);
    }
    await load();
    if (detail.value?.id === courseId) {
      const refreshed = rows.value.find((row) => row.id === courseId);
      if (refreshed) await openDetail(refreshed);
    }
  } catch (cause) {
    error.value = message(cause, '同步失败');
  } finally {
    busy.value = false;
  }
}

/** 同步全部逐门串行执行，避免并发打满 Emby；单门失败不中断其余课程。 */
async function syncAll(): Promise<void> {
  const targets = rows.value.filter((row) => row.status === 'ACTIVE');
  if (targets.length === 0) return;
  if (!window.confirm(`将依次向 Emby 重新拉取 ${targets.length} 门课程的元数据与课时，继续？`)) {
    return;
  }
  busy.value = true;
  notice.value = '';
  const failed: string[] = [];
  try {
    for (const row of targets) {
      try {
        const result = await api.post<SyncResultView>('/courses/sync', { courseId: row.id });
        if (!result.succeeded) failed.push(row.title);
      } catch {
        failed.push(row.title);
      }
    }
    error.value = failed.length > 0 ? `以下课程同步失败，已保留上次可用快照：${failed.join('、')}` : '';
    notice.value = `同步全部完成：成功 ${targets.length - failed.length} 门 · 失败 ${failed.length} 门`;
    await load();
  } finally {
    busy.value = false;
  }
}

async function archiveCourse(): Promise<void> {
  const target = archiveTarget.value;
  if (!target) return;
  busy.value = true;
  try {
    await api.post('/archive/course', { entityId: target.id });
    notice.value = `已归档「${target.title}」，学习端不再可见，历史统计与记录保留`;
    error.value = '';
    archiveTarget.value = null;
    if (detail.value?.id === target.id) detail.value = null;
    await load();
  } catch (cause) {
    error.value = message(cause, '归档失败');
  } finally {
    busy.value = false;
  }
}

function resourceTypeLabel(type: string): string {
  return type === 'DOCUMENT' ? '材料' : '课时';
}

/** 材料按页数计量，课时按时长计量。 */
function resourceAmount(resource: ResourceRow): string {
  if (resource.resourceType === 'DOCUMENT') {
    return resource.pageCount ? `${resource.pageCount} 页` : '—';
  }
  return formatDuration(resource.durationMs);
}

function personLabel(person: CoursePerson): string {
  return person.role ? `${person.name}（${person.role}）` : person.name;
}
</script>

<template>
  <div class="page-head">
    <h1>课程库</h1>
    <p class="lead">
      共 {{ rows.length }} 门课程 · {{ totalResourceCount }} 课时。课程名称、流派、人物与标签全部来自
      Emby 只读投影，<b>本项目不提供编辑入口</b>，要改动请回 Emby 修改后重新同步。本地可写的只有列表排序；课程只能「归档」，彻底删除统一在归档区完成。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <div class="between">
    <div class="row" style="gap: 8px; flex-wrap: wrap">
      <input
        v-model="keyword"
        class="winput"
        style="width: 220px"
        type="search"
        placeholder="搜索课程 / 人物"
        aria-label="搜索课程或人物"
      />
      <select v-model="genreFilter" class="winput" style="width: auto" aria-label="按流派筛选">
        <option value="">全部流派</option>
        <option v-for="item in genreOptions" :key="item" :value="item">{{ item }}</option>
      </select>
      <select v-model="peopleFilter" class="winput" style="width: auto" aria-label="按人物筛选">
        <option value="">全部人物</option>
        <option v-for="item in peopleOptions" :key="item" :value="item">{{ item }}</option>
      </select>
      <select v-model="tagFilter" class="winput" style="width: auto" aria-label="按标签筛选">
        <option value="">全部标签</option>
        <option v-for="item in tagOptions" :key="item" :value="item">{{ item }}</option>
      </select>
    </div>
    <div class="row" style="gap: 8px">
      <button class="wbtn ghost" :disabled="busy || rows.length === 0" @click="syncAll">
        <svg class="icon s14"><use href="#i-pulse" /></svg>
        同步全部
      </button>
      <button class="wbtn" :disabled="busy" @click="createOpen = true">
        <svg class="icon s14"><use href="#i-plus" /></svg>
        从 Emby 导入
      </button>
    </div>
  </div>

  <div class="wcard flush mt16">
    <p v-if="loading" class="loading">加载中…</p>
    <div v-else class="table-wrap">
      <table class="wt">
        <thead>
          <tr>
            <th>课程</th>
            <th>流派</th>
            <th>人物</th>
            <th>标签</th>
            <th>课时</th>
            <th>时长</th>
            <th>不可用</th>
            <th>状态</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.id">
            <td>
              <b>{{ row.title }}</b>
              <div class="cell-sub">排序 {{ row.sortOrder }}</div>
            </td>
            <td>
              <span v-for="genre in row.genres" :key="genre" class="badge b-ink" style="margin-right: 4px">
                {{ genre }}
              </span>
              <span v-if="row.genres.length === 0" class="muted">—</span>
            </td>
            <td>{{ row.people.length > 0 ? row.people.join('、') : '—' }}</td>
            <td>{{ row.tags.length > 0 ? row.tags.join('、') : '—' }}</td>
            <td class="mono">{{ row.resourceCount }}</td>
            <td class="mono">{{ formatDuration(row.totalDurationMs) }}</td>
            <td>
              <span v-if="row.unavailableCount > 0" class="badge b-red">
                {{ row.unavailableCount }}
              </span>
              <span v-else class="muted mono">0</span>
            </td>
            <td>
              <span class="dotstate" :class="stateOf(row).cls"><i></i>{{ stateOf(row).text }}</span>
            </td>
            <td style="text-align: right">
              <div class="wbtn-row" style="justify-content: flex-end">
                <button class="wbtn ghost sm" @click="openDetail(row)">课时明细</button>
                <button class="wbtn ghost sm" :disabled="busy" @click="syncOne(row.id, row.title)">
                  同步
                </button>
                <button
                  v-if="row.status === 'ACTIVE'"
                  class="wbtn ghost sm"
                  :disabled="busy"
                  @click="archiveTarget = row"
                >
                  归档
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="visibleRows.length === 0">
            <td colspan="9" class="empty">
              {{
                rows.length === 0
                  ? '还没有课程。点右上角「新增课程」填写 Emby 父节点 Item ID 完成绑定。'
                  : '没有符合筛选条件的课程'
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="detailLoading" class="wcard mt16"><p class="loading">加载课程详情…</p></div>

  <div v-else-if="detail" class="wgrid c2 mt16">
    <div class="wcard">
      <div class="between">
        <b style="font-size: 14px">课程详情 · {{ detail.title }}</b>
        <button class="wbtn ghost sm" @click="detail = null">收起</button>
      </div>
      <div class="wgrid c2 mt12">
        <div>
          <div class="wlabel">课程名称 · 来自 Emby</div>
          <div class="winput" style="background: var(--ink-soft)">{{ detail.title }}</div>
        </div>
        <div>
          <div class="wlabel">流派 Genres · 来自 Emby</div>
          <div class="winput" style="background: var(--ink-soft)">
            {{ detail.genres.length > 0 ? detail.genres.join('，') : '—' }}
          </div>
        </div>
        <div>
          <div class="wlabel">人物 People · 来自 Emby</div>
          <div class="winput" style="background: var(--ink-soft)">
            {{ detail.people.length > 0 ? detail.people.map(personLabel).join('，') : '—' }}
          </div>
        </div>
        <div>
          <div class="wlabel">标签 Tags · 来自 Emby</div>
          <div class="winput" style="background: var(--ink-soft)">
            {{ detail.tags.length > 0 ? detail.tags.join('，') : '—' }}
          </div>
        </div>
        <div>
          <div class="wlabel">状态</div>
          <div class="winput" style="background: var(--ink-soft)">
            {{ detail.status === 'ARCHIVED' ? '已归档' : detail.sourceMissing ? '来源失联' : '启用中' }}
          </div>
        </div>
        <div>
          <div class="wlabel">列表排序（本地可写）</div>
          <input v-model.number="sortDraft" class="winput mono" type="number" min="0" />
        </div>
      </div>
      <div class="row mt12" style="gap: 9px">
        <button class="wbtn" :disabled="busy" @click="saveSort">保存本地排序</button>
        <button
          class="wbtn ghost"
          :disabled="busy"
          @click="syncOne(detail.id, detail.title)"
        >
          从 Emby 重新拉取
        </button>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        本地只可改「排序」；名称、流派、人物、标签由 Emby 决定。元数据改名或重打标签
        <b>不触发任何数据迁移</b>，统计按课程与课时 ID 聚合，历史结果不变。
      </div>
    </div>

    <div class="wcard">
      <b style="font-size: 14px">课时明细 · {{ detail.resources.length }} 项</b>
      <p class="lead" style="margin: 6px 0 12px">
        课时本地 ID 永不重建，因此进度、Todo、附件与统计始终跟随原课时。远端消失只标记不可用，不删本地行。
      </p>
      <div class="table-wrap">
        <table class="wt">
          <thead>
            <tr>
              <th>序号</th>
              <th>标题</th>
              <th>类型</th>
              <th>时长 / 页数</th>
              <th>可用性</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="resource in detail.resources" :key="resource.id">
              <td class="mono muted">{{ resource.sortIndex }}</td>
              <td>{{ resource.title }}</td>
              <td>
                <span class="badge" :class="resource.resourceType === 'DOCUMENT' ? 'b-ink' : 'b-course'">
                  {{ resourceTypeLabel(resource.resourceType) }}
                </span>
              </td>
              <td class="mono">{{ resourceAmount(resource) }}</td>
              <td>
                <span class="dotstate" :class="resource.available ? 'on' : 'off'">
                  <i></i>{{ resource.available ? '可用' : '已下架' }}
                </span>
              </td>
            </tr>
            <tr v-if="detail.resources.length === 0">
              <td colspan="5" class="empty">这门课程还没有同步到课时</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        课时下架后，引用它的 Todo 在 App 内显示「课时已下架」，学员只能补记完成或删除（删除仍需填原因）。
      </div>
    </div>
  </div>

  <EmbyCourseImportDialog v-if="createOpen" @close="createOpen = false" @busy="busy = $event" @imported="load" />

  <div v-if="archiveTarget" class="modal-backdrop">
    <div class="modal danger">
      <h3>归档课程</h3>
      <p class="sub">
        将归档「{{ archiveTarget.title }}」：学习端不再可见，已有 Todo、进度与统计全部保留。归档可在归档区查看；彻底删除必须在归档区走级联清单并输入名称确认。
      </p>
      <div class="modal-foot">
        <button class="wbtn ghost" :disabled="busy" @click="archiveTarget = null">取消</button>
        <button class="wbtn red" :disabled="busy" @click="archiveCourse">确认归档</button>
      </div>
    </div>
  </div>
</template>
