<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Connection, Delete, EditPen, Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Provider, ProviderInput, ProviderTestResult } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const saving = ref(false)
const providers = ref<Provider[]>([])
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const testingId = ref<string | null>(null)
const testResult = ref<ProviderTestResult | null>(null)
const testDialogVisible = ref(false)

const emptyForm = (): ProviderInput => ({
  name: '',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
  model: 'deepseek-chat',
  enabled: true,
  priority: 100,
  timeoutSeconds: 60,
  backupApiKey: '',
  webSearchEnabled: true,
})
const form = reactive<ProviderInput>(emptyForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入提供方名称', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: 'Base URL 必须以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
  model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
}

const presets = [
  { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { name: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { name: 'Moonshot', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4.1-mini' },
]

async function load() {
  loading.value = true
  try {
    providers.value = await adminApi.providers()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(provider: Provider) {
  editingId.value = provider.id
  Object.assign(form, {
    name: provider.name,
    baseUrl: provider.baseUrl,
    apiKey: '',
    model: provider.model,
    enabled: provider.enabled,
    priority: provider.priority,
    timeoutSeconds: provider.timeoutSeconds,
    backupApiKey: '',
    webSearchEnabled: provider.webSearchEnabled,
  })
  dialogVisible.value = true
}

function applyPreset(preset: (typeof presets)[number]) {
  form.name = preset.name
  form.baseUrl = preset.baseUrl
  form.model = preset.model
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (!editingId.value && !form.apiKey?.trim()) {
    ElMessage.warning('新增模型时必须填写 API Key')
    return
  }

  saving.value = true
  try {
    const payload: ProviderInput = {
      ...form,
      apiKey: form.apiKey?.trim() || undefined,
      backupApiKey: form.backupApiKey?.trim() || undefined,
    }
    if (editingId.value) {
      await adminApi.updateProvider(editingId.value, payload)
      ElMessage.success('模型配置已更新')
    } else {
      await adminApi.createProvider(payload)
      ElMessage.success('模型提供方已添加')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(provider: Provider) {
  try {
    await ElMessageBox.confirm(`确认删除模型提供方“${provider.name}”？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await adminApi.deleteProvider(provider.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(getErrorMessage(error))
    }
  }
}

async function test(provider: Provider) {
  testingId.value = provider.id
  try {
    testResult.value = await adminApi.testProvider(provider.id)
    testDialogVisible.value = true
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    testingId.value = null
  }
}

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>模型配置</h1>
        <p>统一管理 OpenAI Chat Completions 兼容服务，机器人可在多个提供方之间回退。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增模型</el-button>
      </div>
    </div>

    <div class="notice-bar">
      <div class="notice-icon"><el-icon><Connection /></el-icon></div>
      <div>
        <strong>Base URL 需要包含版本路径</strong>
        <span>后端会拼接 <code>/chat/completions</code>。例如 DeepSeek 使用 https://api.deepseek.com/v1。</span>
      </div>
    </div>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>模型提供方</h2>
          <p class="head-desc">优先级数字越小越先调用；主模型失败时按顺序回退。</p>
        </div>
        <span class="count-badge">{{ providers.length }} providers</span>
      </div>
      <div class="table-wrap" v-loading="loading">
        <el-table v-if="providers.length" :data="providers" style="width: 100%">
          <el-table-column label="提供方" min-width="170">
            <template #default="{ row }">
              <div class="provider-cell">
                <span class="provider-logo">{{ row.name.slice(0, 1).toUpperCase() }}</span>
                <div>
                  <strong>{{ row.name }}</strong>
                  <small>
                    {{ row.apiKeyHint || '未设置 Key' }}
                    <span v-if="row.hasBackupApiKey"> · 备用 {{ row.backupApiKeyHint }}</span>
                    <span v-else> · 无备用 Key</span>
                    <span v-if="row.webSearchEnabled"> · 联网</span>
                  </small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="model" label="模型" min-width="150">
            <template #default="{ row }"><span class="model-chip">{{ row.model }}</span></template>
          </el-table-column>
          <el-table-column prop="baseUrl" label="Base URL" min-width="260" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono url-text">{{ row.baseUrl }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" effect="light" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="priority" label="优先级" width="82" align="center" />
          <el-table-column label="更新时间" width="144">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" :loading="testingId === row.id" @click="test(row)">
                测试
              </el-button>
              <el-button text size="small" :icon="EditPen" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" :icon="Delete" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有模型提供方</strong>
          添加 DeepSeek、通义千问或其他 OpenAI 兼容服务后即可开始对话。
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑模型提供方' : '新增模型提供方'"
      width="640px"
      destroy-on-close
    >
      <div class="preset-row">
        <span>快速模板</span>
        <el-button v-for="preset in presets" :key="preset.name" size="small" round @click="applyPreset(preset)">
          {{ preset.name }}
        </el-button>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" placeholder="例如 DeepSeek 主模型" />
          </el-form-item>
          <el-form-item label="模型 ID" prop="model">
            <el-input v-model="form.model" placeholder="deepseek-chat" />
          </el-form-item>
        </div>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="editingId ? '留空表示不修改现有 Key' : 'sk-...'"
          />
          <div class="field-hint">Key 使用 AES-GCM 加密后落库，不会返回前端。</div>
        </el-form-item>
        <el-form-item label="备用 API Key（主 Key 失效时自动切换）">
          <el-input
            v-model="form.backupApiKey"
            type="password"
            show-password
            :placeholder="editingId ? '留空表示保持现有备用 Key' : '可选，sk-...'"
          />
          <div class="field-hint">
            主 Key 返回 401/402/403 立即切换；429/5xx 先重试一次再切换。留空表示不配置备用通道。
          </div>
        </el-form-item>
        <el-form-item label="联网搜索">
          <el-switch v-model="form.webSearchEnabled" />
          <div class="field-hint">
            开启后按关键词触发 DeepSeek 官方搜索（/v1/tools/search），需要官方 Key 才能生效。
          </div>
        </el-form-item>
        <div class="form-grid three">
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="0" :max="10000" controls-position="right" />
          </el-form-item>
          <el-form-item label="超时（秒）">
            <el-input-number v-model="form.timeoutSeconds" :min="5" :max="300" controls-position="right" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存配置</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialogVisible" title="连通性测试" width="520px">
      <div v-if="testResult" class="test-result" :class="{ failed: !testResult.success }">
        <div class="test-head">
          <span class="test-dot"></span>
          <strong>{{ testResult.success ? '模型调用成功' : '模型调用失败' }}</strong>
          <span v-if="testResult.success">{{ testResult.latencyMs }} ms</span>
        </div>
        <p>{{ testResult.reply }}</p>
        <small>模型：{{ testResult.model }}</small>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 9px;
}

.notice-bar {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 18px;
  padding: 14px 16px;
  border: 1px solid #dbeafe;
  border-radius: 13px;
  background: #eff6ff;
}

.notice-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: #2563eb;
  background: #dbeafe;
}

.notice-bar strong,
.notice-bar span {
  display: block;
}

.notice-bar strong {
  color: #1e3a8a;
  font-size: 12px;
}

.notice-bar span {
  margin-top: 3px;
  color: #64748b;
  font-size: 11px;
}

.notice-bar code {
  color: #1d4ed8;
}

.head-desc {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 11px;
}

.count-badge {
  padding: 5px 9px;
  border-radius: 7px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 9px;
  font-weight: 650;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.table-wrap {
  min-height: 150px;
  padding: 0 20px 20px;
}

.provider-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.provider-logo {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 10px;
  color: #1d4ed8;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
  font-size: 12px;
  font-weight: 800;
}

.provider-cell strong,
.provider-cell small {
  display: block;
}

.provider-cell strong {
  color: #1e293b;
  font-size: 12px;
}

.provider-cell small {
  margin-top: 3px;
  color: #94a3b8;
  font-size: 9px;
}

.model-chip {
  padding: 4px 7px;
  border-radius: 6px;
  color: #475569;
  background: #f1f5f9;
  font-size: 10px;
}

.url-text {
  color: #64748b;
  font-size: 10px;
}

.preset-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f1f5f9;
}

.preset-row > span {
  margin-right: 3px;
  color: #94a3b8;
  font-size: 11px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-grid.three {
  grid-template-columns: 0.8fr 1fr 1fr;
}

.field-hint {
  margin-top: 5px;
  color: #94a3b8;
  font-size: 10px;
}

.test-result {
  padding: 18px;
  border: 1px solid #bbf7d0;
  border-radius: 13px;
  background: #f0fdf4;
}

.test-result.failed {
  border-color: #fecaca;
  background: #fef2f2;
}

.test-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #166534;
  font-size: 12px;
}

.failed .test-head {
  color: #b91c1c;
}

.test-head span:last-child {
  margin-left: auto;
  color: #64748b;
  font-size: 10px;
}

.test-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
}

.failed .test-dot {
  background: #ef4444;
}

.test-result p {
  overflow-wrap: anywhere;
  margin: 14px 0;
  color: #334155;
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.test-result small {
  color: #94a3b8;
  font-size: 10px;
}

@media (max-width: 680px) {
  .form-grid,
  .form-grid.three {
    grid-template-columns: 1fr;
  }
}
</style>
