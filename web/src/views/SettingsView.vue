<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { SettingsSnapshot } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const savingChat = ref(false)
const savingLlm = ref(false)
const snapshot = ref<SettingsSnapshot | null>(null)

const chatForm = reactive({
  allowedChatsText: '',
  acceptAll: true,
  groupsEnabled: false,
})

const llmForm = reactive({
  fallbackEnabled: true,
  searchEnabled: true,
})

async function load() {
  loading.value = true
  try {
    const data = await adminApi.settings()
    snapshot.value = data
    chatForm.allowedChatsText = data.allowedChats.value.join('\n')
    chatForm.acceptAll = data.acceptAll.value
    chatForm.groupsEnabled = data.groupsEnabled.value
    llmForm.fallbackEnabled = data.fallbackEnabled.value
    llmForm.searchEnabled = data.searchEnabled.value
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function saveChat() {
  savingChat.value = true
  try {
    const allowedChats = chatForm.allowedChatsText
      .split(/[\n,]/)
      .map((item) => item.trim())
      .filter(Boolean)
    snapshot.value = await adminApi.updateWeChatPolicy({
      groupsEnabled: chatForm.groupsEnabled,
      acceptAll: chatForm.acceptAll,
      allowedChats,
    })
    ElMessage.success('会话策略已生效，无需重启')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingChat.value = false
  }
}

async function saveLlm() {
  savingLlm.value = true
  try {
    snapshot.value = await adminApi.updateLlmPolicy({
      fallbackEnabled: llmForm.fallbackEnabled,
      searchEnabled: llmForm.searchEnabled,
    })
    ElMessage.success('模型策略已生效，无需重启')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingLlm.value = false
  }
}

function sourceLabel(source?: string) {
  return source === 'db' ? '数据库' : '环境变量'
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>运行策略</h1>
        <p>这里的改动写入数据库并立即生效，不需要重启后端，也不需要改 .env。</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>会话白名单</h2>
          <p class="head-desc">每行一个 wxid 或 @chatroom；留空且开启“全量接收”时，所有私聊都会被回复。</p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.allowedChats.source) : '—' }}</span>
      </div>
      <div class="card-body">
        <el-input
          v-model="chatForm.allowedChatsText"
          type="textarea"
          :rows="6"
          placeholder="wxid_friend&#10;49878296302@chatroom"
        />
        <div class="switch-row">
          <div>
            <strong>全量接收</strong>
            <small>白名单为空时是否接收所有私聊</small>
          </div>
          <el-switch v-model="chatForm.acceptAll" />
        </div>
        <div class="switch-row">
          <div>
            <strong>处理群聊消息</strong>
            <small>关闭时群消息直接忽略；开启后还需要命中唤醒词才会回复</small>
          </div>
          <el-switch v-model="chatForm.groupsEnabled" />
        </div>
        <div class="card-actions">
          <el-button type="primary" :loading="savingChat" @click="saveChat">保存并生效</el-button>
        </div>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>模型链路</h2>
          <p class="head-desc">关闭备用链路后，只使用主 Provider 的主/备双 Key 兜底，不再切换到下一个 Provider。</p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.fallbackEnabled.source) : '—' }}</span>
      </div>
      <div class="card-body">
        <div class="switch-row">
          <div>
            <strong>启用备用链路（多 Provider 回退）</strong>
            <small>开启：主 Provider 主备 Key 都失败后，按 priority 继续尝试下一个 Provider</small>
          </div>
          <el-switch v-model="llmForm.fallbackEnabled" />
        </div>
        <div class="switch-row">
          <div>
            <strong>联网搜索</strong>
            <small>需要 DeepSeek 官方 Key（LLM_SEARCH_API_KEY 或在模型配置里填）</small>
          </div>
          <el-switch v-model="llmForm.searchEnabled" />
        </div>
        <div class="card-actions">
          <el-button type="primary" :loading="savingLlm" @click="saveLlm">保存并生效</el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.page-head h1 {
  margin: 0;
  font-size: 19px;
}

.page-head p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 11px;
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
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 0 20px 20px;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid #eef2f7;
  border-radius: 11px;
  background: #fbfdff;
}

.switch-row strong {
  display: block;
  color: #1e293b;
  font-size: 12px;
}

.switch-row small {
  display: block;
  margin-top: 4px;
  color: #94a3b8;
  font-size: 10px;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
