<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { api } from '@/api/client';

/** 原型 8-12：仅管理服务端升级，不提供镜像地址或命令输入。 */
interface UpgradeStatus {
  enabled: boolean; available: boolean; busy: boolean; maintenance: boolean;
  currentVersion: string; previousVersion?: string; latestVersion?: string; phase?: string; message?: string; notes?: string; updatedAt?: string;
  config: { automatic: boolean; time: string; timezone: string };
}
const status = ref<UpgradeStatus>();
const draft = ref({ automatic: false, time: '03:00', timezone: 'Asia/Shanghai' });
const error = ref('');
const notice = ref('');
const working = ref(false);
const confirming = ref(false);
const disabled = computed(() => working.value || !status.value?.available || status.value?.busy);
let timer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;

/** 顺序轮询，维护/重启时保留最近结果，恢复后继续读取，不重复提交任务。 */
async function refresh(initial = false): Promise<void> {
  try {
    status.value = await api.get<UpgradeStatus>('/upgrades');
    if (initial) draft.value = { ...status.value.config };
    error.value = '';
  } catch {
    error.value = '暂时无法连接服务。升级期间会短暂中断，恢复后自动刷新。';
  }
  if (!disposed) timer = setTimeout(() => void refresh(), 5000);
}

/** 升级必须经过明确确认；预下载与检查不会停止业务。 */
async function action(action: 'CHECK' | 'DOWNLOAD' | 'APPLY'): Promise<void> {
  working.value = true;
  confirming.value = false;
  try {
    await api.post('/upgrades/actions', { action });
    notice.value = '任务已提交，请等待升级器执行。';
    if (status.value) status.value.busy = true;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败'; }
  finally { working.value = false; }
}

/** 保存草稿不随轮询覆盖，避免管理员正在编辑的时间跳回旧值。 */
async function save(): Promise<void> {
  working.value = true;
  try {
    status.value = await api.post<UpgradeStatus>('/upgrades/config', draft.value);
    notice.value = '自动升级设置已保存。';
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败'; }
  finally { working.value = false; }
}
onMounted(() => void refresh(true));
onUnmounted(() => { disposed = true; if (timer) clearTimeout(timer); });
</script>

<template>
  <div class="upgrade-page">
    <header><h1>版本与升级</h1><p>正式版本发布后，可手动升级或在指定时间自动安装。</p></header>
    <p v-if="error" role="alert" class="warning">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <section class="upgrade-card" aria-label="服务端版本">
      <div class="version-grid">
        <div><span>当前版本</span><strong>{{ status?.currentVersion ?? '读取中' }}</strong></div>
        <div><span>最新正式版本</span><strong>{{ status?.latestVersion ?? '尚未检查' }}</strong></div>
      </div>
      <p v-if="status?.previousVersion">上次版本：{{ status.previousVersion }}</p>
      <p v-if="status && !status.enabled" class="warning">当前部署尚未启用升级器，请先按部署手册接入。</p>
      <p v-else-if="status && !status.available" class="warning">升级器暂未就绪或正在执行较长任务，请稍后查看。</p>
      <div class="actions">
        <button :disabled="disabled" @click="action('CHECK')">检查更新</button>
        <button :disabled="disabled" @click="action('DOWNLOAD')">预下载</button>
        <button class="primary" :disabled="disabled" @click="confirming = true">立即升级</button>
      </div>
      <h2>更新说明</h2><p class="notes">{{ status?.notes || '检查更新后显示正式版本说明。' }}</p>
    </section>
    <section class="upgrade-card">
      <h2>自动升级</h2>
      <form @submit.prevent="save">
        <label class="toggle"><input v-model="draft.automatic" type="checkbox">启用自动升级</label>
        <div class="settings-grid">
          <label>每日执行时间<input v-model="draft.time" type="time" required></label>
          <label>时区<input v-model="draft.timezone" required placeholder="Asia/Shanghai"></label>
        </div>
        <p>只安装发布者明确允许自动升级的版本。错过执行时间后，当天首次运行会补查一次；失败版本不自动重复尝试。</p>
        <button :disabled="disabled" type="submit">保存设置</button>
      </form>
    </section>
    <section class="upgrade-card" aria-live="polite">
      <h2>最近执行结果</h2>
      <p>{{ status?.message || '尚无升级记录' }}</p>
      <p v-if="status?.updatedAt">更新于 {{ new Date(status.updatedAt).toLocaleString() }}</p>
      <p>升级前会暂停服务并备份数据，验收失败自动恢复。业务恢复后如需回退，请按恢复手册评估新增数据，不直接恢复旧快照。</p>
    </section>
    <div v-if="confirming" class="confirm-backdrop">
      <section role="dialog" aria-modal="true" aria-labelledby="upgrade-confirm-title" class="upgrade-card confirm-box">
        <h2 id="upgrade-confirm-title">确认升级服务端？</h2>
        <p>升级期间学习服务会短暂中断。系统会先备份数据库和附件，新版本验收失败时恢复旧服务。</p>
        <div class="actions"><button @click="confirming = false">取消</button><button class="primary" @click="action('APPLY')">确认升级</button></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.upgrade-page{max-width:960px;margin:auto}.upgrade-page h1{font-size:26px;margin-bottom:8px}.upgrade-page h2{font-size:17px}.upgrade-page p{line-height:1.7;color:#65665f}.upgrade-card{border:1px solid #dddcd4;background:#fff;border-radius:14px;padding:24px;margin:18px 0}.version-grid,.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:24px}.version-grid span{display:block;color:#65665f}.version-grid strong{display:block;font-size:25px;margin-top:8px}.actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:20px}button,input{font:inherit;min-height:44px;border:1px solid #cccac2;border-radius:8px;padding:8px 14px}button{background:#fff;cursor:pointer}button.primary{background:#2e6057;color:white;border-color:#2e6057}button:disabled{opacity:.5;cursor:default}.settings-grid label{display:flex;flex-direction:column;gap:8px}.toggle{display:flex;align-items:center;gap:10px;margin-bottom:16px}.toggle input{min-height:24px;width:22px}.notes{white-space:pre-wrap}.warning{color:#a54432!important}.confirm-backdrop{position:fixed;inset:0;background:#0005;display:grid;place-items:center;z-index:1000;padding:20px}.confirm-box{max-width:480px}@media(max-width:600px){.version-grid,.settings-grid{grid-template-columns:1fr}.upgrade-card{padding:18px}}
</style>
