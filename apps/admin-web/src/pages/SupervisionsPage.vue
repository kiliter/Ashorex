<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { api, ApiError } from '@/api/client';

/**
 * 结构对应原型 8-7：
 * 顶部说明 + 四张指标卡 + 全宽绑定表 + 下方两列（新增绑定 / 权限编辑与快照说明）。
 * 新增绑定在原型里是常驻卡片，不是弹窗；只有解绑走二次确认弹框。
 */

/** 与后端 Supervision 记录对齐；archivedAt 非空即已解绑。 */
interface Supervision {
  id: string;
  learnerUserId: string;
  supervisorUserId: string;
  kind: string;
  canView: boolean;
  canNag: boolean;
  canEditGoal: boolean;
  canAddTodo: boolean;
  archivedAt: string | null;
}

/** 绑定行里的账号信息；只声明页面用到的字段，口令散列一律不读取、不渲染。 */
interface AccountBrief {
  id: string;
  username: string;
  displayName: string;
  status: string;
  roles: string[];
}

/** 后端 SupervisionService.Binding：绑定 + 学员 + 督学人。 */
interface Binding {
  supervision: Supervision;
  learner: AccountBrief;
  supervisor: AccountBrief;
}

/** 候选用户，来自 AccessAdminController.UserRow（只含在用账号）。 */
interface UserOption {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
  supervisor: boolean;
}

interface SupervisionsResponse {
  bindings: Binding[];
  users: UserOption[];
}

/** 四个权限位与展示文案的对应关系，新增与编辑共用。 */
const PERMISSIONS = [
  { key: 'canView', label: '查看学习情况', hint: '今日 Todo、进度、时长、历史与删除原因' },
  { key: 'canNag', label: '一键督学', hint: '立即向学员投递催办，走同一管道' },
  { key: 'canEditGoal', label: '代改考试目标', hint: '可代学员编辑倒计时目标' },
  { key: 'canAddTodo', label: '代加 Todo', hint: '督学人可直接给学员派任务' },
] as const;

type PermissionKey = (typeof PERMISSIONS)[number]['key'];

/** bind 与 restore 可能返回的业务错误码 → 后台可读文案。 */
const ERROR_MESSAGES: Record<string, string> = {
  SUPERVISION_PRIMARY_EXISTS: '该学员已有主督学人。请先解绑现有主督学人再重新绑定，或把本次绑定改为「协同督学」。',
  SUPERVISION_ALREADY_BOUND: '这两个账号之间已存在有效绑定，不能重复绑定。如需调整权限，请用表格右侧的「改权限」。',
  SUPERVISION_SELF_BIND: '不能把账号自己设为督学人，请另选一位督学人。',
  SUPERVISION_LEARNER_NOT_FOUND: '学员账号不存在或已被删除，请刷新页面后重试。',
  SUPERVISION_SUPERVISOR_NOT_FOUND: '督学人账号不存在或已被删除，请刷新页面后重试。',
  SUPERVISION_NOT_FOUND: '该督学关系已不存在，可能已被其他管理员解绑，请刷新页面。',
};

const bindings = ref<Binding[]>([]);
const users = ref<UserOption[]>([]);
const loading = ref(true);
const error = ref('');
const notice = ref('');
const submitting = ref(false);
/** 非空即打开解绑确认框。 */
const unbindTarget = ref<Binding | null>(null);
/** 非空即在右侧卡片显示权限编辑表单。 */
const editing = ref<Binding | null>(null);

const createForm = reactive({
  learnerUserId: '',
  supervisorUserId: '',
  kind: 'PRIMARY',
  canView: true,
  canNag: true,
  canEditGoal: false,
  canAddTodo: false,
});
const editForm = reactive({
  canView: false,
  canNag: false,
  canEditGoal: false,
  canAddTodo: false,
});
const fieldErrors = ref<Record<string, string>>({});

async function load(): Promise<void> {
  try {
    const data = await api.get<SupervisionsResponse>('/supervisions');
    bindings.value = data.bindings;
    users.value = data.users;
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

/** 表格按「有效绑定在前、已解绑在后」排序，避免历史行淹没当前关系。 */
const sortedBindings = computed(() =>
  [...bindings.value].sort((left, right) => {
    const leftActive = left.supervision.archivedAt === null ? 0 : 1;
    const rightActive = right.supervision.archivedAt === null ? 0 : 1;
    if (leftActive !== rightActive) return leftActive - rightActive;
    return left.learner.username.localeCompare(right.learner.username);
  }),
);

const activeBindings = computed(() =>
  bindings.value.filter((item) => item.supervision.archivedAt === null),
);

const supervisorCount = computed(
  () => new Set(activeBindings.value.map((item) => item.supervisor.id)).size,
);

const boundLearnerCount = computed(
  () => new Set(activeBindings.value.map((item) => item.learner.id)).size,
);

const collaboratorCount = computed(
  () => activeBindings.value.filter((item) => item.supervision.kind === 'COLLABORATOR').length,
);

/** 已有主督学人的学员集合；用于「未绑定学员」统计与 409 前置提示。 */
const learnersWithPrimary = computed(
  () =>
    new Set(
      activeBindings.value
        .filter((item) => item.supervision.kind === 'PRIMARY')
        .map((item) => item.learner.id),
    ),
);

/** 学习者候选：具备 LEARNER 角色的在用账号。 */
const learnerOptions = computed(() => users.value.filter((user) => user.roles.includes('LEARNER')));

const unboundLearnerCount = computed(
  () => learnerOptions.value.filter((user) => !learnersWithPrimary.value.has(user.id)).length,
);

const archivedCount = computed(
  () => bindings.value.length - activeBindings.value.length,
);

/** 选中学员已有主督学人时，把新增表单默认切到协同督学，避免必然触发 409。 */
const primaryTaken = computed(
  () => createForm.learnerUserId !== '' && learnersWithPrimary.value.has(createForm.learnerUserId),
);

function resetFeedback(): void {
  error.value = '';
  notice.value = '';
  fieldErrors.value = {};
}

/** 业务错误码优先映射为可操作文案，避免把 409 原始报错直接抛给管理员。 */
function handleFailure(cause: unknown, fallback: string): void {
  if (cause instanceof ApiError) {
    const code = cause.problem.errorCode;
    error.value = (code && ERROR_MESSAGES[code]) || cause.message;
    fieldErrors.value = cause.fieldErrors;
    return;
  }
  error.value = cause instanceof Error ? cause.message : fallback;
}

function accountName(account: AccountBrief): string {
  return account.displayName || account.username;
}

function permissionsOf(supervision: Supervision): string[] {
  const granted: string[] = [];
  for (const permission of PERMISSIONS) {
    if (supervision[permission.key]) {
      granted.push(permission.label);
    }
  }
  return granted;
}

async function bind(): Promise<void> {
  resetFeedback();
  if (!createForm.learnerUserId) {
    fieldErrors.value = { learnerUserId: '请选择学员' };
    return;
  }
  if (!createForm.supervisorUserId) {
    fieldErrors.value = { supervisorUserId: '请选择督学人' };
    return;
  }
  if (createForm.learnerUserId === createForm.supervisorUserId) {
    fieldErrors.value = { supervisorUserId: '不能把账号自己设为督学人' };
    return;
  }
  submitting.value = true;
  try {
    await api.post('/supervisions', {
      learnerUserId: createForm.learnerUserId,
      supervisorUserId: createForm.supervisorUserId,
      kind: createForm.kind,
      canView: createForm.canView,
      canNag: createForm.canNag,
      canEditGoal: createForm.canEditGoal,
      canAddTodo: createForm.canAddTodo,
    });
    notice.value = '已建立督学绑定，督学人在 App 内会出现「切换到督学端」入口';
    createForm.learnerUserId = '';
    createForm.supervisorUserId = '';
    createForm.kind = 'PRIMARY';
    await load();
  } catch (cause) {
    handleFailure(cause, '建立绑定失败');
  } finally {
    submitting.value = false;
  }
}

function openEdit(binding: Binding): void {
  resetFeedback();
  editing.value = binding;
  editForm.canView = binding.supervision.canView;
  editForm.canNag = binding.supervision.canNag;
  editForm.canEditGoal = binding.supervision.canEditGoal;
  editForm.canAddTodo = binding.supervision.canAddTodo;
}

async function savePermissions(): Promise<void> {
  if (!editing.value) return;
  resetFeedback();
  submitting.value = true;
  try {
    await api.post('/supervisions/permissions', {
      supervisionId: editing.value.supervision.id,
      canView: editForm.canView,
      canNag: editForm.canNag,
      canEditGoal: editForm.canEditGoal,
      canAddTodo: editForm.canAddTodo,
    });
    notice.value = `已更新 ${accountName(editing.value.learner)} 的督学权限`;
    editing.value = null;
    await load();
  } catch (cause) {
    handleFailure(cause, '更新权限失败');
  } finally {
    submitting.value = false;
  }
}

/** 解绑即归档绑定：督学人立即失去权限，历史事件的督学人快照保留。 */
async function unbind(): Promise<void> {
  if (!unbindTarget.value) return;
  const label = `${accountName(unbindTarget.value.learner)} ← ${accountName(unbindTarget.value.supervisor)}`;
  submitting.value = true;
  resetFeedback();
  try {
    await api.post('/supervisions/archive', {
      supervisionId: unbindTarget.value.supervision.id,
    });
    notice.value = `已解绑 ${label}`;
    unbindTarget.value = null;
    await load();
  } catch (cause) {
    handleFailure(cause, '解绑失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="page-head">
    <h1>督学关系</h1>
    <p class="lead">
      账号可同时具备学习者与督学人身份。绑定后督学人在 App 内出现「切换到督学端」入口，可查看学员学习情况并一键督学。一个学员同时只能有一个主督学人，可另设协同督学。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <div class="wgrid c4">
    <div class="wcard">
      <div class="wlabel">督学人</div>
      <div class="mono" style="font-size: 22px; font-weight: 800">{{ supervisorCount }}</div>
      <div class="muted mt8" style="font-size: 11.5px">共绑定 {{ boundLearnerCount }} 名学员</div>
    </div>
    <div class="wcard">
      <div class="wlabel">未绑定学员</div>
      <div class="mono" style="font-size: 22px; font-weight: 800">{{ unboundLearnerCount }}</div>
      <div v-if="unboundLearnerCount === 0" class="dotstate on mt8"><i></i>全部已覆盖</div>
      <div v-else class="dotstate off mt8"><i></i>缺主督学人</div>
    </div>
    <div class="wcard">
      <div class="wlabel">协同督学</div>
      <div class="mono" style="font-size: 22px; font-weight: 800">{{ collaboratorCount }}</div>
      <div class="muted mt8" style="font-size: 11.5px">
        有效绑定共 {{ activeBindings.length }} 条
      </div>
    </div>
    <div class="wcard">
      <div class="wlabel">已解绑</div>
      <div class="mono" style="font-size: 22px; font-weight: 800">{{ archivedCount }}</div>
      <div class="muted mt8" style="font-size: 11.5px">历史绑定保留，不再授予任何权限</div>
    </div>
  </div>

  <div class="wcard flush mt16">
    <div class="wcard-head" style="padding: 14px 18px 0; margin-bottom: 0">
      <b style="font-size: 14px">绑定关系</b>
      <span class="muted" style="font-size: 11.5px">权限变更立即生效，解绑不影响历史督学人快照</span>
    </div>
    <div class="table-wrap mt10">
      <table class="wt">
        <thead>
          <tr>
            <th>学员</th>
            <th>督学人</th>
            <th>类型</th>
            <th>督学权限</th>
            <th>状态</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in sortedBindings" :key="item.supervision.id">
            <td>
              <b>{{ accountName(item.learner) }}</b>
              <div class="muted" style="font-size: 11px">{{ item.learner.username }}</div>
            </td>
            <td>
              <b>{{ accountName(item.supervisor) }}</b>
              <div class="muted" style="font-size: 11px">{{ item.supervisor.username }}</div>
            </td>
            <td>
              <span
                class="badge"
                :class="item.supervision.kind === 'PRIMARY' ? 'b-course' : 'b-ink'"
              >
                {{ item.supervision.kind === 'PRIMARY' ? '主督学人' : '协同督学' }}
              </span>
            </td>
            <td>
              <span
                v-for="label in permissionsOf(item.supervision)"
                :key="label"
                class="badge b-ink"
                style="margin-right: 4px"
              >
                {{ label }}
              </span>
              <span v-if="permissionsOf(item.supervision).length === 0" class="muted">无授权</span>
            </td>
            <td>
              <span v-if="item.supervision.archivedAt === null" class="dotstate on"><i></i>有效</span>
              <span v-else class="dotstate off"><i></i>已解绑</span>
            </td>
            <td style="text-align: right; white-space: nowrap">
              <template v-if="item.supervision.archivedAt === null">
                <button class="wbtn ghost sm" @click="openEdit(item)">改权限</button>
                <button
                  class="wbtn ghost sm"
                  style="margin-left: 6px"
                  @click="unbindTarget = item"
                >
                  解绑
                </button>
              </template>
              <span v-else class="muted" style="font-size: 11px">已解绑，不可再操作</span>
            </td>
          </tr>
          <tr v-if="loading">
            <td colspan="6" class="loading">加载中…</td>
          </tr>
          <tr v-else-if="sortedBindings.length === 0">
            <td colspan="6" class="empty">还没有任何督学绑定</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="wgrid c2 mt16">
    <div class="wcard">
      <b style="font-size: 14px">新增绑定</b>
      <div class="wgrid c3 mt12">
        <div>
          <div class="wlabel">学员</div>
          <select v-model="createForm.learnerUserId" class="winput">
            <option value="">选择用户</option>
            <option v-for="user in learnerOptions" :key="user.id" :value="user.id">
              {{ user.displayName || user.username }}（{{ user.username }}）
            </option>
          </select>
          <div v-if="fieldErrors.learnerUserId" class="field-error">
            {{ fieldErrors.learnerUserId }}
          </div>
        </div>
        <div>
          <div class="wlabel">督学人</div>
          <select v-model="createForm.supervisorUserId" class="winput">
            <option value="">选择用户</option>
            <option
              v-for="user in users"
              :key="user.id"
              :value="user.id"
              :disabled="user.id === createForm.learnerUserId"
            >
              {{ user.displayName || user.username }}（{{ user.username }}）
            </option>
          </select>
          <div v-if="fieldErrors.supervisorUserId" class="field-error">
            {{ fieldErrors.supervisorUserId }}
          </div>
        </div>
        <div>
          <div class="wlabel">绑定类型</div>
          <select v-model="createForm.kind" class="winput">
            <option value="PRIMARY">主督学人</option>
            <option value="COLLABORATOR">协同督学</option>
          </select>
        </div>
      </div>

      <p v-if="primaryTaken && createForm.kind === 'PRIMARY'" class="notice warn mt12">
        该学员已有主督学人。直接提交会被服务端拒绝（409）；请先解绑现有主督学人，或把类型改为「协同督学」。
      </p>

      <div class="wlabel" style="margin-top: 12px">授予权限</div>
      <table class="wt">
        <tbody>
          <tr v-for="permission in PERMISSIONS" :key="permission.key">
            <td style="width: 34px">
              <input
                v-model="createForm[permission.key as PermissionKey]"
                type="checkbox"
                :aria-label="permission.label"
                style="width: 16px; height: 16px"
              />
            </td>
            <td>
              <b>{{ permission.label }}</b>
              <div class="muted" style="font-size: 11px">{{ permission.hint }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="row mt12" style="gap: 9px">
        <button class="wbtn" :disabled="submitting" @click="bind">
          {{ submitting ? '保存中…' : '保存绑定' }}
        </button>
        <button
          class="wbtn ghost"
          @click="
            createForm.learnerUserId = '';
            createForm.supervisorUserId = '';
            resetFeedback();
          "
        >
          取消
        </button>
      </div>
    </div>

    <div v-if="editing" class="wcard">
      <b style="font-size: 14px">
        编辑督学权限 · {{ accountName(editing.learner) }} ← {{ accountName(editing.supervisor) }}
      </b>
      <p class="lead" style="margin: 6px 0 12px">
        权限保存后立即生效。绑定类型与绑定对象不可在此修改；需要换人请先解绑再新增绑定。
      </p>
      <table class="wt">
        <tbody>
          <tr v-for="permission in PERMISSIONS" :key="permission.key">
            <td style="width: 34px">
              <input
                v-model="editForm[permission.key as PermissionKey]"
                type="checkbox"
                :aria-label="permission.label"
                style="width: 16px; height: 16px"
              />
            </td>
            <td>
              <b>{{ permission.label }}</b>
              <div class="muted" style="font-size: 11px">{{ permission.hint }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="row mt12" style="gap: 9px">
        <button class="wbtn" :disabled="submitting" @click="savePermissions">
          {{ submitting ? '保存中…' : '保存权限' }}
        </button>
        <button class="wbtn ghost" @click="editing = null">取消</button>
      </div>
    </div>

    <div v-else class="wcard">
      <b style="font-size: 14px">事件督学人快照</b>
      <p class="lead" style="margin: 6px 0 12px">
        每条学习事件写入时都会记录当时的主督学人，改绑与解绑不会篡改历史归属。
      </p>
      <p class="notice info" style="margin-bottom: 0">
        在表格里选择一条绑定的「改权限」，这里会切换为权限编辑表单。
      </p>
      <div class="muted mt10" style="font-size: 11.5px">
        字段：<code>supervisor_user_id_snapshot</code>，写入即固化，不随后续改绑变化。
      </div>
    </div>
  </div>

  <div v-if="unbindTarget" class="modal-backdrop">
    <div class="modal danger">
      <h3>
        解绑「{{ accountName(unbindTarget.learner) }} ←
        {{ accountName(unbindTarget.supervisor) }}」
      </h3>
      <p class="sub">
        解绑后该督学人立即失去这名学员的全部权限，App 内不再看到该学员；若其名下已无其他学员，督学身份会一并收回。
      </p>
      <p class="notice warn" style="margin-bottom: 0">
        历史学习事件里的督学人快照<b>不会</b>被修改，历史归属保持原样。解绑后如需恢复关系，请重新新增绑定。
      </p>
      <div class="modal-foot">
        <button class="wbtn ghost" @click="unbindTarget = null">取消</button>
        <button class="wbtn red" :disabled="submitting" @click="unbind">
          {{ submitting ? '解绑中…' : '确认解绑' }}
        </button>
      </div>
    </div>
  </div>
</template>
