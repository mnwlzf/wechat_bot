<script setup lang="ts">
import { onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Document,
  Link,
  Location,
  Microphone,
  Money,
  Picture,
  Postcard,
  Present,
  Refresh,
  Search,
  Star,
  Tickets,
  VideoCamera,
  VideoPlay,
} from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Conversation, MediaKind, Message, PageResult, PromptTemplate } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const messageLoading = ref(false)
const query = ref('')
const page = ref(0)
const pageSize = 20
const result = ref<PageResult<Conversation>>({
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: pageSize,
})
const drawerVisible = ref(false)
const activeConversation = ref<Conversation | null>(null)
const messages = ref<Message[]>([])
const templates = ref<PromptTemplate[]>([])

async function load() {
  loading.value = true
  try {
    result.value = await adminApi.conversations({
      query: query.value.trim() || undefined,
      page: page.value,
      size: pageSize,
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openConversation(row: Conversation) {
  activeConversation.value = row
  drawerVisible.value = true
  messageLoading.value = true
  messages.value = []
  try {
    const data = await adminApi.messages(row.id)
    messages.value = data.content
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    messageLoading.value = false
  }
}

function search() {
  page.value = 0
  load()
}

function changePage(nextPage: number) {
  page.value = nextPage - 1
  load()
}

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

function messageTime(value: string) {
  return dayjs(value).format('HH:mm')
}

const mediaMeta: Record<MediaKind, { label: string; icon: unknown }> = {
  IMAGE: { label: '图片', icon: Picture },
  VOICE: { label: '语音', icon: Microphone },
  VIDEO: { label: '视频', icon: VideoCamera },
  FILE: { label: '文件', icon: Document },
  LINK: { label: '链接', icon: Link },
  STICKER: { label: '表情', icon: Star },
  LOCATION: { label: '位置', icon: Location },
  CARD: { label: '名片', icon: Postcard },
  MINIAPP: { label: '小程序', icon: Present },
  RECORD: { label: '聊天记录', icon: Tickets },
  CHANNEL: { label: '视频号', icon: VideoPlay },
  TRANSFER: { label: '转账', icon: Money },
  REDPACKET: { label: '红包', icon: Money },
  CALL: { label: '通话', icon: ChatDotRound },
  SYSTEM: { label: '系统', icon: ChatDotRound },
  OTHER: { label: '消息', icon: ChatDotRound },
}

function mediaLabel(kind: MediaKind) {
  return mediaMeta[kind]?.label || '消息'
}

function mediaIcon(kind: MediaKind) {
  return mediaMeta[kind]?.icon || ChatDotRound
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function avatarText(name?: string) {
  return name?.trim().slice(0, 1).toUpperCase() || '?'
}

async function toggleTakeover(row: Conversation) {
  const next = row.botPaused
  try {
    const updated = await adminApi.setTakeover(row.id, next)
    row.botPaused = updated.botPaused
    if (activeConversation.value?.id === row.id) {
      activeConversation.value.botPaused = updated.botPaused
    }
    ElMessage.success(updated.botPaused ? '已接管，该会话不再自动回复' : '已交还机器人自动回复')
  } catch (error) {
    row.botPaused = !next
    ElMessage.error(getErrorMessage(error))
  }
}

async function changePersona(templateId: string) {
  if (!activeConversation.value) return
  try {
    const updated = await adminApi.setPersona(activeConversation.value.id, templateId || null)
    activeConversation.value.promptTemplateId = updated.promptTemplateId
    activeConversation.value.promptTemplateName = updated.promptTemplateName
    const row = result.value.content.find((item) => item.id === updated.id)
    if (row) {
      row.promptTemplateId = updated.promptTemplateId
      row.promptTemplateName = updated.promptTemplateName
    }
    ElMessage.success(updated.promptTemplateName ? `已切换到「${updated.promptTemplateName}」` : '已回退到默认提示词')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

onMounted(async () => {
  await load()
  try {
    templates.value = await adminApi.promptTemplates()
  } catch {
    /* 没有模板时下拉只显示“默认提示词” */
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>会话记录</h1>
        <p>查看所有微信聊天、上下文消息、模型调用结果与失败原因。</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="surface-card">
      <div class="toolbar">
        <el-input
          v-model="query"
          class="search-input"
          placeholder="搜索聊天名称或 Chat ID"
          :prefix-icon="Search"
          clearable
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <span class="total-label">共 {{ result.totalElements }} 个会话</span>
      </div>

      <div v-loading="loading" class="conversation-table">
        <el-table v-if="result.content.length" :data="result.content" style="width: 100%" @row-click="openConversation">
          <el-table-column label="会话" min-width="230">
            <template #default="{ row }">
              <div class="conversation-cell">
                <span class="chat-avatar" :class="row.chatType.toLowerCase()">{{ avatarText(row.chatName) }}</span>
                <div>
                  <strong>{{ row.chatName }}</strong>
                  <span v-if="row.botPaused" class="paused-tag">已接管</span>
                  <small class="mono">{{ row.chatId }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="86">
            <template #default="{ row }">
              <el-tag :type="row.chatType === 'GROUP' ? 'warning' : 'primary'" effect="light" size="small">
                {{ row.chatType === 'GROUP' ? '群聊' : '私聊' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后消息" min-width="320">
            <template #default="{ row }">
              <span class="last-message">{{ row.lastMessage || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最后活跃" width="150">
            <template #default="{ row }">{{ formatTime(row.lastMessageAt) }}</template>
          </el-table-column>
          <el-table-column label="接管" width="76">
            <template #default="{ row }">
              <el-switch
                v-model="row.botPaused"
                size="small"
                @click.stop
                @change="toggleTakeover(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="" width="70" align="right">
            <template #default><span class="open-link">查看</span></template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>没有找到会话</strong>
          {{ query ? '换个关键词试试。' : '收到微信消息后，会话会显示在这里。' }}
        </div>
      </div>

      <div v-if="result.totalPages > 1" class="pagination">
        <el-pagination
          background
          layout="prev, pager, next"
          :current-page="result.number + 1"
          :page-size="pageSize"
          :total="result.totalElements"
          @current-change="changePage"
        />
      </div>
    </section>

    <el-drawer v-model="drawerVisible" size="min(620px, 94vw)" :with-header="false" destroy-on-close>
      <div v-if="activeConversation" class="chat-drawer">
        <header class="chat-header">
          <div class="chat-avatar large" :class="activeConversation.chatType.toLowerCase()">
            {{ avatarText(activeConversation.chatName) }}
          </div>
          <div class="chat-title">
            <h2>
              {{ activeConversation.chatName }}
              <span v-if="activeConversation.botPaused" class="paused-tag">已接管</span>
            </h2>
            <p>
              <span class="mono">{{ activeConversation.chatId }}</span> ·
              {{ activeConversation.chatType === 'GROUP' ? '群聊' : '私聊' }}
            </p>
          </div>
          <el-select
            :model-value="activeConversation.promptTemplateId || ''"
            size="small"
            class="persona-select"
            placeholder="默认提示词"
            @change="changePersona"
          >
            <el-option label="默认提示词" value="" />
            <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-button text :icon="Refresh" :loading="messageLoading" @click="openConversation(activeConversation)">
            刷新
          </el-button>
        </header>

        <div v-loading="messageLoading" class="chat-body">
          <div v-if="messages.length === 0 && !messageLoading" class="empty-state">
            <strong>暂无消息</strong>
            该会话还没有可展示的消息。
          </div>
          <div
            v-for="message in messages"
            :key="message.id"
            class="message-row"
            :class="{ outbound: message.direction === 'OUTBOUND' }"
          >
            <div v-if="message.direction === 'INBOUND'" class="message-avatar">
              {{ avatarText(message.senderName) }}
            </div>
            <div class="message-stack">
              <div class="message-info">
                <span>{{ message.senderName || (message.direction === 'INBOUND' ? '用户' : '机器人') }}</span>
                <time>{{ messageTime(message.createdAt) }}</time>
              </div>
              <div class="bubble" :class="{ inbound: message.direction === 'INBOUND', failed: message.status === 'FAILED' }">
                <div v-if="message.quoteText" class="quote-block">
                  <span class="quote-author">{{ message.quoteAuthor || '引用' }}</span>
                  <span class="quote-text">{{ message.quoteText }}</span>
                </div>
                <span v-if="message.mediaKind" class="media-chip">
                  <el-icon :size="13"><component :is="mediaIcon(message.mediaKind)" /></el-icon>
                  <span class="media-label">{{ mediaLabel(message.mediaKind) }}</span>
                  <span v-if="message.mediaName" class="media-name">{{ message.mediaName }}</span>
                  <span v-if="message.mediaSize" class="media-size">{{ formatSize(message.mediaSize) }}</span>
                </span>
                <template v-else>{{ message.content }}</template>
              </div>
              <div v-if="message.errorMessage" class="error-text">{{ message.errorMessage }}</div>
              <div v-if="message.direction === 'OUTBOUND'" class="model-meta">
                <span v-if="message.providerName">{{ message.providerName }}</span>
                <span v-if="message.model">{{ message.model }}</span>
                <span v-if="message.latencyMs">{{ message.latencyMs }} ms</span>
                <span v-if="message.promptTokens || message.completionTokens">
                  {{ message.promptTokens || 0 }} + {{ message.completionTokens || 0 }} tokens
                </span>
              </div>
            </div>
            <div v-if="message.direction === 'OUTBOUND'" class="message-avatar bot">
              <el-icon><ChatDotRound /></el-icon>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line-soft);
}

.search-input { width: min(320px, 48vw); }
.total-label { margin-left: auto; color: var(--muted-soft); font-size: 10px; }
.conversation-table { min-height: 140px; padding: 0 12px; }
.conversation-cell { display: flex; align-items: center; gap: 8px; cursor: pointer; }

.chat-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 7px;
  color: #1d4ed8;
  background: #eff6ff;
  font-size: 11px;
  font-weight: 750;
}

.chat-avatar.group { color: #b45309; background: #fffbeb; }
.chat-avatar.large { width: 36px; height: 36px; border-radius: 8px; }
.conversation-cell strong, .conversation-cell small { display: block; }
.conversation-cell strong { color: var(--ink); font-size: var(--fs-sm); }

.conversation-cell small {
  max-width: 200px;
  overflow: hidden;
  margin-top: 1px;
  color: var(--muted-soft);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.last-message {
  display: block;
  max-width: 430px;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--fs-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.open-link { color: #3b82f6; font-size: 11px; }

.paused-tag {
  display: inline-block;
  margin-left: 5px;
  padding: 1px 6px;
  border-radius: 999px;
  color: #b45309;
  background: #fff7ed;
  font-size: 10px;
  font-weight: 550;
  vertical-align: middle;
}

.persona-select {
  width: 128px;
  flex: 0 0 auto;
}

.media-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 0;
  color: var(--ink-soft);
  font-size: var(--fs-xs);
}

.media-label {
  font-weight: 550;
}

.media-name {
  overflow: hidden;
  max-width: 200px;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
}

.media-size {
  color: var(--muted-soft);
  font-size: 10px;
}

.quote-block {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  padding: 4px 8px;
  border-left: 2px solid var(--line-strong, rgba(0, 0, 0, 0.18));
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.035);
  color: var(--ink-soft);
  font-size: var(--fs-xs);
  line-height: 1.45;
}

.quote-author {
  flex: 0 0 auto;
  font-weight: 550;
}

.quote-text {
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  word-break: break-word;
}
:deep(.el-table__row) { cursor: pointer; }
.pagination { display: flex; justify-content: flex-end; padding: 8px 12px; border-top: 1px solid var(--line-soft); }
.chat-drawer { display: flex; height: 100vh; flex-direction: column; background: #f7f9fc; }

.chat-header {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}

.chat-title { min-width: 0; flex: 1; }
.chat-title h2 { margin: 0; font-size: var(--fs-md); }

.chat-title p {
  overflow: hidden;
  margin: 2px 0 0;
  color: var(--muted-soft);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-body { flex: 1; overflow-y: auto; padding: 16px 14px 28px; }
.message-row { display: flex; align-items: flex-start; gap: 7px; margin-bottom: 12px; }
.message-row.outbound { justify-content: flex-end; }

.message-avatar {
  display: grid;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 6px;
  color: #475569;
  background: #e2e8f0;
  font-size: 10px;
  font-weight: 700;
}

.message-avatar.bot { color: #1d4ed8; background: #dbeafe; }
.message-stack { max-width: min(80%, 460px); }
.outbound .message-stack { text-align: right; }

.message-info {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
  color: var(--muted-soft);
  font-size: 10px;
}

.outbound .message-info { justify-content: flex-end; }

.bubble {
  display: inline-block;
  padding: 8px 10px;
  border-radius: 3px 9px 9px 9px;
  color: var(--ink-soft);
  background: #fff;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  font-size: var(--fs-sm);
  line-height: 1.6;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
}

.outbound .bubble {
  border-radius: 9px 3px 9px 9px;
  color: #eff6ff;
  background: #2563eb;
}

.bubble.failed { border: 1px solid #fecaca; }

.model-meta {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 3px;
  color: var(--muted-soft);
  font-size: 9px;
}

.error-text { margin-top: 3px; color: var(--danger); font-size: 10px; text-align: left; }

@media (max-width: 660px) {
  .toolbar { flex-wrap: wrap; }
  .search-input { width: 100%; }
  .total-label { display: none; }
  .conversation-table { padding: 0 8px; }
}
</style>

