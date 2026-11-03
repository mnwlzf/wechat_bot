<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Star, StarFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { adminApi } from '@/api'
import type { PromptTemplate, PromptTemplateInput } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const saving = ref(false)
const templates = ref<PromptTemplate[]>([])
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<PromptTemplateInput>({ name: '', description: '', content: '' })

const formRules: FormRules = {
  name: [{ required: true, message: '请输入人设名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入人设内容', trigger: 'blur' }],
}

const defaultPersona = computed(() => templates.value.find((item) => item.defaultPersona))

async function load() {
  loading.value = true
  try {
    templates.value = await adminApi.promptTemplates()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', description: '', content: '' })
  dialogVisible.value = true
}

function openEdit(template: PromptTemplate) {
  editingId.value = template.id
  Object.assign(form, {
    name: template.name,
    description: template.description,
    content: template.content,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updatePromptTemplate(editingId.value, { ...form })
      ElMessage.success('人设已更新')
    } else {
      await adminApi.createPromptTemplate({ ...form })
      ElMessage.success('人设已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function setDefault(template: PromptTemplate) {
  try {
    await adminApi.setDefaultPromptTemplate(template.id)
    ElMessage.success(`已把「${template.name}」设为默认人设`)
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function clearDefault() {
  try {
    await adminApi.clearDefaultPromptTemplate()
    ElMessage.success('已取消默认人设，未指定的会话改用机器人系统提示词')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function remove(template: PromptTemplate) {
  const hint = template.defaultPersona
    ? `「${template.name}」是当前默认人设，删除后会自动改由最近更新的人设担任默认。`
    : `已单独指定「${template.name}」的会话会回退到默认人设。`
  try {
    await ElMessageBox.confirm(hint, '删除人设', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.deletePromptTemplate(template.id)
    ElMessage.success('人设已删除')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function updatedAt(value: string) {
  return dayjs(value).format('MM-DD HH:mm')
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>人设</h1>
        <p>
          提示词优先级：<b>会话/群单独指定 → 默认人设 → 机器人系统提示词</b>。没指定的群自动走默认，
          不用逐个配置。
        </p>
      </div>
      <div class="head-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建人设</el-button>
      </div>
    </div>

    <section class="surface-card default-card">
      <div class="surface-card__body default-body">
        <div class="default-copy">
          <span class="default-label">当前默认人设</span>
          <strong v-if="defaultPersona">{{ defaultPersona.name }}</strong>
          <strong v-else class="muted">未设置（使用机器人系统提示词）</strong>
          <p v-if="defaultPersona" class="default-desc">{{ defaultPersona.description || '未填写说明' }}</p>
          <p v-else class="default-desc">建议指定一个人设，否则每个没单独配置的群都会用同一段通用提示词。</p>
        </div>
        <el-button v-if="defaultPersona" text type="primary" @click="clearDefault">取消默认</el-button>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__body">
        <el-table v-if="templates.length" :data="templates" style="width: 100%">
          <el-table-column label="人设" min-width="220">
            <template #default="{ row }">
              <div class="tpl-name">
                <div class="tpl-title">
                  <strong>{{ row.name }}</strong>
                  <el-tag v-if="row.defaultPersona" type="success" size="small" effect="light">默认</el-tag>
                  <el-tag v-if="row.builtin" type="info" size="small" effect="plain">内置</el-tag>
                </div>
                <span>{{ row.description || '未填写说明' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="内容预览" min-width="320">
            <template #default="{ row }">
              <p class="tpl-preview">{{ row.content }}</p>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="118">
            <template #default="{ row }"><span class="muted">{{ updatedAt(row.updatedAt) }}</span></template>
          </el-table-column>
          <el-table-column label="" width="210" align="right">
            <template #default="{ row }">
              <el-button
                v-if="!row.defaultPersona"
                text
                type="primary"
                size="small"
                :icon="Star"
                @click="setDefault(row)"
              >
                设为默认
              </el-button>
              <span v-else class="default-mark">
                <el-icon :size="12"><StarFilled /></el-icon> 默认
              </span>
              <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有人设</strong>
          新建一份「大肥鱼」或「客服语气」，设成默认后所有没单独指定的群都会用它。
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑人设' : '新建人设'"
      width="min(640px, 92vw)"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="人设名称" prop="name">
            <el-input v-model="form.name" placeholder="例如 大肥鱼" />
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="form.description" placeholder="一句话说明这个人的性格" maxlength="300" />
          </el-form-item>
        </div>
        <el-form-item label="人设提示词" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="14"
            maxlength="20000"
            show-word-limit
            placeholder="你是……，说话时……，不要……"
          />
          <div class="field-hint">
            这段文字会作为系统提示词发给模型。会话单独指定了人设时优先用它，否则用默认人设。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.default-card {
  margin-bottom: 14px;
}

.default-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.default-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.default-label {
  color: var(--muted-soft);
  font-size: 10px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.default-copy strong {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 650;
}

.default-desc {
  margin: 0;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.tpl-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tpl-name strong {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.tpl-name span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.tpl-preview {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  color: var(--ink-soft);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.default-mark {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-right: 6px;
  color: #16a34a;
  font-size: 11px;
}
</style>
