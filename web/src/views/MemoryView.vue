<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { MagicStick, Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { ChatChoice, ChatMemory, Conversation } from '@/api/types'
import { getErrorMessage } from '@/api/client'
import ChatSelect from '@/components/ChatSelect.vue'

const loading = ref(false)
const saving = ref(false)
const summarizing = ref(false)
const conversations = ref<Conversation[]>([])
const memories = ref<ChatMemory[]>([])
const chatId = ref('')
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({ content: '', enabled: true })

const formRules: FormRules = {
  content: [{ required: true, message: '请输入要记住的内容', trigger: 'blur' }],
}

const summary = computed(() => memories.value.find((item) => item.kind === 'SUMMARY') || null)
const notes = computed(() => memories.value.filter((item) => item.kind === 'NOTE'))
/** 选择器里选中的名字：可能来自会话目录（这个群还没跟机器人说过话） */
const pickedName = ref('')
const activeName = computed(
  () => conversations.value.find((item) => item.chatId === chatId.value)?.chatName
    || pickedName.value
    || chatId.value,
)

async function loadConversations() {
  try {
    const data = await adminApi.conversations({ page: 0, size: 1 })
    conversations.value = data.content
    if (!chatId.value && data.content.length) {
      chatId.value = data.content[0].chatId
    }
  } catch {
    /* 会话列表不可用时允许手填 chatId */
  }
}

async function loadMemories() {
  if (!chatId.value) {
    memories.value = []
    return
  }
  loading.value = true
  try {
    memories.value = await adminApi.memories(chatId.value)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function onChatChange(conversation: ChatChoice | null) {
  if (conversation) pickedName.value = conversation.chatName
  await loadMemories()
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { content: '', enabled: true })
  dialogVisible.value = true
}

function openEdit(note: ChatMemory) {
  editingId.value = note.id
  Object.assign(form, { content: note.content, enabled: note.enabled })
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value || !chatId.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updateMemory(editingId.value, { content: form.content, enabled: form.enabled })
      ElMessage.success('记忆已更新')
    } else {
      await adminApi.createMemory({
        chatId: chatId.value,
        chatName: activeName.value,
        content: form.content,
        enabled: form.enabled,
      })
      ElMessage.success('已加入共享记忆')
    }
    dialogVisible.value = false
    await loadMemories()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function toggle(note: ChatMemory) {
  try {
    const updated = await adminApi.updateMemory(note.id, { enabled: note.enabled })
    note.enabled = updated.enabled
    ElMessage.success(updated.enabled ? '已启用该条记忆' : '已停用该条记忆')
  } catch (error) {
    note.enabled = !note.enabled
    ElMessage.error(getErrorMessage(error))
  }
}

async function remove(note: ChatMemory) {
  try {
    await ElMessageBox.confirm('删除后无法恢复，确定继续？', '删除记忆', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteMemory(note.id)
    ElMessage.success('已删除')
    await loadMemories()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function regenerate() {
  if (!chatId.value) return
  summarizing.value = true
  try {
    await adminApi.summarizeMemory(chatId.value)
    ElMessage.success('共享摘要已更新')
    await loadMemories()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    summarizing.value = false
  }
}

async function resetSummary() {
  if (!chatId.value) return
  try {
    await ElMessageBox.confirm('清空摘要后，下次会根据新消息重新压缩。人工要点不受影响。', '清空摘要', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.resetMemorySummary(chatId.value)
    ElMessage.success('摘要已清空')
    await loadMemories()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function timeLabel(value?: string | null) {
  return value ? dayjs(value).format('MM-DD HH:mm') : '—'
}

onMounted(async () => {
  await loadConversations()
  await loadMemories()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>共享记忆</h1>
        <p>
          同一个群的所有成员共用一份长期记忆：滚出上下文窗口的历史会被压成摘要，人工要点也会一起注入。
          这样群里前面定过的事，后面还能接得上。
        </p>
      </div>
      <el-button :icon="Refresh" @click="loadMemories">刷新</el-button>
    </div>

    <section class="surface-card">
      <div class="surface-card__body">
        <div class="toolbar">
          <ChatSelect
            v-model="chatId"
            allow-create
            placeholder="搜群名 / 好友昵称 / chatId"
            class="chat-select"
            @pick="onChatChange"
          />
          <el-button type="primary" :icon="Plus" :disabled="!chatId" @click="openCreate">添加要点</el-button>
        </div>
        <p class="chat-hint" v-if="chatId">
          当前：<b>{{ activeName }}</b>
          <span class="mono">{{ chatId }}</span>
        </p>
      </div>
    </section>

    <section class="surface-card" v-loading="loading">
      <div class="surface-card__head">
        <div>
          <h2>滚动摘要</h2>
          <p class="head-desc">把滚出窗口的历史压成一段文字，所有成员共用</p>
        </div>
        <div class="head-actions">
          <el-button
            :icon="MagicStick"
            :loading="summarizing"
            :disabled="!chatId"
            @click="regenerate"
          >
            立即生成
          </el-button>
          <el-button text type="danger" :disabled="!summary" @click="resetSummary">清空</el-button>
        </div>
      </div>
      <div class="surface-card__body">
        <div v-if="summary" class="summary-block">
          <p class="summary-text">{{ summary.content }}</p>
          <div class="summary-meta">
            <span>覆盖 {{ summary.messageCount }} 条消息</span>
            <span>压缩到 {{ timeLabel(summary.coveredUntil) }}</span>
            <span>更新于 {{ timeLabel(summary.updatedAt) }}</span>
          </div>
        </div>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有摘要</strong>
          聊天记录超过上下文窗口后会自动生成；也可以点「立即生成」手动压一次。
        </div>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>人工要点</h2>
          <p class="head-desc">约定、称呼、偏好、禁忌这类要长期记住的事</p>
        </div>
      </div>
      <div class="surface-card__body">
        <el-table v-if="notes.length" :data="notes" style="width: 100%">
          <el-table-column label="内容" min-width="360">
            <template #default="{ row }"><p class="note-text">{{ row.content }}</p></template>
          </el-table-column>
          <el-table-column label="启用" width="80">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" size="small" @change="toggle(row)" />
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="118">
            <template #default="{ row }"><span class="muted">{{ timeLabel(row.updatedAt) }}</span></template>
          </el-table-column>
          <el-table-column label="" width="118" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有人工要点</strong>
          比如「群里称呼老板为老张」「每周五下午开例会」，写进来之后机器人会一直记得。
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑记忆' : '添加记忆'"
      width="min(560px, 92vw)"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <el-form-item label="记住这件事" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="例如：群里管张伟叫老张；每周五 16:00 站会"
          />
          <div class="field-hint">会作为系统提示词的一部分，对「{{ activeName }}」里所有人生效。</div>
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
.chat-select {
  width: min(420px, 100%);
}

.chat-hint {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.chat-hint b {
  color: var(--ink);
  font-weight: 600;
}

.chat-hint .mono {
  margin-left: 8px;
  color: var(--muted-soft);
  font-size: 10px;
}

.summary-text {
  margin: 0;
  color: var(--ink-soft);
  font-size: var(--fs-sm);
  line-height: 1.75;
  white-space: pre-wrap;
}

.summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
  color: var(--muted-soft);
  font-size: 10px;
}

.note-text {
  margin: 0;
  color: var(--ink-soft);
  font-size: var(--fs-xs);
  line-height: 1.65;
  white-space: pre-wrap;
}
</style>
