<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
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
  name: [{ required: true, message: '请输入模板名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入模板内容', trigger: 'blur' }],
}

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
      ElMessage.success('模板已更新')
    } else {
      await adminApi.createPromptTemplate({ ...form })
      ElMessage.success('模板已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(template: PromptTemplate) {
  try {
    await ElMessageBox.confirm(`确定删除模板「${template.name}」？已绑定该模板的会话会回退到默认提示词。`, '删除模板', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.deletePromptTemplate(template.id)
    ElMessage.success('模板已删除')
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
        <h1>提示词模板</h1>
        <p>把不同场景的语气与边界写成模板，在会话里绑定即可切换人格。</p>
      </div>
      <div class="head-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建模板</el-button>
      </div>
    </div>

    <section class="surface-card">
      <div class="surface-card__body">
        <el-table v-if="templates.length" :data="templates" style="width: 100%">
          <el-table-column label="模板" min-width="200">
            <template #default="{ row }">
              <div class="tpl-name">
                <strong>{{ row.name }}</strong>
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
          <el-table-column label="" width="118" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有模板</strong>
          先建一份「客服语气」或「技术群简洁风」，再到会话详情里绑定。
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑模板' : '新建模板'"
      width="min(640px, 92vw)"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="模板名称" prop="name">
            <el-input v-model="form.name" placeholder="例如 客服语气" />
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="form.description" placeholder="一句话说明适用场景" maxlength="300" />
          </el-form-item>
        </div>
        <el-form-item label="系统提示词" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="10"
            maxlength="20000"
            show-word-limit
            placeholder="你是……，回答时……，不要……"
          />
          <div class="field-hint">绑定到会话后，这条提示词会替代机器人默认系统提示词。</div>
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
.tpl-name strong {
  display: block;
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
</style>
