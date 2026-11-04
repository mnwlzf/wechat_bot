<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Star, StarFilled, View } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { adminApi } from '@/api'
import type { Conversation, PersonaPreview, PromptTemplate, PromptTemplateInput } from '@/api/types'
import { getErrorMessage } from '@/api/client'
import ChatSelect from '@/components/ChatSelect.vue'

const loading = ref(false)
const saving = ref(false)
const templates = ref<PromptTemplate[]>([])
const conversations = ref<Conversation[]>([])
const personaSaving = ref<string | null>(null)
const keyword = ref('')
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const previewVisible = ref(false)
const previewLoading = ref(false)
const preview = ref<PersonaPreview | null>(null)

const bindVisible = ref(false)
const bindSaving = ref(false)
const bindForm = reactive({ chatId: '', promptTemplateId: '' })

const form = reactive<PromptTemplateInput>({ name: '', description: '', content: '' })

const formRules: FormRules = {
  name: [{ required: true, message: '请输入人设名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入人设内容', trigger: 'blur' }],
}

const defaultPersona = computed(() => templates.value.find((item) => item.defaultPersona))

const boundCount = computed(() => conversations.value.filter((item) => item.promptTemplateId).length)

/**
 * 会话列表搜索走服务端：会话表里可能只存了 wxid / @chatroom，
 * 后端会再用会话目录（备注 / 昵称 / 群名称）补搜一遍，所以搜「客户群」也能命中。
 */
async function loadConversations(query = keyword.value.trim()) {
  try {
    const data = await adminApi.conversations({ query: query || undefined, page: 0, size: 100 })
    conversations.value = data.content
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
function onKeywordChange() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadConversations(), 300)
}

/** 给某个群 / 某个人单独绑定人设；空值表示跟随默认人设。 */
async function bindPersona(row: Conversation, templateId: string) {
  personaSaving.value = row.id
  try {
    const updated = await adminApi.setPersona(row.id, templateId || null)
    // 解绑时后端会省略字段，统一按服务端返回值落定
    row.promptTemplateId = updated.promptTemplateId ?? null
    row.promptTemplateName = updated.promptTemplateName ?? null
    row.effectivePersonaName = updated.effectivePersonaName ?? null
    ElMessage.success(
      updated.promptTemplateName
        ? `「${row.chatName}」已改用「${updated.promptTemplateName}」`
        : defaultPersona.value
          ? `「${row.chatName}」已回退到默认人设「${defaultPersona.value.name}」`
          : `「${row.chatName}」已回到机器人系统提示词`,
    )
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    await loadConversations()
  } finally {
    personaSaving.value = null
  }
}

function openBindDialog() {
  bindForm.chatId = ''
  bindForm.promptTemplateId = defaultPersona.value?.id || ''
  bindVisible.value = true
}

/** 手动绑定：私聊填 wxid、群填 @chatroom 结尾的 ID，会话还没出现过也能先挂上人设。 */
async function submitBind() {
  if (!bindForm.chatId.trim()) {
    ElMessage.warning('请填写微信号 wxid 或群 ID')
    return
  }
  bindSaving.value = true
  try {
    const updated = await adminApi.bindPersonaByChatId(bindForm.chatId.trim(), bindForm.promptTemplateId || null)
    ElMessage.success(`已给「${updated.chatName}」指定人设「${updated.promptTemplateName || '默认'}」`)
    bindVisible.value = false
    await loadConversations()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    bindSaving.value = false
  }
}

async function openPreview(row: Conversation) {
  preview.value = null
  previewLoading.value = true
  previewVisible.value = true
  try {
    preview.value = await adminApi.personaPreview(row.id)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

const sourceLabels: Record<string, string> = {
  chat: '该会话单独指定',
  default: '全局默认人设',
  bot: '机器人自身提示词',
}

function sourceLabel(source?: string) {
  return (source && sourceLabels[source]) || '未知'
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

onMounted(async () => {
  await Promise.all([load(), loadConversations()])
})
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

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>按群 / 按人指定人设</h2>
          <p class="head-desc">
            群、私聊都能单独换人设，改完立即生效；没指定的继续跟随默认人设。
            聊天里发「换人设」也能让聊天对象自己从名称列表里挑。
            <template v-if="boundCount">当前已有 {{ boundCount }} 个会话单独指定。</template>
          </p>
        </div>
        <div class="head-actions">
          <el-input
            v-model="keyword"
            placeholder="搜索群名 / 好友昵称 / 备注 / chatId"
            clearable
            class="persona-search"
            @input="onKeywordChange"
            @clear="onKeywordChange"
          />
          <el-button :icon="Plus" @click="openBindDialog">手动指定</el-button>
        </div>
      </div>
      <div class="surface-card__body">
        <el-table v-if="conversations.length" :data="conversations" style="width: 100%" max-height="440">
          <el-table-column label="会话 / 群" min-width="230">
            <template #default="{ row }">
              <div class="chat-cell">
                <div class="chat-title-line">
                  <strong>{{ row.chatName }}</strong>
                  <el-tag size="small" :type="row.chatType === 'GROUP' ? 'primary' : 'success'" effect="plain">
                    {{ row.chatType === 'GROUP' ? '群聊' : '私聊' }}
                  </el-tag>
                </div>
                <span class="chat-id">{{ row.chatId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="当前人设" min-width="150">
            <template #default="{ row }">
              <span :class="{ inherited: !row.promptTemplateId }">
                {{ row.effectivePersonaName || row.promptTemplateName || '未指定' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="指定人设" width="210">
            <template #default="{ row }">
              <el-select
                :model-value="row.promptTemplateId || ''"
                size="small"
                class="persona-picker"
                :loading="personaSaving === row.id"
                @change="(value: string) => bindPersona(row, value)"
              >
                <el-option
                  :label="defaultPersona ? `跟随默认（${defaultPersona.name}）` : '跟随默认人设'"
                  value=""
                />
                <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="" width="82" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" :icon="View" @click="openPreview(row)">预览</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>{{ keyword ? '没搜到匹配的会话' : '还没有可以绑定的会话' }}</strong>
          {{ keyword
            ? '换个关键词试试，或者点「手动指定」按群名 / 昵称再搜一次。'
            : '机器人收到第一条消息之后，这里会出现群和私聊；也可以点「手动指定」按群名或昵称先绑上。' }}
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

    <el-dialog v-model="bindVisible" title="手动指定某个人 / 某个群的人设" width="min(560px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="群 / 好友">
          <ChatSelect v-model="bindForm.chatId" allow-create placeholder="搜群名、好友昵称，或直接粘贴 wxid" />
          <div class="field-hint">
            直接搜群名或好友昵称就行；会话还没出现过也能先绑定 —— 保存时会补一条会话记录，
            等这个群 / 这个人发来消息就按这份人设回。
          </div>
        </el-form-item>
        <el-form-item label="人设">
          <el-select v-model="bindForm.promptTemplateId" class="persona-picker" placeholder="跟随默认人设">
            <el-option
              :label="defaultPersona ? `跟随默认（${defaultPersona.name}）` : '跟随默认人设'"
              value=""
            />
            <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="bindSaving" @click="submitBind">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="这条会话实际会用的提示词" width="min(680px, 94vw)">
      <div v-loading="previewLoading" class="preview-body">
        <template v-if="preview">
          <div class="preview-head">
            <strong>{{ preview.chatName }}</strong>
            <el-tag size="small" :type="preview.chatType === 'GROUP' ? 'primary' : 'success'" effect="plain">
              {{ preview.chatType === 'GROUP' ? '群聊' : '私聊' }}
            </el-tag>
            <span class="chat-id">{{ preview.chatId }}</span>
          </div>
          <p class="preview-meta">
            人设：<b>{{ preview.personaName }}</b>
            <el-tag size="small" type="info" effect="plain">来源 {{ sourceLabel(preview.personaSource) }}</el-tag>
            <el-tag v-if="preview.personaBuiltin" size="small" effect="plain">内置</el-tag>
            <el-tag
              size="small"
              :type="preview.personaThin ? 'warning' : 'info'"
              effect="plain"
            >
              {{ preview.personaChars }} 字
            </el-tag>
          </p>

          <el-alert
            v-for="warning in preview.warnings || []"
            :key="warning"
            :title="warning"
            type="warning"
            :closable="false"
            show-icon
            class="preview-warning"
          />

          <div class="preview-block">
            <span class="block-label">① 人设（system）</span>
            <pre>{{ preview.personaContent }}</pre>
          </div>
          <div v-if="preview.switchNote" class="preview-block">
            <span class="block-label">② 刚换过人设的提醒（system）</span>
            <pre>{{ preview.switchNote }}</pre>
          </div>
          <div v-if="preview.memoryBlock" class="preview-block">
            <span class="block-label">③ 长期记忆（system）</span>
            <pre>{{ preview.memoryBlock }}</pre>
          </div>
          <div v-if="preview.styleBlock" class="preview-block">
            <span class="block-label">④ 微信格式约束（system，只管格式不管性格）</span>
            <pre>{{ preview.styleBlock }}</pre>
          </div>
          <p class="field-hint">
            这几段就是模型每轮最先看到的内容，顺序是先人设、再记忆、最后格式。
            人设换了但语气没变，通常就出在这几段里：人设太短、记忆里还留着旧名字旧口吻、
            或者窗口里几十条旧回复把语气带回去了。
          </p>
        </template>
      </div>
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

.persona-search {
  width: 220px;
}

.persona-picker {
  width: 100%;
}

.chat-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-title-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.chat-title-line strong {
  color: var(--ink);
  font-weight: 600;
}

.preview-body {
  min-height: 120px;
}

.preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 12px;
  color: var(--ink-soft);
  font-size: var(--fs-xs);
}

.preview-warning {
  margin-bottom: 10px;
}

.preview-block {
  margin-bottom: 12px;
}

.block-label {
  display: block;
  margin-bottom: 4px;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.preview-block pre {
  margin: 0;
  padding: 10px 12px;
  max-height: 180px;
  overflow: auto;
  border-radius: var(--radius-sm);
  background: var(--surface-soft);
  color: var(--ink-soft);
  font-size: var(--fs-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-id {
  color: var(--muted-soft);
  font-size: 10px;
}

.inherited {
  color: var(--muted);
}
</style>
