<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { api, ApiError } from '@/api/client';
import { formatDuration } from '@/api/format';
import EmbyCourseImportDialog from '@/components/EmbyCourseImportDialog.vue';

/**
 * 结构对应原型 8-5：
 * 顶部四格状态摘要 → 媒体库绑定 → 两列（课时映射待确认 / 失联处理）→ 重新绑定向导。
 *
 * 边界：本页只处理元数据快照、课时映射与失联迁移；Emby 原始文件路径只在服务端内存中用于生成
 * 来源指纹，既不入库也不在此展示。Emby 未配置时给出去「运行配置」的引导，而不是报错。
 */
interface CourseOption {
  id: string;
  title: string;
  status: string;
  sourceMissing: boolean;
}

interface EmbyLibrary {
  id: string;
  name: string;
  contentType: string;
}

interface EmbySyncResponse {
  courses: CourseOption[];
  libraries: EmbyLibrary[];
  embyConfigured: boolean;
}

interface MediaLibrary {
  id: string;
  name: string;
  collectionType: string | null;
}

interface MediaSource {
  id: string;
  name: string;
  itemType: string;
  collectionType: string | null;
  parentId: string | null;
}

interface MappingPlanView {
  inPlace: number;
  created: number;
  markedUnavailable: number;
  ambiguous: number;
}

interface SyncResultView {
  succeeded: boolean;
  inPlaceCount: number;
  createdCount: number;
  unavailableCount: number;
  ambiguousCount: number;
  error: string | null;
}

/** 课程列表行，用来统计失联课程与不可用课时；与课程库页共用 /courses。 */
interface CourseRow {
  id: string;
  title: string;
  status: string;
  sourceMissing: boolean;
  resourceCount: number;
  unavailableCount: number;
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
  resources: ResourceRow[];
}

const LIBRARY_TYPES = ['SERIES', 'MOVIE', 'MIXED', 'BOOK'] as const;
type LibraryType = (typeof LIBRARY_TYPES)[number];

const loading = ref(true);
const error = ref('');
const notice = ref('');
const busy = ref(false);
/** 仅在点击导入时打开选课弹窗，避免课程列表撑长同步页。 */
const importOpen = ref(false);

const embyConfigured = ref(false);
const courses = ref<CourseOption[]>([]);
const courseRows = ref<CourseRow[]>([]);
/** 媒体库绑定草稿：保存前只改本地数组，保存时整表提交。 */
const libraries = ref<Array<{ id: string; name: string; type: LibraryType }>>([]);
const remoteLibraries = ref<MediaLibrary[]>([]);
const remoteLoaded = ref(false);

const rebindCourseId = ref('');
const newParentRef = ref('');
const searchQuery = ref('');
const searchResults = ref<MediaSource[]>([]);
const plan = ref<MappingPlanView | null>(null);
const rebindConfirmOpen = ref(false);
// 来源或课程变化后必须重新预览，避免旧确认内容授权迁移另一门课程。
watch([rebindCourseId, newParentRef], () => {
  plan.value = null;
  rebindConfirmOpen.value = false;
}, { flush: 'sync' });

const mappingCourseId = ref('');
const mappingDetail = ref<CourseDetail | null>(null);
/** 逐项确认映射时填写的新远端 Item ID，按本地课时 ID 归档。 */
const mappingDrafts = ref<Record<string, string>>({});

function message(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) return cause.message;
  return cause instanceof Error ? cause.message : fallback;
}

/** 导入完成后静默刷新摘要，保留选择器的逐项结果与重试入口。 */
async function load(silent = false): Promise<void> {
  if (!silent) loading.value = true;
  try {
    const [sync, rows] = await Promise.all([
      api.get<EmbySyncResponse>('/emby-sync'),
      api.get<CourseRow[]>('/courses'),
    ]);
    embyConfigured.value = sync.embyConfigured;
    courses.value = sync.courses;
    courseRows.value = rows;
    libraries.value = sync.libraries.map((library) => ({
      id: library.id,
      name: library.name,
      type: (LIBRARY_TYPES as readonly string[]).includes(library.contentType)
        ? (library.contentType as LibraryType)
        : 'MIXED',
    }));
    error.value = '';
  } catch (cause) {
    error.value = message(cause, '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const missingCourses = computed(() => courseRows.value.filter((row) => row.sourceMissing));
const unavailableTotal = computed(() =>
  courseRows.value.reduce((sum, row) => sum + row.unavailableCount, 0),
);
/** 失联课程牵连的课时总数，用于估算迁移工作量。 */
const missingResourceTotal = computed(() =>
  missingCourses.value.reduce((sum, row) => sum + row.resourceCount, 0),
);
/** 有不可用课时或已失联的课程才需要在本页处理映射。 */
const attentionCourses = computed(() =>
  courseRows.value.filter((row) => row.sourceMissing || row.unavailableCount > 0),
);
// 重绑歧义会在写入前拒绝，因此待确认资源可能仍可用，不能仅展示下架项。
const mappingResources = computed(() => mappingDetail.value?.resources ?? []);

function libraryTypeLabel(type: string): string {
  if (type === 'SERIES') return '剧集库';
  if (type === 'MOVIE') return '电影库';
  if (type === 'BOOK') return '书籍库';
  return '混合库';
}

async function loadRemoteLibraries(): Promise<void> {
  busy.value = true;
  try {
    remoteLibraries.value = await api.get<MediaLibrary[]>('/emby-sync/remote-libraries');
    remoteLoaded.value = true;
    error.value = '';
    notice.value = `已从 Emby 读取到 ${remoteLibraries.value.length} 个可用媒体库`;
  } catch (cause) {
    error.value = message(cause, '读取 Emby 媒体库失败');
  } finally {
    busy.value = false;
  }
}

/** 把远端媒体库加入绑定草稿；已在草稿中的忽略。 */
function addLibrary(library: MediaLibrary): void {
  if (libraries.value.some((item) => item.id === library.id)) return;
  const type: LibraryType =
    library.collectionType === 'tvshows'
      ? 'SERIES'
      : library.collectionType === 'movies'
        ? 'MOVIE'
        : library.collectionType === 'books'
          ? 'BOOK'
          : 'MIXED';
  libraries.value = [...libraries.value, { id: library.id, name: library.name, type }];
}

function removeLibrary(id: string): void {
  libraries.value = libraries.value.filter((item) => item.id !== id);
}

async function saveLibraries(): Promise<void> {
  busy.value = true;
  try {
    await api.post('/emby-sync/libraries', {
      libraries: libraries.value.map((item) => ({
        id: item.id,
        name: item.name,
        type: item.type,
      })),
    });
    notice.value = `已保存 ${libraries.value.length} 个媒体库绑定，点击「导入课程」选择课程`;
    error.value = '';
    await load();
  } catch (cause) {
    error.value = message(cause, '保存媒体库绑定失败');
  } finally {
    busy.value = false;
  }
}

async function searchSources(): Promise<void> {
  const query = searchQuery.value.trim();
  if (!query) return;
  busy.value = true;
  try {
    searchResults.value = await api.get<MediaSource[]>(
      `/emby-sync/search-sources?query=${encodeURIComponent(query)}`,
    );
    error.value = '';
  } catch (cause) {
    error.value = message(cause, '联想 Emby 来源失败');
  } finally {
    busy.value = false;
  }
}

/** 先预览再提交：预览只算映射方案，不写库。 */
async function preview(): Promise<void> {
  if (!rebindCourseId.value || !newParentRef.value.trim()) return;
  busy.value = true;
  plan.value = null;
  try {
    const courseId = rebindCourseId.value;
    const parentRef = newParentRef.value.trim();
    const result = await api.post<MappingPlanView>('/emby-sync/preview', {
      courseId,
      newParentRef: parentRef,
    });
    // 请求返回前输入可能已变化，过期结果不能恢复提交按钮。
    if (courseId !== rebindCourseId.value || parentRef !== newParentRef.value.trim()) return;
    plan.value = result;
    error.value = '';
    notice.value = '';
  } catch (cause) {
    error.value = message(cause, '预览失败，已保留上次可用快照');
  } finally {
    busy.value = false;
  }
}

async function rebind(): Promise<void> {
  if (!plan.value || busy.value) return;
  busy.value = true;
  rebindConfirmOpen.value = false;
  try {
    const result = await api.post<SyncResultView>('/emby-sync/rebind', {
      courseId: rebindCourseId.value,
      newParentRef: newParentRef.value.trim(),
    });
    if (result.succeeded) {
      notice.value = `迁移已提交：原位 ${result.inPlaceCount} · 新增 ${result.createdCount} · 下架 ${result.unavailableCount} · 待确认 ${result.ambiguousCount}`;
      error.value = '';
      plan.value = null;
      newParentRef.value = '';
      await load();
    } else {
      error.value = `迁移失败，已整体回滚并保留上次可用快照：${result.error ?? '未知原因'}`;
    }
  } catch (cause) {
    error.value = message(cause, '迁移失败，已整体回滚');
  } finally {
    busy.value = false;
  }
}

async function loadMappingDetail(): Promise<void> {
  if (!mappingCourseId.value) {
    mappingDetail.value = null;
    return;
  }
  busy.value = true;
  try {
    mappingDetail.value = await api.get<CourseDetail>(
      `/courses/detail?courseId=${encodeURIComponent(mappingCourseId.value)}`,
    );
    mappingDrafts.value = {};
    error.value = '';
  } catch (cause) {
    error.value = message(cause, '加载课时失败');
  } finally {
    busy.value = false;
  }
}

/** 歧义或失联课时只能逐项确认，绝不自动合并；确认后本地 ID 不变，只换来源标识。 */
async function confirmMapping(resource: ResourceRow): Promise<void> {
  const newExternalRef = (mappingDrafts.value[resource.id] ?? '').trim();
  if (!newExternalRef) {
    error.value = `请先填写「${resource.title}」对应的新远端 Item ID`;
    return;
  }
  busy.value = true;
  try {
    await api.post('/emby-sync/confirm-mapping', {
      resourceId: resource.id,
      newExternalRef,
      courseId: mappingCourseId.value,
    });
    notice.value = `已确认「${resource.title}」为同一课时，本地 ID 不变，观看进度与 Todo 全部保留`;
    error.value = '';
    delete mappingDrafts.value[resource.id];
    await loadMappingDetail();
    await load();
  } catch (cause) {
    error.value = message(cause, '确认映射失败');
  } finally {
    busy.value = false;
  }
}

const selectedRebindCourse = computed(() =>
  courseRows.value.find((row) => row.id === rebindCourseId.value) ?? null,
);
</script>

<template>
  <div class="page-head">
    <h1>Emby 同步</h1>
    <p class="lead">
      同步只写三类数据：课程基本信息、元数据只读快照（Genres / Tags / People / Year）、课时映射。<b>本地课时
      ID 永不重建</b>，因此观看进度、Todo、附件、备注与统计始终跟随原课时。Emby
      原始路径只在服务端内存中用于生成来源指纹，不入库、不展示、不写日志。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <p v-if="loading" class="loading">加载中…</p>

  <template v-else>
    <p v-if="!embyConfigured" class="notice warn">
      Emby 尚未配置，本页的远端读取、预览与迁移都不可用。请先到「运行配置」填写 Emby 地址、API Key
      与用户 ID，保存后回到本页继续绑定媒体库。
      <RouterLink :to="{ name: 'settings' }">前往运行配置 →</RouterLink>
    </p>

    <div class="wgrid c4">
      <div class="wcard">
        <div class="wlabel">Emby 连接</div>
        <div class="dotstate mt8" :class="embyConfigured ? 'on' : 'off'">
          <i></i>{{ embyConfigured ? '已配置' : '未配置' }}
        </div>
        <div class="muted mt8" style="font-size: 11.5px">密钥只存在服务端，不下发客户端</div>
      </div>
      <div class="wcard">
        <div class="wlabel">已绑定媒体库</div>
        <div class="mono" style="font-size: 22px; font-weight: 800">{{ libraries.length }}</div>
        <div class="muted mt8" style="font-size: 11.5px">
          共 {{ courseRows.length }} 门课程使用这些库
        </div>
      </div>
      <div
        class="wcard"
        :style="unavailableTotal > 0 ? 'border-color:var(--ochre-line);background:var(--ochre-soft)' : ''"
      >
        <div class="wlabel" :style="unavailableTotal > 0 ? 'color:var(--ochre)' : ''">
          下架课时
        </div>
        <div
          class="mono"
          :style="`font-size:22px;font-weight:800${unavailableTotal > 0 ? ';color:var(--ochre)' : ''}`"
        >
          {{ unavailableTotal }}
        </div>
        <div class="muted mt8" style="font-size: 11.5px">远端已删除，本地记录保留</div>
      </div>
      <div
        class="wcard"
        :style="missingCourses.length > 0 ? 'border-color:var(--red-line);background:var(--red-soft)' : ''"
      >
        <div class="wlabel" :style="missingCourses.length > 0 ? 'color:var(--red)' : ''">失联</div>
        <div
          class="mono"
          :style="`font-size:17px;font-weight:800${missingCourses.length > 0 ? ';color:var(--red)' : ''}`"
        >
          {{ missingCourses.length }} 门 · {{ missingResourceTotal }} 课时
        </div>
        <div class="muted mt8" style="font-size: 11.5px">需重新绑定或归档</div>
      </div>
    </div>

    <div class="wcard mt16">
      <div class="between">
        <b style="font-size: 14px">媒体库绑定</b>
        <div class="row" style="gap: 8px">
          <button
            class="wbtn ghost sm"
            :disabled="busy || !embyConfigured"
            @click="loadRemoteLibraries"
          >
            从 Emby 读取
          </button>
          <button class="wbtn sm" :disabled="busy" @click="saveLibraries">保存绑定</button>
          <button class="wbtn sm" :disabled="busy || !embyConfigured" @click="importOpen = true">导入课程</button>
        </div>
      </div>
      <p class="lead" style="margin: 6px 0 12px">
        绑定决定同步扫描范围。书籍库按 ADR-0030 只落枚举值，V2 不参与同步。
      </p>
      <div class="table-wrap">
        <table class="wt">
          <thead>
            <tr>
              <th>媒体库</th>
              <th>Item ID</th>
              <th>内容类型</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="library in libraries" :key="library.id">
              <td>
                <b>{{ library.name }}</b>
              </td>
              <td class="mono muted">{{ library.id }}</td>
              <td>
                <select v-model="library.type" class="winput" style="width: auto" aria-label="内容类型" :disabled="busy">
                  <option v-for="type in LIBRARY_TYPES" :key="type" :value="type">
                    {{ libraryTypeLabel(type) }}
                  </option>
                </select>
              </td>
              <td style="text-align: right">
                <button class="wbtn ghost sm" :disabled="busy" @click="removeLibrary(library.id)">
                  移除
                </button>
              </td>
            </tr>
            <tr v-if="libraries.length === 0">
              <td colspan="4" class="empty">
                还没有绑定媒体库。{{
                  embyConfigured ? '点「从 Emby 读取」拉取可选库。' : 'Emby 配置完成后即可读取可选库。'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <template v-if="remoteLoaded">
        <div class="wlabel" style="margin-top: 14px">Emby 侧可选媒体库</div>
        <div class="wbtn-row">
          <button
            v-for="library in remoteLibraries"
            :key="library.id"
            class="pill"
            :class="{ on: libraries.some((item) => item.id === library.id) }"
            :disabled="busy"
            @click="addLibrary(library)"
          >
            {{ library.name }}
          </button>
          <span v-if="remoteLibraries.length === 0" class="muted" style="font-size: 12px">
            Emby 没有返回任何用户可见媒体库
          </span>
        </div>
      </template>
    </div>


    <div class="wgrid c2 mt16">
      <div class="wcard" style="border-color: var(--ochre-line)">
        <b style="font-size: 14px">课时映射待确认</b>
        <p class="lead" style="margin: 6px 0 12px">
          Item ID 变了且指纹无法唯一匹配时不自动合并，必须逐项确认。匹配优先级：当前 Item ID →
          课程内唯一来源指纹 → 唯一标题且时长差 ≤2 秒 → 管理员确认。一对多或多对一一律人工处理。
        </p>
        <div class="wlabel">选择需要处理的课程</div>
        <select
          v-model="mappingCourseId"
          class="winput"
          aria-label="选择需要处理映射的课程"
          @change="loadMappingDetail"
        >
          <option value="">请选择课程</option>
          <option v-for="row in courseRows" :key="row.id" :value="row.id">
            {{ row.title }}{{ row.sourceMissing ? ' · 来源失联' : row.unavailableCount ? ` · ${row.unavailableCount} 课时下架` : '' }}
          </option>
        </select>

        <div class="table-wrap mt12">
          <table class="wt">
            <thead>
              <tr>
                <th>本地课时</th>
                <th>新远端 Item ID</th>
                <th style="text-align: right">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="resource in mappingResources" :key="resource.id">
                <td>
                  <b>{{ resource.title }}</b>
                  <div class="cell-sub">
                    序号 {{ resource.sortIndex }} · {{ formatDuration(resource.durationMs) }}
                  </div>
                </td>
                <td>
                  <input
                    v-model="mappingDrafts[resource.id]"
                    class="winput mono"
                    placeholder="粘贴远端 Item ID"
                    :aria-label="`${resource.title} 的新远端 Item ID`"
                  />
                </td>
                <td style="text-align: right">
                  <button
                    class="wbtn sm"
                    style="white-space: nowrap"
                    :disabled="busy"
                    @click="confirmMapping(resource)"
                  >
                    确认同一课时
                  </button>
                </td>
              </tr>
              <tr v-if="mappingResources.length === 0">
                <td colspan="3" class="empty">
                  {{
                    mappingCourseId
                      ? '这门课程当前没有课时'
                      : courseRows.length === 0
                        ? '暂无课程'
                        : '先在上方选择一门课程'
                  }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="wcard" style="border-color: var(--red-line)">
        <b style="font-size: 14px">失联处理与迁移</b>
        <p class="lead" style="margin: 6px 0 12px">
          远端删除只标记不可用，本地行与学习记录一律保留。课程失联后在学习端隐藏，历史统计不变。
        </p>
        <div class="table-wrap">
          <table class="wt">
            <thead>
              <tr>
                <th>对象</th>
                <th>情况</th>
                <th>影响</th>
                <th style="text-align: right">处理</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in attentionCourses" :key="row.id">
                <td>
                  <b>{{ row.title }}</b>
                  <div class="cell-sub">{{ row.resourceCount }} 课时</div>
                </td>
                <td>
                  <span class="dotstate" :class="row.sourceMissing ? 'off' : 'idle'">
                    <i></i>{{ row.sourceMissing ? '课程失联' : '课时下架' }}
                  </span>
                </td>
                <td>
                  {{ row.sourceMissing ? '学习 Tab 隐藏' : `${row.unavailableCount} 课时不可播放` }}
                  <div class="cell-sub">历史统计保留</div>
                </td>
                <td style="text-align: right">
                  <button
                    class="wbtn sm"
                    style="white-space: nowrap"
                    :disabled="busy"
                    @click="rebindCourseId = row.id"
                  >
                    重新绑定
                  </button>
                </td>
              </tr>
              <tr v-if="attentionCourses.length === 0">
                <td colspan="4" class="empty">当前没有失联课程或下架课时</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="muted mt10" style="font-size: 11.5px">
          课时下架后，引用它的 Todo 在 App 内显示「课时已下架」，学员只能补记完成或删除（删除仍需填原因）。课程需要下架时到课程库页归档。
        </div>
      </div>
    </div>

    <div class="wcard mt16">
      <b style="font-size: 14px">
        重新绑定向导{{ selectedRebindCourse ? ` · ${selectedRebindCourse.title}` : '' }}
      </b>
      <p class="lead" style="margin: 6px 0 12px">
        先预览映射方案，确认四个数字符合预期后再提交。提交在一个短事务内完成：更新父绑定 →
        原位改写来源标识 → 创建新课时 → 标记下架 → 写审计。任一步失败整体回滚，保留上一次可用快照。
      </p>

      <div class="wgrid c3">
        <div>
          <div class="wlabel">① 选择课程</div>
          <select v-model="rebindCourseId" class="winput" aria-label="选择要重新绑定的课程">
            <option value="">请选择课程</option>
            <option v-for="course in courses" :key="course.id" :value="course.id">
              {{ course.title }}{{ course.sourceMissing ? ' · 失联' : '' }}
            </option>
          </select>
        </div>
        <div>
          <div class="wlabel">② 新父节点 Item ID</div>
          <input
            v-model="newParentRef"
            class="winput mono"
            placeholder="例如 8f21c4d0e5b34a7f9c12"
            aria-label="新父节点 Item ID"
          />
        </div>
        <div>
          <div class="wlabel">③ 匹配结果</div>
          <div class="winput mono" style="background: var(--ink-soft)">
            {{
              plan
                ? `原位 ${plan.inPlace} · 新增 ${plan.created} · 下架 ${plan.markedUnavailable} · 待确认 ${plan.ambiguous}`
                : '尚未预览'
            }}
          </div>
        </div>
      </div>

      <div class="wlabel" style="margin-top: 14px">按关键字联想 Emby 来源</div>
      <div class="winput-row">
        <input
          v-model="searchQuery"
          class="winput"
          placeholder="输入课程名关键字"
          aria-label="Emby 来源关键字"
          @keyup.enter="searchSources"
        />
        <button
          class="wbtn ghost"
          style="flex: 0 0 auto; white-space: nowrap"
          :disabled="busy || !embyConfigured"
          @click="searchSources"
        >
          <svg class="icon s14"><use href="#i-search" /></svg>
          联想
        </button>
      </div>
      <div v-if="searchResults.length > 0" class="table-wrap mt12">
        <table class="wt">
          <thead>
            <tr>
              <th>名称</th>
              <th>类型</th>
              <th>Item ID</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="source in searchResults" :key="source.id">
              <td>{{ source.name }}</td>
              <td><span class="badge b-ink">{{ source.itemType }}</span></td>
              <td class="mono muted">{{ source.id }}</td>
              <td style="text-align: right">
                <button class="wbtn ghost sm" @click="newParentRef = source.id">用作父节点</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="plan" class="wgrid c4 mt12">
        <div class="wcard">
          <div class="wlabel">原位保留</div>
          <div class="mono" style="font-size: 22px; font-weight: 800">{{ plan.inPlace }}</div>
          <div class="muted mt8" style="font-size: 11.5px">仅换来源标识，记录保留</div>
        </div>
        <div class="wcard">
          <div class="wlabel">新增课时</div>
          <div class="mono" style="font-size: 22px; font-weight: 800">{{ plan.created }}</div>
          <div class="muted mt8" style="font-size: 11.5px">创建新本地 ID</div>
        </div>
        <div class="wcard">
          <div class="wlabel">标记下架</div>
          <div class="mono" style="font-size: 22px; font-weight: 800">
            {{ plan.markedUnavailable }}
          </div>
          <div class="muted mt8" style="font-size: 11.5px">本地行与记录保留</div>
        </div>
        <div
          class="wcard"
          :style="plan.ambiguous > 0 ? 'border-color:var(--ochre-line);background:var(--ochre-soft)' : ''"
        >
          <div class="wlabel" :style="plan.ambiguous > 0 ? 'color:var(--ochre)' : ''">待确认</div>
          <div
            class="mono"
            :style="`font-size:22px;font-weight:800${plan.ambiguous > 0 ? ';color:var(--ochre)' : ''}`"
          >
            {{ plan.ambiguous }}
          </div>
          <div class="muted mt8" style="font-size: 11.5px">歧义项需逐项人工确认</div>
        </div>
      </div>

      <div class="row mt12" style="gap: 9px">
        <button
          class="wbtn ghost"
          :disabled="busy || !embyConfigured || !rebindCourseId || !newParentRef.trim()"
          @click="preview"
        >
          预览映射方案
        </button>
        <button class="wbtn" :disabled="busy || !plan" @click="rebindConfirmOpen = true">
          在单个事务内提交迁移
        </button>
        <button class="wbtn ghost" :disabled="busy || !plan" @click="plan = null">取消</button>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        元数据是纯展示与筛选维度，改名或重打标签<b>不触发任何数据迁移</b>；统计按课程与课时 ID
        聚合，历史结果不会变化。
      </div>
    </div>
  </template>

  <EmbyCourseImportDialog v-if="importOpen" @close="importOpen = false" @busy="busy = $event" @imported="load(true)" />

  <div v-if="rebindConfirmOpen" class="modal-backdrop">
    <div class="modal danger">
      <h3>提交迁移</h3>
      <p class="sub">
        将把「{{ selectedRebindCourse?.title ?? '所选课程' }}」的父节点改为新来源，并按预览方案原位保留 {{ plan?.inPlace ?? 0 }} 项、新增 {{ plan?.created ?? 0 }} 项、标记下架
        {{ plan?.markedUnavailable ?? 0 }} 项。本地课时 ID 不变，学习记录全部保留；歧义
        {{ plan?.ambiguous ?? 0 }} 项仍需逐项确认。任一步失败整体回滚。
      </p>
      <div class="modal-foot">
        <button class="wbtn ghost" :disabled="busy" @click="rebindConfirmOpen = false">取消</button>
        <button class="wbtn red" :disabled="busy" @click="rebind">确认提交</button>
      </div>
    </div>
  </div>
</template>
