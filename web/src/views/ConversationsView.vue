<script setup lang="ts">
import { onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Refresh, Search } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Conversation, Message, PageResult } from '@/api/types'
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

function avatarText(name?: string) {
  return name?.trim().slice(0, 1).toUpperCase() || '?'
}

onMounted(load)
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

    <el-drawer v-model="drawerVisible" size="min(680px, 94vw)" :with-header="false" destroy-on-close>
      <div v-if="activeConversation" class="chat-drawer">
        <header class="chat-header">
          <div class="chat-avatar large" :class="activeConversation.chatType.toLowerCase()">
            {{ avatarText(activeConversation.chatName) }}
          </div>
          <div class="chat-title">
            <h2>{{ activeConversation.chatName }}</h2>
            <p>
              <span class="mono">{{ activeConversation.chatId }}</span> ·
              {{ activeConversation.chatType === 'GROUP' ? '群聊' : '私聊' }}
            </p>
          </div>
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
                {{ message.content }}
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
  gap: 9px;
  padding: 18px 20px;
  border-bottom: 1px solid #eef2f7;
}

.search-input { width: min(360px, 48vw); }
.total-label { margin-left: auto; color: #94a3b8; font-size: 12px; }
.conversation-table { min-height: 180px; padding: 0 20px; }
.conversation-cell { display: flex; align-items: center; gap: 11px; cursor: pointer; }

.chat-avatar {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 11px;
  color: #1d4ed8;
  background: #eff6ff;
  font-size: 13px;
  font-weight: 750;
}

.chat-avatar.group { color: #b45309; background: #fffbeb; }
.chat-avatar.large { width: 44px; height: 44px; border-radius: 13px; }
.conversation-cell strong, .conversation-cell small { display: block; }
.conversation-cell strong { color: #1e293b; font-size: 13px; }

.conversation-cell small {
  max-width: 200px;
  overflow: hidden;
  margin-top: 3px;
  color: #94a3b8;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.last-message {
  display: block;
  max-width: 430px;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.open-link { color: #3b82f6; font-size: 12px; }
:deep(.el-table__row) { cursor: pointer; }
.pagination { display: flex; justify-content: flex-end; padding: 18px 20px; border-top: 1px solid #f1f5f9; }
.chat-drawer { display: flex; height: 100vh; flex-direction: column; background: #f8fafc; }

.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 22px;
  border-bottom: 1px solid #e8eef6;
  background: #fff;
}

.chat-title { min-width: 0; flex: 1; }
.chat-title h2 { margin: 0; font-size: 15px; }

.chat-title p {
  overflow: hidden;
  margin: 4px 0 0;
  color: #94a3b8;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-body { flex: 1; overflow-y: auto; padding: 24px 22px 40px; }
.message-row { display: flex; align-items: flex-start; gap: 9px; margin-bottom: 20px; }
.message-row.outbound { justify-content: flex-end; }

.message-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: #475569;
  background: #e2e8f0;
  font-size: 12px;
  font-weight: 700;
}

.message-avatar.bot { color: #1d4ed8; background: #dbeafe; }
.message-stack { max-width: min(78%, 480px); }
.outbound .message-stack { text-align: right; }

.message-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 5px;
  color: #94a3b8;
  font-size: 11px;
}

.outbound .message-info { justify-content: flex-end; }

.bubble {
  display: inline-block;
  padding: 11px 13px;
  border-radius: 4px 13px 13px 13px;
  color: #334155;
  background: #fff;
  box-shadow: 0 2px 9px rgba(15, 23, 42, 0.04);
  font-size: 13px;
  line-height: 1.75;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
}

.outbound .bubble {
  border-radius: 13px 4px 13px 13px;
  color: #eff6ff;
  background: linear-gradient(145deg, #2563eb, #1d4ed8);
}

.bubble.failed { border: 1px solid #fecaca; }

.model-meta {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 6px;
  color: #94a3b8;
  font-size: 8px;
}

.error-text { margin-top: 5px; color: #dc2626; font-size: 11px; text-align: left; }

@media (max-width: 660px) {
  .toolbar { flex-wrap: wrap; }
  .search-input { width: 100%; }
  .total-label { display: none; }
  .conversation-table { padding: 0 12px; }
}
</style>
