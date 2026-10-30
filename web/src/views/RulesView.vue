<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { AutoReplyRule, AutoReplyRuleInput } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const saving = ref(false)
const rules = ref<AutoReplyRule[]>([])
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const emptyForm = (): AutoReplyRuleInput => ({
  name: '',
  matchType: 'CONTAINS',
  pattern: '',
  reply: '',
  scope: 'ALL',
  priority: 100,
  enabled: true,
})

const form = reactive<AutoReplyRuleInput>(emptyForm())

const rulesForm: FormRules = {
  name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  pattern: [{ required: true, message: '请输入匹配内容', trigger: 'blur' }],
  reply: [{ required: true, message: '请输入回复内容', trigger: 'blur' }],
}

const matchLabels: Record<string, string> = {
  EXACT: '完全一致',
  CONTAINS: '包含关键词',
  REGEX: '正则匹配',
}

const scopeLabels: Record<string, string> = {
  ALL: '全部会话',
  PRIVATE: '仅私聊',
  GROUP: '仅群聊',
}

async function load() {
  loading.value = true
  try {
    rules.value = await adminApi.autoReplies()
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

function openEdit(rule: AutoReplyRule) {
  editingId.value = rule.id
  Object.assign(form, {
    name: rule.name,
    matchType: rule.matchType,
    pattern: rule.pattern,
    reply: rule.reply,
    scope: rule.scope,
    priority: rule.priority,
    enabled: rule.enabled,
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
      await adminApi.updateAutoReply(editingId.value, { ...form })
      ElMessage.success('规则已更新')
    } else {
      await adminApi.createAutoReply({ ...form })
      ElMessage.success('规则已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function toggle(rule: AutoReplyRule) {
  try {
    const updated = await adminApi.updateAutoReply(rule.id, {
      name: rule.name,
      matchType: rule.matchType,
      pattern: rule.pattern,
      reply: rule.reply,
      scope: rule.scope,
      priority: rule.priority,
      enabled: rule.enabled,
    })
    Object.assign(rule, updated)
  } catch (error) {
    rule.enabled = !rule.enabled
    ElMessage.error(getErrorMessage(error))
  }
}

async function remove(rule: AutoReplyRule) {
  try {
    await ElMessageBox.confirm(`确定删除规则「${rule.name}」？`, '删除规则', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteAutoReply(rule.id)
    ElMessage.success('规则已删除')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>自动回复规则</h1>
        <p>命中关键词或正则时直接返回固定话术，不调用模型，响应更快也不消耗额度。</p>
      </div>
      <div class="head-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建规则</el-button>
      </div>
    </div>

    <section class="surface-card">
      <div class="surface-card__body">
        <el-table v-if="rules.length" :data="rules" style="width: 100%">
          <el-table-column label="规则" min-width="200">
            <template #default="{ row }">
              <div class="rule-name">
                <strong>{{ row.name }}</strong>
                <span>{{ row.reply }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="匹配" min-width="180">
            <template #default="{ row }">
              <span class="tag-soft">{{ matchLabels[row.matchType] || row.matchType }}</span>
              <code class="pattern">{{ row.pattern }}</code>
            </template>
          </el-table-column>
          <el-table-column label="范围" width="102">
            <template #default="{ row }">
              <span class="muted">{{ scopeLabels[row.scope] || row.scope }}</span>
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="82">
            <template #default="{ row }"><span class="muted">{{ row.priority }}</span></template>
          </el-table-column>
          <el-table-column label="启用" width="72">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" @change="toggle(row)" />
            </template>
          </el-table-column>
          <el-table-column label="" width="118" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有规则</strong>
          常见用法：价格、地址、营业时间这类固定问题交给规则秒回。
        </div>
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑规则' : '新建规则'" width="min(560px, 92vw)">
      <el-form ref="formRef" :model="form" :rules="rulesForm" label-position="top">
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="form.name" placeholder="例如 价格咨询" />
        </el-form-item>
        <div class="form-grid three">
          <el-form-item label="匹配方式">
            <el-select v-model="form.matchType" style="width: 100%">
              <el-option label="包含关键词" value="CONTAINS" />
              <el-option label="完全一致" value="EXACT" />
              <el-option label="正则匹配" value="REGEX" />
            </el-select>
          </el-form-item>
          <el-form-item label="适用范围">
            <el-select v-model="form.scope" style="width: 100%">
              <el-option label="全部会话" value="ALL" />
              <el-option label="仅私聊" value="PRIVATE" />
              <el-option label="仅群聊" value="GROUP" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number
              v-model="form.priority"
              :min="0"
              :max="10000"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
        </div>
        <el-form-item label="匹配内容" prop="pattern">
          <el-input v-model="form.pattern" placeholder="包含关键词时填 价格；正则时填 (发票|开票)" />
          <div class="field-hint">优先级数字越小越先匹配；正则忽略大小写。</div>
        </el-form-item>
        <el-form-item label="回复内容" prop="reply">
          <el-input v-model="form.reply" type="textarea" :rows="3" maxlength="5000" show-word-limit />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
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
.rule-name strong {
  display: block;
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.rule-name span {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--fs-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-soft {
  display: inline-block;
  margin-right: 6px;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--muted);
  background: #f1f5f9;
  font-size: 11px;
}

.pattern {
  color: var(--ink-soft);
  font-size: 12px;
}
</style>
