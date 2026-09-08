<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { api, ApiError } from '@/api/client';

/**
 * 结构对应原型 8-9 的用户管理部分：
 * 顶部说明 + 搜索框与「新增用户」按钮同一行 + 全宽用户表 + 表下常驻表单卡片。
 * 表单是常驻卡片而非弹窗（与原型一致）；只有归档这一危险操作走二次确认弹框。
 */

/** 与后端 AccessAdminController.UserRow 对齐；后端不返回任何口令字段。 */
interface UserRow {
  id: string;
  username: string;
  displayName: string;
  timezone: string;
  status: string;
  roles: string[];
  supervisor: boolean;
}

/** 表下同一时刻只展开一个表单，避免出现多套输入互相干扰。 */
type Panel = 'none' | 'create' | 'reset' | 'profile';

/** 后端 timezone 只做字符串存储，这里限定为常用 IANA 时区，避免写入非法值。 */
const TIMEZONES = [
  'Asia/Shanghai',
  'Asia/Hong_Kong',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Europe/London',
  'America/New_York',
  'UTC',
];

const ROLE_LABELS: Record<string, string> = {
  ADMIN: '管理员',
  LEARNER: '学习者',
  SUPERVISOR: '督学人',
};

const rows = ref<UserRow[]>([]);
const keyword = ref('');
const loading = ref(true);
const error = ref('');
const notice = ref('');
const submitting = ref(false);
const panel = ref<Panel>('none');
/** 当前被操作的行；重置密码与改资料都依赖它。 */
const target = ref<UserRow | null>(null);
/** 待确认归档的行；非空即弹出确认框。 */
const archiveTarget = ref<UserRow | null>(null);

const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  timezone: 'Asia/Shanghai',
  supervisor: false,
});
const resetForm = reactive({ password: '', confirm: '' });
const profileForm = reactive({ displayName: '', timezone: 'Asia/Shanghai' });
/** 字段级错误，键为表单字段名。 */
const fieldErrors = ref<Record<string, string>>({});

async function load(): Promise<void> {
  try {
    rows.value = await api.get<UserRow[]>('/users');
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const visibleRows = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  if (!query) {
    return rows.value;
  }
  return rows.value.filter(
    (row) =>
      row.username.toLowerCase().includes(query) ||
      row.displayName.toLowerCase().includes(query),
  );
});

const activeCount = computed(() => rows.value.filter((row) => row.status === 'ACTIVE').length);

/** 统一收敛错误处理：ApiError 用后端 detail，字段错误单独渲染。 */
function handleFailure(cause: unknown, fallback: string): void {
  if (cause instanceof ApiError) {
    fieldErrors.value = cause.fieldErrors;
    error.value = cause.message;
    return;
  }
  error.value = cause instanceof Error ? cause.message : fallback;
}

function resetFeedback(): void {
  error.value = '';
  notice.value = '';
  fieldErrors.value = {};
}

function openCreate(): void {
  resetFeedback();
  target.value = null;
  createForm.username = '';
  createForm.displayName = '';
  createForm.password = '';
  createForm.timezone = 'Asia/Shanghai';
  createForm.supervisor = false;
  panel.value = panel.value === 'create' ? 'none' : 'create';
}

function openReset(row: UserRow): void {
  resetFeedback();
  target.value = row;
  resetForm.password = '';
  resetForm.confirm = '';
  panel.value = 'reset';
}

function openProfile(row: UserRow): void {
  resetFeedback();
  target.value = row;
  profileForm.displayName = row.displayName;
  profileForm.timezone = row.timezone;
  panel.value = 'profile';
}

function closePanel(): void {
  panel.value = 'none';
  target.value = null;
  fieldErrors.value = {};
}

async function createUser(): Promise<void> {
  resetFeedback();
  if (!createForm.username.trim()) {
    fieldErrors.value = { username: '用户名必填' };
    return;
  }
  if (createForm.password.length < 8) {
    fieldErrors.value = { password: '初始密码至少 8 位' };
    return;
  }
  submitting.value = true;
  try {
    await api.post('/users', {
      username: createForm.username.trim(),
      displayName: createForm.displayName.trim() || createForm.username.trim(),
      password: createForm.password,
      timezone: createForm.timezone,
      supervisor: createForm.supervisor,
    });
    notice.value = `已创建用户 ${createForm.username.trim()}`;
    closePanel();
    await load();
  } catch (cause) {
    handleFailure(cause, '创建用户失败');
  } finally {
    submitting.value = false;
  }
}

/** 重置密码会撤销该用户全部刷新令牌，提交前必须二次输入一致。 */
async function resetPassword(): Promise<void> {
  if (!target.value) return;
  resetFeedback();
  if (resetForm.password.length < 8) {
    fieldErrors.value = { password: '新密码至少 8 位' };
    return;
  }
  if (resetForm.password !== resetForm.confirm) {
    fieldErrors.value = { confirm: '两次输入不一致' };
    return;
  }
  const username = target.value.username;
  submitting.value = true;
  try {
    await api.post('/users/reset-password', {
      userId: target.value.id,
      password: resetForm.password,
    });
    notice.value = `已重置 ${username} 的密码，该用户全部登录会话已被撤销，需要用新密码重新登录`;
    closePanel();
  } catch (cause) {
    handleFailure(cause, '重置密码失败');
  } finally {
    submitting.value = false;
  }
}

async function saveProfile(): Promise<void> {
  if (!target.value) return;
  resetFeedback();
  if (!profileForm.displayName.trim()) {
    fieldErrors.value = { displayName: '显示名必填' };
    return;
  }
  const username = target.value.username;
  submitting.value = true;
  try {
    await api.post('/users/profile', {
      userId: target.value.id,
      displayName: profileForm.displayName.trim(),
      timezone: profileForm.timezone,
    });
    notice.value = `已更新 ${username} 的资料`;
    closePanel();
    await load();
  } catch (cause) {
    handleFailure(cause, '更新资料失败');
  } finally {
    submitting.value = false;
  }
}

/** 授予或收回督学身份；收回后该账号在 App 内不再出现督学端入口。 */
async function toggleSupervisor(row: UserRow): Promise<void> {
  resetFeedback();
  submitting.value = true;
  try {
    await api.post('/users/supervisor-role', { userId: row.id, supervisor: !row.supervisor });
    notice.value = row.supervisor
      ? `已收回 ${row.username} 的督学身份`
      : `已授予 ${row.username} 督学身份`;
    await load();
  } catch (cause) {
    handleFailure(cause, '调整督学身份失败');
  } finally {
    submitting.value = false;
  }
}

async function archiveUser(): Promise<void> {
  if (!archiveTarget.value) return;
  const username = archiveTarget.value.username;
  submitting.value = true;
  resetFeedback();
  try {
    await api.post('/users/archive', { entityId: archiveTarget.value.id });
    notice.value = `已归档 ${username}；该账号立即无法登录，彻底删除请到归档区`;
    archiveTarget.value = null;
    await load();
  } catch (cause) {
    handleFailure(cause, '归档失败');
  } finally {
    submitting.value = false;
  }
}

function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role;
}

/** 角色徽标用色：管理员用 ink，督学人用 focus，学习者用 course。 */
function roleBadgeClass(role: string): string {
  if (role === 'ADMIN') return 'b-ink';
  if (role === 'SUPERVISOR') return 'b-focus';
  return 'b-course';
}

/** 管理员账号不允许在后台归档，避免把自己锁在系统外。 */
function archivable(row: UserRow): boolean {
  return row.status === 'ACTIVE' && !row.roles.includes('ADMIN');
}

/** 归档按钮置灰时的原因，同时用作 title 与 aria-label，不让状态只靠灰度传达。 */
function archiveDisabledReason(row: UserRow): string {
  return row.status === 'ACTIVE'
    ? '管理员账号不可归档，否则没人能再登录后台'
    : '账号已归档，恢复或彻底删除请到归档区';
}
</script>

<template>
  <div class="page-head">
    <h1>用户</h1>
    <p class="lead">
      共 {{ activeCount }} 位在用账号。归档后账号立即不能登录、不参与催办扫描与统计；<b>彻底删除请到归档区</b>，届时会级联清除 Todo、进度、附件文件、催办、督学绑定与会话，并要求输入用户名确认。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <div class="between">
    <input v-model="keyword" class="winput" style="width: 240px" placeholder="搜索用户名或显示名" />
    <button class="wbtn" @click="openCreate">
      <svg class="icon s14"><use href="#i-plus" /></svg>
      新增用户
    </button>
  </div>

  <div class="wcard flush mt12">
    <div class="table-wrap">
      <table class="wt">
        <thead>
          <tr>
            <th>用户名</th>
            <th>显示名</th>
            <th>时区</th>
            <th>角色</th>
            <th>状态</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.id">
            <td><b>{{ row.username }}</b></td>
            <td>{{ row.displayName }}</td>
            <td class="muted mono" style="font-size: 11.5px">{{ row.timezone }}</td>
            <td>
              <span
                v-for="role in row.roles"
                :key="role"
                class="badge"
                :class="roleBadgeClass(role)"
                style="margin-right: 4px"
              >
                {{ roleLabel(role) }}
              </span>
            </td>
            <td>
              <span v-if="row.status === 'ACTIVE'" class="dotstate on"><i></i>正常</span>
              <span v-else class="dotstate off"><i></i>已归档</span>
            </td>
            <td style="text-align: right; white-space: nowrap">
              <button class="wbtn ghost sm" @click="openReset(row)">重置密码</button>
              <button class="wbtn ghost sm" style="margin-left: 6px" @click="openProfile(row)">
                编辑
              </button>
              <button
                class="wbtn ghost sm"
                style="margin-left: 6px"
                :disabled="submitting"
                @click="toggleSupervisor(row)"
              >
                {{ row.supervisor ? '收回督学' : '授予督学' }}
              </button>
              <button
                v-if="archivable(row)"
                class="wbtn ghost sm"
                style="margin-left: 6px"
                @click="archiveTarget = row"
              >
                归档
              </button>
              <!-- 不可归档时仍占同一个按钮位，避免整列右对齐被文字宽度带偏 -->
              <button
                v-else
                class="wbtn ghost sm"
                style="margin-left: 6px"
                disabled
                :title="archiveDisabledReason(row)"
                :aria-label="archiveDisabledReason(row)"
              >
                归档
              </button>
            </td>
          </tr>
          <tr v-if="loading">
            <td colspan="6" class="loading">加载中…</td>
          </tr>
          <tr v-else-if="visibleRows.length === 0">
            <td colspan="6" class="empty">没有匹配的用户</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="panel === 'create'" class="wcard mt16">
    <b style="font-size: 14px">新增用户</b>
    <p class="lead" style="margin: 6px 0 12px">
      新账号默认具备学习者身份。初始密码由管理员设定，用户首次登录后应自行修改。
    </p>
    <div class="wgrid c4">
      <div>
        <div class="wlabel">用户名</div>
        <input v-model="createForm.username" class="winput" placeholder="登录用户名" />
        <div v-if="fieldErrors.username" class="field-error">{{ fieldErrors.username }}</div>
      </div>
      <div>
        <div class="wlabel">显示名</div>
        <input v-model="createForm.displayName" class="winput" placeholder="留空则与用户名相同" />
        <div v-if="fieldErrors.displayName" class="field-error">{{ fieldErrors.displayName }}</div>
      </div>
      <div>
        <div class="wlabel">初始密码</div>
        <input
          v-model="createForm.password"
          class="winput"
          type="password"
          autocomplete="new-password"
          placeholder="至少 8 位"
        />
        <div v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</div>
      </div>
      <div>
        <div class="wlabel">时区</div>
        <select v-model="createForm.timezone" class="winput">
          <option v-for="zone in TIMEZONES" :key="zone" :value="zone">{{ zone }}</option>
        </select>
      </div>
    </div>
    <label class="wcheck mt12">
      <input v-model="createForm.supervisor" type="checkbox" />
      同时授予督学身份
    </label>
    <div class="row mt12" style="gap: 9px">
      <button class="wbtn" :disabled="submitting" @click="createUser">
        {{ submitting ? '创建中…' : '创建用户' }}
      </button>
      <button class="wbtn ghost" @click="closePanel">取消</button>
    </div>
  </div>

  <div v-if="panel === 'reset' && target" class="wcard mt16">
    <b style="font-size: 14px">重置密码 · {{ target.username }}</b>
    <p class="notice warn mt10">
      重置成功后会<b>撤销该用户全部登录会话</b>，所有已登录设备都需要用新密码重新登录。后台不保存也不回显任何现有密码。
    </p>
    <div class="wgrid c2">
      <div>
        <div class="wlabel">新密码</div>
        <input
          v-model="resetForm.password"
          class="winput"
          type="password"
          autocomplete="new-password"
          placeholder="至少 8 位"
        />
        <div v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</div>
      </div>
      <div>
        <div class="wlabel">确认新密码</div>
        <input
          v-model="resetForm.confirm"
          class="winput"
          type="password"
          autocomplete="new-password"
          placeholder="再输入一次"
        />
        <div v-if="fieldErrors.confirm" class="field-error">{{ fieldErrors.confirm }}</div>
      </div>
    </div>
    <div class="row mt12" style="gap: 9px">
      <button class="wbtn red" :disabled="submitting" @click="resetPassword">
        {{ submitting ? '提交中…' : '确认重置' }}
      </button>
      <button class="wbtn ghost" @click="closePanel">取消</button>
    </div>
  </div>

  <div v-if="panel === 'profile' && target" class="wcard mt16">
    <b style="font-size: 14px">编辑资料 · {{ target.username }}</b>
    <p class="lead" style="margin: 6px 0 12px">
      用户名是登录身份，创建后不可修改。时区决定「今日 / 历史某天」的日界，改动会影响后续统计归属日期。
    </p>
    <div class="wgrid c2">
      <div>
        <div class="wlabel">显示名</div>
        <input v-model="profileForm.displayName" class="winput" />
        <div v-if="fieldErrors.displayName" class="field-error">{{ fieldErrors.displayName }}</div>
      </div>
      <div>
        <div class="wlabel">时区</div>
        <select v-model="profileForm.timezone" class="winput">
          <option v-for="zone in TIMEZONES" :key="zone" :value="zone">{{ zone }}</option>
        </select>
      </div>
    </div>
    <div class="row mt12" style="gap: 9px">
      <button class="wbtn" :disabled="submitting" @click="saveProfile">
        {{ submitting ? '保存中…' : '保存' }}
      </button>
      <button class="wbtn ghost" @click="closePanel">取消</button>
    </div>
  </div>

  <div v-if="archiveTarget" class="modal-backdrop">
    <div class="modal danger">
      <h3>归档用户「{{ archiveTarget.username }}」</h3>
      <p class="sub">
        归档是可逆的软状态：账号立即无法登录，不参与催办扫描与统计，学习端也看不到它，但历史数据全部保留，可在归档区恢复。
      </p>
      <p class="notice warn" style="margin-bottom: 0">
        这里<b>不会</b>删除任何数据。<b>彻底删除请到归档区</b>，那里会展示完整级联清单（Todo、进度、附件文件、催办、督学绑定、会话），并要求输入用户名确认。
      </p>
      <div class="modal-foot">
        <button class="wbtn ghost" @click="archiveTarget = null">取消</button>
        <button class="wbtn red" :disabled="submitting" @click="archiveUser">
          {{ submitting ? '归档中…' : '确认归档' }}
        </button>
      </div>
    </div>
  </div>
</template>
