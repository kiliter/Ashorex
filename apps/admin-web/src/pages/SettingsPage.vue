<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { api, ApiError } from '@/api/client';

/** 分区切换只改变可见性，所有配置草稿继续参与统一保存。 */
const section = ref('emby');

/**
 * 按 ADR-0046 使用固定分区导航与统一保存栏，
 * 系统 Bark 与功能开关分别使用独立卡片，个人催办目的地由用户在 App 维护。
 *
 * 安全边界：后端 `/settings` 只回传 `apiKeyConfigured` / `sendKeyConfigured` 布尔值，
 * 页面不接收已保存的明文密钥；密钥输入框留空即表示「保持原值」。
 */
interface EmbyView {
  baseUrl: string;
  userId: string;
  timeoutSeconds: number;
  apiKeyConfigured: boolean;
}

interface ServerChanView {
  sendKeyConfigured: boolean;
  timeoutSeconds: number;
  nagEnabled: boolean;
  dailyDigestEnabled: boolean;
}

interface FeaturesView {
  documentResources: boolean;
  maxDocumentSizeMb: number;
}

interface SettingsResponse {
  bark: { baseUrl: string; deviceKeyConfigured: boolean; enabled: boolean; timeoutSeconds: number };
  emby: EmbyView;
  serverChan: ServerChanView;
  features: FeaturesView;
  /** 后端只给出「未配置 / 可用 / 不可用」三种稳定中文状态，不含主机与密钥。 */
  embyStatus: string;
}

/** `POST /settings/test-emby` 的结果；文案已在服务端脱敏，可直接展示。 */
interface TestEmbyResult {
  ok: boolean;
  status: string;
  message: string;
  latencyMs: number;
}

// 系统异常专用目的地，密钥不回显。
const barkBaseUrl = ref('https://api.day.app');
const barkDeviceKey = ref('');
const barkEnabled = ref(false);
const barkTimeoutSeconds = ref(8);
const barkConfigured = ref(false);
const testingBark = ref(false);
const barkTestResult = ref<{ ok: boolean; status: string; message: string } | null>(null);
/** 只测试已保存的系统配置，修改草稿后先保存，避免发往旧设备。 */
const barkSaved = ref('');
const barkDraft = computed(() => JSON.stringify([barkBaseUrl.value.trim(), barkEnabled.value, barkTimeoutSeconds.value]));
const barkDirty = computed(() => barkDeviceKey.value.trim() !== '' || barkDraft.value !== barkSaved.value);

/** 点击明确发送一条测试通知，加载态防止重复点击，不改动异常开关。 */
async function testBark(): Promise<void> {
  if (testingBark.value || saving.value || barkDirty.value) return;
  testingBark.value = true;
  barkTestResult.value = null;
  try {
    barkTestResult.value = await api.post('/settings/test-bark', {});
  } catch (cause) {
    barkTestResult.value = { ok: false, status: '发送失败', message: cause instanceof Error ? cause.message : '测试通知发送失败，请稍后重试' };
  } finally {
    testingBark.value = false;
  }
}
const loaded = ref(false);
const error = ref('');
const notice = ref('');
const saving = ref(false);
const probing = ref(false);
/** 最近一次「测试连接」的结论；null 表示本次会话还没测过。 */
const probeResult = ref<TestEmbyResult | null>(null);
const embyStatus = ref('');
const apiKeyConfigured = ref(false);
const sendKeyConfigured = ref(false);
/** 字段名 → 中文错误文案，key 与后端校验器一致：embyBaseUrl / embyTimeoutSeconds / serverChanTimeoutSeconds / featureMaxDocumentSizeMb。 */
const fieldErrors = ref<Record<string, string>>({});

const embyBaseUrl = ref('');
const embyUserId = ref('');
const embyTimeoutSeconds = ref(10);
/** 只做「新密钥」输入用，加载时永远保持为空。 */
const embyApiKey = ref('');
const serverChanSendKey = ref('');
const serverChanTimeoutSeconds = ref(8);
const serverChanNagEnabled = ref(true);
const serverChanDailyDigestEnabled = ref(false);
const documentResources = ref(false);
const maxDocumentSizeMb = ref(200);

function applySnapshot(data: SettingsResponse): void {
  barkBaseUrl.value = data.bark.baseUrl;
  barkEnabled.value = data.bark.enabled;
  barkTimeoutSeconds.value = data.bark.timeoutSeconds;
  barkConfigured.value = data.bark.deviceKeyConfigured;
  barkDeviceKey.value = '';
  barkSaved.value = barkDraft.value;
  barkTestResult.value = null;
  embyBaseUrl.value = data.emby.baseUrl;
  embyUserId.value = data.emby.userId;
  embyTimeoutSeconds.value = data.emby.timeoutSeconds;
  apiKeyConfigured.value = data.emby.apiKeyConfigured;
  sendKeyConfigured.value = data.serverChan.sendKeyConfigured;
  serverChanTimeoutSeconds.value = data.serverChan.timeoutSeconds;
  serverChanNagEnabled.value = data.serverChan.nagEnabled;
  serverChanDailyDigestEnabled.value = data.serverChan.dailyDigestEnabled;
  documentResources.value = data.features.documentResources;
  maxDocumentSizeMb.value = data.features.maxDocumentSizeMb;
  embyStatus.value = data.embyStatus;
  // 配置已变更，上一次的探测结论不再可信，直接清空。
  probeResult.value = null;
  // 密钥输入框不做回显，保存成功后同样清空，避免明文停留在 DOM 里。
  embyApiKey.value = '';
  serverChanSendKey.value = '';
}

async function load(): Promise<void> {
  try {
    applySnapshot(await api.get<SettingsResponse>('/settings'));
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载运行配置失败';
  } finally {
    loaded.value = true;
  }
}

onMounted(load);

/** 保存提交整份运行配置（全部卡片一起），因为后端只有一个 `POST /settings` 端点。 */
async function save(): Promise<void> {
  saving.value = true;
  notice.value = '';
  error.value = '';
  fieldErrors.value = {};
  try {
    await api.post('/settings', {
      barkBaseUrl: barkBaseUrl.value.trim(), barkDeviceKey: barkDeviceKey.value.trim(),
      barkEnabled: barkEnabled.value, barkTimeoutSeconds: barkTimeoutSeconds.value,
      embyBaseUrl: embyBaseUrl.value.trim(),
      embyApiKey: embyApiKey.value.trim(),
      embyUserId: embyUserId.value.trim(),
      embyTimeoutSeconds: embyTimeoutSeconds.value,
      serverChanSendKey: serverChanSendKey.value.trim(),
      serverChanTimeoutSeconds: serverChanTimeoutSeconds.value,
      serverChanNagEnabled: serverChanNagEnabled.value,
      serverChanDailyDigestEnabled: serverChanDailyDigestEnabled.value,
      documentResources: documentResources.value,
      maxDocumentSizeMb: maxDocumentSizeMb.value,
    });
    await load();
    notice.value = '运行配置已保存，Emby、Server 酱与系统 Bark 配置立即生效';
  } catch (cause) {
    if (cause instanceof ApiError) {
      fieldErrors.value = cause.fieldErrors;
      // 整体保存可能发现隐藏分区的错误，自动切到首个错误，避免用户找不到字段。
      const firstField = Object.keys(cause.fieldErrors)[0] ?? '';
      if (firstField.startsWith('emby')) section.value = 'emby';
      else if (firstField.startsWith('serverChan')) section.value = 'serverchan';
      else if (firstField.startsWith('bark')) section.value = 'bark';
      else if (firstField.startsWith('feature') || firstField === 'maxDocumentSizeMb') section.value = 'features';
      error.value = cause.message;
    } else {
      error.value = cause instanceof Error ? cause.message : '保存失败';
    }
  } finally {
    saving.value = false;
  }
}

/**
 * 「测试连接」对已保存的配置做一次真实探测（`POST /settings/test-emby`）。
 *
 * 注意探测的是**服务端已保存的配置**，不是输入框里的草稿；未配置或探测失败都会返回 200 与可展示文案，
 * 因此这里只在网络层异常时才需要兜底。
 */
async function probeEmby(): Promise<void> {
  probing.value = true;
  probeResult.value = null;
  try {
    const result = await api.post<TestEmbyResult>('/settings/test-emby', {});
    probeResult.value = result;
    embyStatus.value = result.status;
  } catch (cause) {
    probeResult.value = {
      ok: false,
      status: '不可用',
      message: cause instanceof Error ? cause.message : '测试连接请求失败',
      latencyMs: 0,
    };
    embyStatus.value = '不可用';
  } finally {
    probing.value = false;
  }
}

/** 探测结论对应的 notice 配色：成功绿、未配置黄、失败红。 */
const probeNoticeClass = computed(() => {
  if (!probeResult.value) return 'info';
  if (probeResult.value.ok) return 'success';
  return probeResult.value.status === '未配置' ? 'warn' : 'danger';
});

/** Emby 状态映射到 dotstate：可用绿点、未配置灰点、不可用红点。 */
const embyDotClass = computed(() => {
  if (embyStatus.value === '可用') return 'on';
  if (embyStatus.value === '不可用') return 'off';
  return 'idle';
});
/** 切换分区回到内容顶部，保留表单草稿但不继承上一分区滚动位置。 */
const scrollArea = ref<HTMLElement | null>(null);
watch(section, () => scrollArea.value?.scrollTo({ top: 0 }), { flush: 'post' });
</script>

<template>
  <!-- 标题和操作固定，长内容在工作区内滚动。 -->
  <section class="workspace-page">
  <div class="page-head">
    <h1>运行配置</h1>
    <p class="lead">
      管理 Emby、Server 酱、系统 Bark 异常通知及材料资源开关。密钥只在服务端保存与使用，<b>不下发给 App</b>，日志与错误响应中一律脱敏；页面只能看到「是否已配置」。
    </p>
  </div>

  <p v-if="notice" class="notice success">{{ notice }}</p>
  <p v-if="error" class="notice danger">{{ error }}</p>

  <p v-if="!loaded" class="loading">正在加载运行配置…</p>

  <nav class="workspace-tabs" aria-label="页面分区">
    <button
      type="button"
      :class="{ on: section === 'emby' }"
      :aria-pressed="section === 'emby'"
      @click="section = 'emby'"
    >Emby</button>
    <button
      type="button"
      :class="{ on: section === 'serverchan' }"
      :aria-pressed="section === 'serverchan'"
      @click="section = 'serverchan'"
    >Server 酱</button>
    <button
      type="button"
      :class="{ on: section === 'bark' }"
      :aria-pressed="section === 'bark'"
      @click="section = 'bark'"
    >系统 Bark</button>
    <button
      type="button"
      :class="{ on: section === 'features' }"
      :aria-pressed="section === 'features'"
      @click="section = 'features'"
    >功能开关</button>
  </nav>
  <div v-if="loaded" ref="scrollArea" class="workspace-scroll">
    <div>
      <div v-show="section === 'emby'" class="wcard">
        <b style="font-size: 14px">运行配置 · Emby</b>
        <div class="wgrid c2 mt12">
          <div>
            <div class="wlabel">Base URL</div>
            <input
              v-model="embyBaseUrl"
              class="winput mono"
              style="font-size: 12px"
              placeholder="http://host.docker.internal:8096"
            />
            <div v-if="fieldErrors.embyBaseUrl" class="field-error">
              {{ fieldErrors.embyBaseUrl }}
            </div>
          </div>
          <div>
            <div class="wlabel">API Key</div>
            <input
              v-model="embyApiKey"
              class="winput mono"
              type="password"
              autocomplete="new-password"
              style="font-size: 12px"
              :placeholder="apiKeyConfigured ? '已配置 · 留空保持原值' : '未配置 · 填入后生效'"
            />
            <div class="muted mt6" style="font-size: 11px">
              <span class="dotstate" :class="apiKeyConfigured ? 'on' : 'idle'">
                <i></i>{{ apiKeyConfigured ? '已配置' : '未配置' }}
              </span>
              · 留空表示保持原值
            </div>
          </div>
          <div>
            <div class="wlabel">User ID</div>
            <input
              v-model="embyUserId"
              class="winput mono"
              style="font-size: 12px"
              placeholder="Emby 用户 ID"
            />
          </div>
          <div>
            <div class="wlabel">连接超时（秒）</div>
            <div class="winput-row">
              <input
                v-model.number="embyTimeoutSeconds"
                class="winput mono"
                type="number"
                min="1"
                max="120"
              />
              <span class="unit">1–120</span>
            </div>
            <div v-if="fieldErrors.embyTimeoutSeconds" class="field-error">
              {{ fieldErrors.embyTimeoutSeconds }}
            </div>
          </div>
        </div>
        <div class="wbtn-row mt12">
          <button class="wbtn ghost sm" :disabled="probing" @click="probeEmby">
            {{ probing ? '探测中…' : '测试连接' }}
          </button>
          <span class="dotstate" :class="embyDotClass" style="margin-left: 6px">
            <i></i>{{ embyStatus || '未知' }}
          </span>
        </div>
        <p v-if="probeResult" class="notice mt10" :class="probeNoticeClass" style="margin-bottom: 0">
          {{ probeResult.message }}
          <span v-if="probeResult.latencyMs > 0" class="mono muted" style="margin-left: 4px">
            耗时 {{ probeResult.latencyMs }} ms
          </span>
        </p>
        <div class="muted mt10" style="font-size: 11.5px">
          「测试连接」探测的是<b>已保存</b>的配置；刚改过输入框请先保存再测。
        </div>
        <div class="muted mt10" style="font-size: 11.5px">
          Emby 是唯一媒体源与元数据来源。目标主机由本项配置固定，代理会拒绝其他主机与路径穿越；原始 <code>Path</code> 只在内存中用于生成来源指纹，不落库、不上页面。
        </div>
      </div>

      <div v-show="section === 'serverchan'" class="wcard">
        <b style="font-size: 14px">运行配置 · Server 酱</b>
        <div class="wgrid c2 mt12">
          <div>
            <div class="wlabel">SendKey</div>
            <input
              v-model="serverChanSendKey"
              class="winput mono"
              type="password"
              autocomplete="new-password"
              style="font-size: 12px"
              :placeholder="sendKeyConfigured ? '已配置 · 留空保持原值' : '未配置 · 填入后生效'"
            />
            <div class="muted mt6" style="font-size: 11px">
              <span class="dotstate" :class="sendKeyConfigured ? 'on' : 'idle'">
                <i></i>{{ sendKeyConfigured ? '已配置' : '未配置' }}
              </span>
              · 留空表示保持原值
            </div>
          </div>
          <div>
            <div class="wlabel">超时（秒）</div>
            <div class="winput-row">
              <input
                v-model.number="serverChanTimeoutSeconds"
                class="winput mono"
                type="number"
                min="1"
                max="60"
              />
              <span class="unit">1–60</span>
            </div>
            <div v-if="fieldErrors.serverChanTimeoutSeconds" class="field-error">
              {{ fieldErrors.serverChanTimeoutSeconds }}
            </div>
          </div>
        </div>

        <div class="wlabel" style="margin-top: 14px">用途</div>
        <table class="wt">
          <tbody>
            <tr>
              <td style="width: 42px">
                <label class="wcheck">
                  <input v-model="serverChanNagEnabled" type="checkbox" />
                </label>
              </td>
              <td>
                <b>催办推送</b>
                <div class="muted" style="font-size: 11px">
                  离线或全屏超时未回应时的降级渠道
                </div>
              </td>
            </tr>
            <tr>
              <td>
                <label class="wcheck">
                  <input v-model="serverChanDailyDigestEnabled" type="checkbox" />
                </label>
              </td>
              <td>
                <b>每日汇总</b>
                <div class="muted" style="font-size: 11px">V2 暂不启用</div>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="muted mt10" style="font-size: 11.5px">
          密钥只在服务端保存与使用，不下发给 App，日志中脱敏。当前没有「发送测试」端点，可在「在线与进度」页用手动催办验证投递链路。
        </div>
      </div>
    </div>

    <!-- 系统目的地独立成卡；沿用后台表单组件，避免原生控件挤成一行。 -->
    <section v-show="section === 'bark'" class="wcard bark-settings" aria-labelledby="system-bark-title">
      <div class="wcard-head">
        <h2 id="system-bark-title">系统 Bark 异常通知</h2>
        <span class="badge b-ink">系统专用</span>
      </div>
      <p class="muted bark-description">接收 Emby 同步、备份和完整性检查异常。个人催办使用用户自己的 Bark 配置。</p>
      <div class="bark-switch-row">
        <label class="wcheck" for="system-bark-enabled">
          <input id="system-bark-enabled" v-model="barkEnabled" type="checkbox" :disabled="saving" />
          <b>启用系统异常通知</b>
        </label>
        <span class="dotstate" :class="barkEnabled ? 'on' : 'idle'"><i></i>{{ barkEnabled ? '已启用' : '未启用' }}</span>
      </div>
      <div class="wgrid c2 bark-fields mt16">
        <div>
          <label class="wlabel" for="system-bark-url">服务地址</label>
          <input id="system-bark-url" v-model="barkBaseUrl" class="winput mono" type="url" :disabled="saving" placeholder="https://api.day.app" :aria-invalid="!!fieldErrors.barkBaseUrl" aria-describedby="system-bark-url-help" />
          <div id="system-bark-url-help" class="muted mt6">默认使用 Bark 官方服务，也可填写系统通知专用的自建服务地址。</div>
          <div v-if="fieldErrors.barkBaseUrl" class="field-error">{{ fieldErrors.barkBaseUrl }}</div>
        </div>
        <div>
          <label class="wlabel" for="system-bark-key">设备 Key</label>
          <input id="system-bark-key" v-model="barkDeviceKey" class="winput mono" type="password" :disabled="saving" :placeholder="barkConfigured ? '已配置 · 留空保持原值' : '填写系统通知设备 Key'" autocomplete="new-password" :aria-invalid="!!fieldErrors.barkDeviceKey" />
          <div class="muted mt6"><span class="dotstate" :class="barkConfigured ? 'on' : 'idle'"><i></i>{{ barkConfigured ? '已配置' : '未配置' }}</span> · 密钥不回显，留空保持原值</div>
          <div v-if="fieldErrors.barkDeviceKey" class="field-error">{{ fieldErrors.barkDeviceKey }}</div>
        </div>
        <div>
          <label class="wlabel" for="system-bark-timeout">请求超时（秒）</label>
          <div class="winput-row">
            <input id="system-bark-timeout" v-model.number="barkTimeoutSeconds" class="winput mono" type="number" min="1" max="60" :disabled="saving" :aria-invalid="!!fieldErrors.barkTimeoutSeconds" />
            <span class="unit">1–60</span>
          </div>
          <div v-if="fieldErrors.barkTimeoutSeconds" class="field-error">{{ fieldErrors.barkTimeoutSeconds }}</div>
        </div>
        <div>
          <div class="wlabel">默认通知方式</div>
          <div class="bark-modes"><span class="badge b-ink">上岸分组</span><span class="badge b-ink">重要通知</span><span class="badge b-ink">点击打开 App</span></div>
        </div>
      </div>
      <div class="wbtn-row mt16">
        <button type="button" class="wbtn ghost sm" :disabled="saving || testingBark || barkDirty" @click="testBark">{{ testingBark ? '发送中…' : '发送测试通知' }}</button>
        <span class="muted">保存本页全部配置后生效</span>
      </div>
      <p class="muted mt10 bark-test-help">{{ barkDirty ? '系统 Bark 配置已修改，请先保存再发送测试通知。' : '测试使用已保存的系统配置；异常通知开关关闭时也可手动测试。' }}</p>
      <p v-if="barkTestResult && !barkDirty" class="notice mt10" :class="barkTestResult.ok ? 'success' : 'danger'" role="status">{{ barkTestResult.message }}</p>
    </section>

    <div v-show="section === 'features'" class="wcard">
      <div class="wcard-head">
        <h2>功能开关</h2>
        <span class="badge b-ink">ADR-0030</span>
      </div>
      <div class="wgrid c2">
        <div>
          <div class="wlabel">材料（PDF）资源</div>
          <label class="wcheck">
            <input v-model="documentResources" type="checkbox" />
            启用 DOCUMENT 类型学习资源
          </label>
          <div class="muted mt6" style="font-size: 11.5px">
            预留能力，V2 <b>不启用</b>阅读页与书籍库同步；只保留数据模型与本开关，默认关闭。
          </div>
        </div>
        <div>
          <div class="wlabel">材料单文件上限（MB）</div>
          <div class="winput-row">
            <input
              v-model.number="maxDocumentSizeMb"
              class="winput mono"
              type="number"
              min="1"
              max="2048"
            />
            <span class="unit">1–2048</span>
          </div>
          <div v-if="fieldErrors.featureMaxDocumentSizeMb" class="field-error">
            {{ fieldErrors.featureMaxDocumentSizeMb }}
          </div>
          <div class="muted mt6" style="font-size: 11.5px">
            材料开关关闭时该上限不生效，仅作为后续接入的预设值保存。
          </div>
        </div>
      </div>
      <div class="muted mt10" style="font-size: 11.5px">
        服务端只有一个保存端点，底部「保存运行配置」会提交全部分区的配置。
      </div>
    </div>
  </div>
  <footer v-if="loaded" class="workspace-actions">
    <span class="muted">切换分区保留输入，保存全部配置</span>
    <button type="button" class="wbtn" :disabled="saving || testingBark" @click="save">{{ saving ? '保存中…' : '保存运行配置' }}</button>
  </footer>
  </section>
</template>

<style scoped>
/* 仅约束系统 Bark 卡片，不影响其他任务正在调整的全局后台样式。 */
.bark-description { margin: 0 0 16px; line-height: 1.6; }
.bark-switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 14px; border: 1px solid var(--rule); border-radius: var(--r-field); background: var(--bg); }
.bark-fields > div { min-width: 0; }
.bark-fields .wlabel { display: block; }
.bark-fields .muted, .bark-settings .wbtn-row .muted { font-size: 11.5px; line-height: 1.6; }
.bark-modes { display: flex; flex-wrap: wrap; gap: 8px; padding-top: 4px; }
@media (max-width: 760px) { .bark-fields.wgrid.c2 { grid-template-columns: minmax(0, 1fr); } }
</style>
