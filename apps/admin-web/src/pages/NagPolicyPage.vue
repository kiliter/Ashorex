<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { api, ApiError } from '@/api/client';

/**
 * 结构严格对应原型 8-3：一张「全局默认」表单卡（按原型分四组 + 督学提醒规则）
 * 加一张「按用户覆盖」表格卡。所有阈值、渠道与免打扰只在此维护，App 端只读展示。
 *
 * 覆盖只允许改阈值与渠道类字段：扫描周期、在线宽限期、心跳间隔、文案模板与督学提醒
 * 始终跟随全局（后端 POST /nag-policy/overrides 会用全局值回填这些字段）。
 */
interface NagPolicy {
  scanIntervalMinutes: number;
  presenceGraceSeconds: number;
  heartbeatIntervalSeconds: number;
  firstThresholdMinutes: number;
  repeatIntervalMinutes: number;
  dailyMax: number;
  fullscreenTimeoutMinutes: number;
  quietStart: string;
  quietEnd: string;
  minPending: number;
  minReasonLength: number;
  channelFullscreenEnabled: boolean;
  channelServerchanEnabled: boolean;
  messageTemplate: string;
  notifySupervisorOnBulkDelete: boolean;
  notifySupervisorOnGaveUp: boolean;
  notifySupervisorOnHalfDoneDelete: boolean;
}

interface OverrideRow {
  userId: string;
  username: string;
  displayName: string;
  policy: NagPolicy;
}

interface UserOption {
  id: string;
  username: string;
  displayName: string;
}

interface NagPolicyResponse {
  global: NagPolicy;
  overrides: OverrideRow[];
  users: UserOption[];
}

/** 全局默认表单，字段名与后端 GlobalPolicyRequest 一一对应。 */
interface GlobalForm {
  scanIntervalMinutes: number;
  presenceGraceSeconds: number;
  heartbeatIntervalSeconds: number;
  firstThresholdMinutes: number;
  repeatIntervalMinutes: number;
  dailyMax: number;
  fullscreenTimeoutMinutes: number;
  quietStart: string;
  quietEnd: string;
  minPending: number;
  minReasonLength: number;
  channelFullscreenEnabled: boolean;
  channelServerchanEnabled: boolean;
  messageTemplate: string;
  notifyBulkDelete: boolean;
  notifyGaveUp: boolean;
  notifyHalfDoneDelete: boolean;
}

/** 出厂值与 Spec 7.5 的默认策略一致，「恢复出厂值」只回填表单，仍需点保存才生效。 */
const FACTORY: GlobalForm = {
  scanIntervalMinutes: 5,
  presenceGraceSeconds: 150,
  heartbeatIntervalSeconds: 60,
  firstThresholdMinutes: 90,
  repeatIntervalMinutes: 60,
  dailyMax: 3,
  fullscreenTimeoutMinutes: 10,
  quietStart: '23:30',
  quietEnd: '07:00',
  minPending: 1,
  minReasonLength: 5,
  channelFullscreenEnabled: true,
  channelServerchanEnabled: true,
  messageTemplate: '{{user}} 今天还有 {{pending}} 项未完成，已经 {{idleMinutes}} 分钟没有任何操作。',
  notifyBulkDelete: true,
  notifyGaveUp: true,
  notifyHalfDoneDelete: true,
};

const form = reactive<GlobalForm>({ ...FACTORY });
const overrides = ref<OverrideRow[]>([]);
const users = ref<UserOption[]>([]);
const loading = ref(true);
const error = ref('');
const notice = ref('');
const savingGlobal = ref(false);
const transportMode = ref<'SSE' | 'HEARTBEAT'>('SSE');
const savingTransport = ref(false);

/** 通知模式独立保存，不意外提交其他尚未确认的策略编辑。 */
async function saveTransport(): Promise<void> {
  savingTransport.value = true;
  error.value = '';
  notice.value = '';
  try {
    await api.post('/nag-transport', { mode: transportMode.value });
    notice.value = '通知方式已保存，客户端下一次心跳生效，无需重启';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '通知方式保存失败';
  } finally {
    savingTransport.value = false;
  }
}

/** 覆盖编辑区：editing 为空表示未展开；userId 已存在时是编辑，否则是新增。 */
interface OverrideForm {
  userId: string;
  firstThresholdMinutes: number;
  repeatIntervalMinutes: number;
  dailyMax: number;
  fullscreenTimeoutMinutes: number;
  quietStart: string;
  quietEnd: string;
  minPending: number;
  minReasonLength: number;
  channelFullscreenEnabled: boolean;
  channelServerchanEnabled: boolean;
}

const editing = ref<OverrideForm | null>(null);
const savingOverride = ref(false);

/** 后端 LocalTime 序列化为 `HH:mm:ss`，而 `<input type="time">` 只接受 `HH:mm`。 */
function toTimeInput(value: string): string {
  return value.slice(0, 5);
}

function applyGlobal(policy: NagPolicy): void {
  form.scanIntervalMinutes = policy.scanIntervalMinutes;
  form.presenceGraceSeconds = policy.presenceGraceSeconds;
  form.heartbeatIntervalSeconds = policy.heartbeatIntervalSeconds;
  form.firstThresholdMinutes = policy.firstThresholdMinutes;
  form.repeatIntervalMinutes = policy.repeatIntervalMinutes;
  form.dailyMax = policy.dailyMax;
  form.fullscreenTimeoutMinutes = policy.fullscreenTimeoutMinutes;
  form.quietStart = toTimeInput(policy.quietStart);
  form.quietEnd = toTimeInput(policy.quietEnd);
  form.minPending = policy.minPending;
  form.minReasonLength = policy.minReasonLength;
  form.channelFullscreenEnabled = policy.channelFullscreenEnabled;
  form.channelServerchanEnabled = policy.channelServerchanEnabled;
  form.messageTemplate = policy.messageTemplate;
  form.notifyBulkDelete = policy.notifySupervisorOnBulkDelete;
  form.notifyGaveUp = policy.notifySupervisorOnGaveUp;
  form.notifyHalfDoneDelete = policy.notifySupervisorOnHalfDoneDelete;
}

async function load(): Promise<void> {
  try {
    const data = await api.get<NagPolicyResponse>('/nag-policy');
    transportMode.value = (await api.get<{ mode: 'SSE' | 'HEARTBEAT' }>('/nag-transport')).mode;
    applyGlobal(data.global);
    overrides.value = data.overrides;
    users.value = data.users;
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

async function saveGlobal(): Promise<void> {
  savingGlobal.value = true;
  error.value = '';
  notice.value = '';
  try {
    await api.post('/nag-policy', { ...form });
    notice.value = '全局默认策略已保存，下一轮扫描生效';
    await load();
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '保存失败';
  } finally {
    savingGlobal.value = false;
  }
}

function resetFactory(): void {
  Object.assign(form, FACTORY);
  notice.value = '已回填出厂值，仍需点「保存全局默认」才会生效';
}

/** 未被覆盖的活跃用户才能新增覆盖，避免同一用户出现两行。 */
const assignableUsers = computed(() =>
  users.value.filter((user) => !overrides.value.some((row) => row.userId === user.id)),
);

function startCreate(): void {
  const candidate = users.value.find(
    (user) => !overrides.value.some((row) => row.userId === user.id),
  );
  editing.value = {
    userId: candidate?.id ?? '',
    firstThresholdMinutes: form.firstThresholdMinutes,
    repeatIntervalMinutes: form.repeatIntervalMinutes,
    dailyMax: form.dailyMax,
    fullscreenTimeoutMinutes: form.fullscreenTimeoutMinutes,
    quietStart: form.quietStart,
    quietEnd: form.quietEnd,
    minPending: form.minPending,
    minReasonLength: form.minReasonLength,
    channelFullscreenEnabled: form.channelFullscreenEnabled,
    channelServerchanEnabled: form.channelServerchanEnabled,
  };
}

function startEdit(row: OverrideRow): void {
  editing.value = {
    userId: row.userId,
    firstThresholdMinutes: row.policy.firstThresholdMinutes,
    repeatIntervalMinutes: row.policy.repeatIntervalMinutes,
    dailyMax: row.policy.dailyMax,
    fullscreenTimeoutMinutes: row.policy.fullscreenTimeoutMinutes,
    quietStart: toTimeInput(row.policy.quietStart),
    quietEnd: toTimeInput(row.policy.quietEnd),
    minPending: row.policy.minPending,
    minReasonLength: row.policy.minReasonLength,
    channelFullscreenEnabled: row.policy.channelFullscreenEnabled,
    channelServerchanEnabled: row.policy.channelServerchanEnabled,
  };
}

async function saveOverride(): Promise<void> {
  const payload = editing.value;
  if (!payload || !payload.userId) {
    error.value = '请先选择要覆盖的用户';
    return;
  }
  savingOverride.value = true;
  error.value = '';
  notice.value = '';
  try {
    await api.post('/nag-policy/overrides', payload);
    notice.value = '用户覆盖已保存';
    editing.value = null;
    await load();
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '保存覆盖失败';
  } finally {
    savingOverride.value = false;
  }
}

/** 删除覆盖会让该用户立即回到全局默认，需二次确认。 */
async function removeOverride(row: OverrideRow): Promise<void> {
  if (!window.confirm(`删除 ${row.username} 的覆盖后，该用户将回到全局默认策略。确认删除？`)) {
    return;
  }
  error.value = '';
  notice.value = '';
  try {
    await api.delete(`/nag-policy/overrides/${encodeURIComponent(row.userId)}`);
    notice.value = `已删除 ${row.username} 的覆盖`;
    if (editing.value?.userId === row.userId) {
      editing.value = null;
    }
    await load();
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '删除覆盖失败';
  }
}

function editingUsername(userId: string): string {
  return users.value.find((user) => user.id === userId)?.username ?? userId;
}

const editingIsNew = computed(
  () => editing.value !== null && !overrides.value.some((row) => row.userId === editing.value?.userId),
);
/** 分区隐藏不销毁表单，编辑草稿在切换时保留。 */
const section = ref('global');
/** 切换分区回到内容顶部，保留表单草稿但不继承上一分区滚动位置。 */
const scrollArea = ref<HTMLElement | null>(null);
watch(section, () => scrollArea.value?.scrollTo({ top: 0 }), { flush: 'post' });
</script>

<template>
  <!-- 标题和操作固定，长内容在工作区内滚动。 -->
  <section class="workspace-page">
  <div class="page-head">
    <h1>催办策略</h1>
    <p class="lead">
      服务端定时扫描每日待办完成情况。触发条件：当日仍有未完成 Todo，且「最近一次有效上报」距今超过无操作阈值，且不在免打扰时段内。所有阈值、渠道与免打扰只在此维护，App 端只读展示。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>
  <p v-if="loading" class="loading">加载中…</p>

  <nav class="workspace-tabs" aria-label="页面分区">
    <button
      type="button"
      :class="{ on: section === 'global' }"
      :aria-pressed="section === 'global'"
      @click="section = 'global'"
    >全局默认</button>
    <button
      type="button"
      :class="{ on: section === 'transport' }"
      :aria-pressed="section === 'transport'"
      @click="section = 'transport'"
    >通知方式</button>
    <button
      type="button"
      :class="{ on: section === 'users' }"
      :aria-pressed="section === 'users'"
      @click="section = 'users'"
    >用户覆盖</button>
  </nav>
  <div v-if="!loading" ref="scrollArea" class="workspace-scroll">
    <div v-show="section === 'transport'" class="wcard">
      <label class="wlabel" for="nag-transport">客户端催办通知方式（全局）</label>
      <select id="nag-transport" v-model="transportMode" class="winput" :disabled="savingTransport">
        <option value="SSE">SSE 实时通知（默认）</option>
        <option value="HEARTBEAT">心跳轮询（原方式）</option>
      </select>
      <p class="muted">SSE 收到通知后立即查询催办；心跳轮询等待下一次心跳。两种方式都保留心跳在线判定。</p>
      <button class="wbtn" :disabled="savingTransport" @click="saveTransport">{{ savingTransport ? '保存中…' : '保存通知方式' }}</button>
    </div>
    <div v-show="section === 'global'" class="wcard">
      <b style="font-size: 14px">全局默认</b>

      <div class="wgrid c4 mt12">
        <div>
          <div class="wlabel">扫描周期（分钟）</div>
          <div class="winput-row">
            <input v-model.number="form.scanIntervalMinutes" class="winput mono" type="number" min="1" max="60" />
            <span class="unit">1 – 60</span>
          </div>
        </div>
        <div>
          <div class="wlabel">在线宽限期（秒）</div>
          <div class="winput-row">
            <input v-model.number="form.presenceGraceSeconds" class="winput mono" type="number" min="30" max="900" />
            <span class="unit">心跳 × 2.5</span>
          </div>
        </div>
        <div>
          <div class="wlabel">心跳间隔（秒）· 下发给 App</div>
          <div class="winput-row">
            <input
              v-model.number="form.heartbeatIntervalSeconds"
              class="winput mono"
              type="number"
              min="30"
              max="300"
            />
            <span class="unit">30 – 300</span>
          </div>
        </div>
        <div>
          <div class="wlabel">空闲判定依据</div>
          <select class="winput" disabled>
            <option>最近有效上报</option>
          </select>
        </div>
      </div>

      <div class="wgrid c4 mt16" style="border-top: 1px solid var(--hair); padding-top: 16px">
        <div>
          <div class="wlabel">首次催办阈值（分钟）</div>
          <div class="winput-row">
            <input v-model.number="form.firstThresholdMinutes" class="winput mono" type="number" min="10" max="720" />
            <span class="unit">10 – 720</span>
          </div>
        </div>
        <div>
          <div class="wlabel">重复催办间隔（分钟）</div>
          <div class="winput-row">
            <input v-model.number="form.repeatIntervalMinutes" class="winput mono" type="number" min="0" max="720" />
            <span class="unit">0 = 关闭</span>
          </div>
        </div>
        <div>
          <div class="wlabel">每日最多催办次数</div>
          <div class="winput-row">
            <input v-model.number="form.dailyMax" class="winput mono" type="number" min="1" max="10" />
            <span class="unit">1 – 10</span>
          </div>
        </div>
        <div>
          <div class="wlabel">全屏未响应转推延迟（分钟）</div>
          <div class="winput-row">
            <input
              v-model.number="form.fullscreenTimeoutMinutes"
              class="winput mono"
              type="number"
              min="1"
              max="60"
            />
            <span class="unit">1 – 60</span>
          </div>
        </div>
      </div>

      <div class="wgrid c3 mt16" style="border-top: 1px solid var(--hair); padding-top: 16px">
        <div>
          <div class="wlabel">免打扰时段</div>
          <div class="row" style="gap: 8px">
            <input v-model="form.quietStart" class="winput mono" type="time" style="flex: 1" />
            <span class="muted">—</span>
            <input v-model="form.quietEnd" class="winput mono" type="time" style="flex: 1" />
          </div>
        </div>
        <div>
          <div class="wlabel">最少未完成项才触发</div>
          <div class="winput-row">
            <input v-model.number="form.minPending" class="winput mono" type="number" min="1" max="20" />
            <span class="unit">项</span>
          </div>
        </div>
        <div>
          <div class="wlabel">原因最少字数</div>
          <div class="winput-row">
            <input v-model.number="form.minReasonLength" class="winput mono" type="number" min="0" max="100" />
            <span class="unit">字</span>
          </div>
        </div>
      </div>

      <div class="wgrid c2 mt16" style="border-top: 1px solid var(--hair); padding-top: 16px">
        <div>
          <div class="wlabel">启用渠道</div>
          <table class="wt">
            <tbody>
              <tr>
                <td style="width: 34px">
                  <label class="wcheck"><input v-model="form.channelFullscreenEnabled" type="checkbox" /></label>
                </td>
                <td>
                  <b>客户端全屏提醒</b>
                  <div class="muted" style="font-size: 11px">App 在线时投递，必须填写原因才能关闭</div>
                </td>
              </tr>
              <tr>
                <td>
                  <label class="wcheck"><input v-model="form.channelServerchanEnabled" type="checkbox" /></label>
                </td>
                <td>
                  <b>Server 酱</b>
                  <div class="muted" style="font-size: 11px">App 离线或全屏超时未回应时投递</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div>
          <div class="wlabel">催办文案模板</div>
          <textarea v-model="form.messageTemplate" class="winput" style="min-height: 86px" />
          <div class="muted mt6" style="font-size: 11px">
            可用变量：user / pending / idleMinutes / date
          </div>
        </div>
      </div>

      <div class="mt16" style="border-top: 1px solid var(--hair); padding-top: 16px">
        <div class="wlabel">触发督学提醒的规则</div>
        <table class="wt">
          <tbody>
            <tr>
              <td style="width: 34px">
                <label class="wcheck"><input v-model="form.notifyBulkDelete" type="checkbox" /></label>
              </td>
              <td>
                <b>单日删除 ≥ 3 条</b>
                <div class="muted" style="font-size: 11px">通知主督学人</div>
              </td>
            </tr>
            <tr>
              <td>
                <label class="wcheck"><input v-model="form.notifyGaveUp" type="checkbox" /></label>
              </td>
              <td>
                <b>原因含「不想学」类标签</b>
                <div class="muted" style="font-size: 11px">立即通知主督学人</div>
              </td>
            </tr>
            <tr>
              <td>
                <label class="wcheck"><input v-model="form.notifyHalfDoneDelete" type="checkbox" /></label>
              </td>
              <td>
                <b>删除已完成 50% 以上的课程 Todo</b>
                <div class="muted" style="font-size: 11px">计入删除台账异常项</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>


    </div>

    <div v-show="section === 'users' && !editing" class="wcard">
      <div class="between">
        <b style="font-size: 14px">按用户覆盖</b>
        <button class="wbtn ghost sm" @click="startCreate">新增覆盖</button>
      </div>
      <div class="table-wrap mt10">
        <table class="wt">
          <thead>
            <tr>
              <th>用户</th>
              <th>首次阈值</th>
              <th>重复间隔</th>
              <th>每日上限</th>
              <th>渠道</th>
              <th>免打扰</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in overrides" :key="row.userId">
              <td>
                <b>{{ row.username }}</b>
                <div class="cell-sub">{{ row.displayName }}</div>
              </td>
              <td class="mono">{{ row.policy.firstThresholdMinutes }} 分</td>
              <td v-if="row.policy.repeatIntervalMinutes > 0" class="mono">
                {{ row.policy.repeatIntervalMinutes }} 分
              </td>
              <td v-else class="muted">关闭</td>
              <td class="mono">{{ row.policy.dailyMax }}</td>
              <td>
                <span v-if="row.policy.channelFullscreenEnabled" class="badge b-ink">全屏</span>
                <span v-if="row.policy.channelServerchanEnabled" class="badge b-ink">Server 酱</span>
                <span
                  v-if="!row.policy.channelFullscreenEnabled && !row.policy.channelServerchanEnabled"
                  class="badge b-red"
                >
                  全部关闭
                </span>
              </td>
              <td class="mono muted">
                {{ toTimeInput(row.policy.quietStart) }} – {{ toTimeInput(row.policy.quietEnd) }}
              </td>
              <td style="text-align: right">
                <button class="wbtn ghost sm" @click="startEdit(row)">编辑</button>
                <button class="wbtn ghost sm" @click="removeOverride(row)">删除</button>
              </td>
            </tr>
            <tr v-if="overrides.length === 0">
              <td colspan="7" class="empty">还没有任何用户覆盖，全部用户使用全局默认</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        未列出的用户使用全局默认。覆盖只影响阈值与渠道，不改变扫描周期与在线宽限期。
      </div>
    </div>

    <div v-if="editing" v-show="section === 'users'" class="wcard">
      <div class="between">
        <b style="font-size: 14px">
          {{ editingIsNew ? '新增覆盖' : `编辑覆盖 · ${editingUsername(editing.userId)}` }}
        </b>
        <button class="wbtn ghost sm" @click="editing = null">收起</button>
      </div>

      <div class="wgrid c4 mt12">
        <div>
          <div class="wlabel">用户</div>
          <select v-if="editingIsNew" v-model="editing.userId" class="winput">
            <option v-for="user in assignableUsers" :key="user.id" :value="user.id">
              {{ user.username }} · {{ user.displayName }}
            </option>
          </select>
          <div v-else class="winput mono">{{ editingUsername(editing.userId) }}</div>
        </div>
        <div>
          <div class="wlabel">首次催办阈值（分钟）</div>
          <div class="winput-row">
            <input
              v-model.number="editing.firstThresholdMinutes"
              class="winput mono"
              type="number"
              min="10"
              max="720"
            />
            <span class="unit">分</span>
          </div>
        </div>
        <div>
          <div class="wlabel">重复催办间隔（分钟）</div>
          <div class="winput-row">
            <input
              v-model.number="editing.repeatIntervalMinutes"
              class="winput mono"
              type="number"
              min="0"
              max="720"
            />
            <span class="unit">0 = 关闭</span>
          </div>
        </div>
        <div>
          <div class="wlabel">每日最多催办次数</div>
          <div class="winput-row">
            <input v-model.number="editing.dailyMax" class="winput mono" type="number" min="1" max="10" />
            <span class="unit">次</span>
          </div>
        </div>
      </div>

      <div class="wgrid c4 mt12">
        <div>
          <div class="wlabel">全屏未响应转推延迟（分钟）</div>
          <div class="winput-row">
            <input
              v-model.number="editing.fullscreenTimeoutMinutes"
              class="winput mono"
              type="number"
              min="1"
              max="60"
            />
            <span class="unit">分</span>
          </div>
        </div>
        <div>
          <div class="wlabel">免打扰时段</div>
          <div class="row" style="gap: 8px">
            <input v-model="editing.quietStart" class="winput mono" type="time" style="flex: 1" />
            <span class="muted">—</span>
            <input v-model="editing.quietEnd" class="winput mono" type="time" style="flex: 1" />
          </div>
        </div>
        <div>
          <div class="wlabel">最少未完成项才触发</div>
          <div class="winput-row">
            <input v-model.number="editing.minPending" class="winput mono" type="number" min="1" max="20" />
            <span class="unit">项</span>
          </div>
        </div>
        <div>
          <div class="wlabel">原因最少字数</div>
          <div class="winput-row">
            <input v-model.number="editing.minReasonLength" class="winput mono" type="number" min="0" max="100" />
            <span class="unit">字</span>
          </div>
        </div>
      </div>

      <div class="wlabel" style="margin-top: 14px">启用渠道</div>
      <div class="row" style="gap: 18px">
        <label class="wcheck">
          <input v-model="editing.channelFullscreenEnabled" type="checkbox" />客户端全屏提醒
        </label>
        <label class="wcheck">
          <input v-model="editing.channelServerchanEnabled" type="checkbox" />Server 酱
        </label>
      </div>


      <div class="muted mt10" style="font-size: 11.5px">
        扫描周期、在线宽限期、心跳间隔、文案模板与督学提醒规则不可按用户覆盖，始终跟随全局默认。
      </div>
    </div>
  </div>
      <footer v-if="!loading && section === 'global'" class="workspace-actions">
        <button class="wbtn" :disabled="savingGlobal" @click="saveGlobal">
          {{ savingGlobal ? '保存中…' : '保存全局默认' }}
        </button>
        <button class="wbtn ghost" :disabled="savingGlobal" @click="resetFactory">恢复出厂值</button>
      </footer>
      <footer v-if="!loading && section === 'users' && editing" class="workspace-actions">
        <button class="wbtn" :disabled="savingOverride" @click="saveOverride">
          {{ savingOverride ? '保存中…' : '保存覆盖' }}
        </button>
        <button class="wbtn ghost" @click="editing = null">取消</button>
      </footer>
  </section>
</template>
