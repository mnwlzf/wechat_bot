<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Refresh, User } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { ChatCandidate, SettingsSnapshot } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const loadingCandidates = ref(false)
const savingChat = ref(false)
const savingLlm = ref(false)
const snapshot = ref<SettingsSnapshot | null>(null)
const candidates = ref<ChatCandidate[]>([])
const candidateHint = ref('')

const chatForm = reactive({
  allowedChats: [] as string[],
  acceptAll: true,
  groupsEnabled: false,
})

const llmForm = reactive({
  fallbackEnabled: true,
  searchEnabled: true,
})

const filterForm = reactive({
  bannedWords: [] as string[],
  mode: 'off' as 'off' | 'rewrite' | 'block',
})

const savingFilter = ref(false)

const filterDirty = computed(() => {
  const saved = snapshot.value
  if (!saved) return false
  const savedWords = [...(saved.bannedWords?.value ?? [])].sort().join(',')
  const currentWords = [...filterForm.bannedWords].sort().join(',')
  return savedWords !== currentWords || (saved.filterMode?.value ?? 'off') !== filterForm.mode
})

const chatDirty = computed(() => {
  const saved = snapshot.value
  if (!saved) return false
  const savedChats = [...saved.allowedChats.value].sort().join(',')
  const currentChats = [...chatForm.allowedChats].sort().join(',')
  return (
    savedChats !== currentChats ||
    saved.acceptAll.value !== chatForm.acceptAll ||
    saved.groupsEnabled.value !== chatForm.groupsEnabled
  )
})

const llmDirty = computed(() => {
  const saved = snapshot.value
  if (!saved) return false
  return (
    saved.fallbackEnabled.value !== llmForm.fallbackEnabled ||
    saved.searchEnabled.value !== llmForm.searchEnabled
  )
})

const selectedRows = computed(() =>
  chatForm.allowedChats.map((chatId) => {
    const known = candidates.value.find((item) => item.chatId === chatId)
    return {
      chatId,
      name: known?.name || chatId,
      group: known ? known.group : chatId.endsWith('@chatroom'),
      unknown: !known,
    }
  }),
)

const groupOptions = computed(() => candidates.value.filter((item) => item.group))
const privateOptions = computed(() => candidates.value.filter((item) => !item.group))

async function load() {
  loading.value = true
  try {
    const data = await adminApi.settings()
    snapshot.value = data
    chatForm.allowedChats = [...data.allowedChats.value]
    chatForm.acceptAll = data.acceptAll.value
    chatForm.groupsEnabled = data.groupsEnabled.value
    llmForm.fallbackEnabled = data.fallbackEnabled.value
    llmForm.searchEnabled = data.searchEnabled.value
    filterForm.bannedWords = [...(data.bannedWords?.value ?? [])]
    filterForm.mode = data.filterMode?.value ?? 'off'
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

/** 会话候选：历史会话（后端库）+ 微信当前会话 + 联系人，任一来源可用即可。 */
async function loadCandidates(notify = false) {
  loadingCandidates.value = true
  const merged = new Map<string, ChatCandidate>()

  const push = (chatId: string, name: string, source: ChatCandidate['source']) => {
    if (!chatId) return
    const group = chatId.endsWith('@chatroom')
    const existing = merged.get(chatId)
    if (!existing) {
      merged.set(chatId, { chatId, name: name || chatId, group, source })
      return
    }
    if ((!existing.name || existing.name === chatId) && name) {
      existing.name = name
    }
    if (source === 'contact') existing.source = 'contact'
  }

  try {
    const conversations = await adminApi.conversations({ page: 0, size: 200 })
    conversations.content.forEach((item) => push(item.chatId, item.chatName, 'conversation'))
  } catch {
    /* 后端会话库为空时忽略 */
  }

  let hookWarning = ''
  try {
    const sessions = await adminApi.sessions(200)
    sessions.forEach((row) => push(row.username || '', row.username || '', 'session'))
  } catch (error) {
    hookWarning = getErrorMessage(error)
  }
  try {
    const contacts = await adminApi.contacts(200)
    contacts.forEach((row) => {
      const name = row.remark || row.nick_name || row.alias || row.username || ''
      push(row.username || '', name, 'contact')
    })
  } catch (error) {
    hookWarning = hookWarning || getErrorMessage(error)
  }

  candidates.value = [...merged.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
  loadingCandidates.value = false

  candidateHint.value = hookWarning
    ? `已载入 ${candidates.value.length} 个历史会话；微信实时会话不可用（${hookWarning}）`
    : `来自历史会话 ${candidates.value.length} 个`

  if (notify) {
    if (candidates.value.length) {
      ElMessage.success(`已载入 ${candidates.value.length} 个候选会话`)
    } else {
      ElMessage.warning('没有拿到会话列表，可直接输入 wxid 或 @chatroom 后回车')
    }
  }
}

async function saveChat() {
  savingChat.value = true
  try {
    snapshot.value = await adminApi.updateWeChatPolicy({
      groupsEnabled: chatForm.groupsEnabled,
      acceptAll: chatForm.acceptAll,
      allowedChats: chatForm.allowedChats,
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

async function saveFilter() {
  savingFilter.value = true
  try {
    snapshot.value = await adminApi.updateFilterPolicy({
      bannedWords: filterForm.bannedWords,
      mode: filterForm.mode,
    })
    ElMessage.success('内容过滤已生效')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingFilter.value = false
  }
}

function removeChat(chatId: string) {
  chatForm.allowedChats = chatForm.allowedChats.filter((item) => item !== chatId)
}

function sourceLabel(source?: string) {
  return source === 'db' ? '数据库' : '环境变量'
}

onMounted(async () => {
  await load()
  await loadCandidates()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>运行策略</h1>
        <p>改动写入数据库并立即生效，不需要重启后端，也不用改 .env。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="settings-grid">
    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>会话白名单</h2>
          <p class="head-desc">只有名单内的会话才会被回复。留空且开启「全量接收」时，所有私聊都会被回复。</p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.allowedChats.source) : '—' }}</span>
      </div>

      <div class="surface-card__body">
        <div class="picker-head">
          <span class="hint">{{ candidateHint || '从会话列表选择，或直接粘贴 wxid / @chatroom 后回车' }}</span>
          <el-button
            size="small"
            :loading="loadingCandidates"
            :icon="Refresh"
            @click="loadCandidates(true)"
          >
            载入微信会话
          </el-button>
        </div>

        <el-select
          v-model="chatForm.allowedChats"
          class="picker"
          multiple
          filterable
          allow-create
          default-first-option
          collapse-tags
          collapse-tags-tooltip
          :reserve-keyword="false"
          placeholder="搜索昵称、备注或 wxid"
        >
          <el-option-group v-if="groupOptions.length" label="群聊">
            <el-option
              v-for="item in groupOptions"
              :key="item.chatId"
              :label="item.name"
              :value="item.chatId"
            >
              <div class="option-row">
                <span class="option-name">{{ item.name }}</span>
                <span v-if="item.name !== item.chatId" class="option-id mono">{{ item.chatId }}</span>
              </div>
            </el-option>
          </el-option-group>
          <el-option-group v-if="privateOptions.length" label="好友与私聊">
            <el-option
              v-for="item in privateOptions"
              :key="item.chatId"
              :label="item.name"
              :value="item.chatId"
            >
              <div class="option-row">
                <span class="option-name">{{ item.name }}</span>
                <span v-if="item.name !== item.chatId" class="option-id mono">{{ item.chatId }}</span>
              </div>
            </el-option>
          </el-option-group>
        </el-select>

        <ul v-if="selectedRows.length" class="chat-list">
          <li v-for="row in selectedRows" :key="row.chatId" class="chat-item">
            <span class="chat-icon">
              <el-icon><component :is="row.group ? ChatDotRound : User" /></el-icon>
            </span>
            <span class="chat-copy">
              <strong>{{ row.name }}</strong>
              <span v-if="row.name !== row.chatId" class="mono chat-id">{{ row.chatId }}</span>
            </span>
            <el-tag :type="row.group ? 'warning' : 'info'" effect="plain" size="small">
              {{ row.group ? '群聊' : '私聊' }}
            </el-tag>
            <el-tag v-if="row.unknown" type="info" effect="plain" size="small">未识别</el-tag>
            <el-button text type="danger" size="small" @click="removeChat(row.chatId)">移除</el-button>
          </li>
        </ul>
        <div v-else class="chat-empty hint">还没有白名单条目</div>

        <div class="settings-block">
          <div class="setting-row">
            <div>
              <strong>全量接收私聊</strong>
              <small>白名单为空时，是否接收所有私聊消息</small>
            </div>
            <div class="setting-row__control">
              <el-switch v-model="chatForm.acceptAll" />
            </div>
          </div>
          <div class="setting-row">
            <div>
              <strong>处理群聊消息</strong>
              <small>关闭时群消息直接忽略；开启后还要命中唤醒词才会回复</small>
            </div>
            <div class="setting-row__control">
              <el-switch v-model="chatForm.groupsEnabled" />
            </div>
          </div>
        </div>

        <div class="card-actions">
          <span v-if="chatDirty" class="dirty-dot">有未保存的改动</span>
          <el-button type="primary" :disabled="!chatDirty" :loading="savingChat" @click="saveChat">
            保存并生效
          </el-button>
        </div>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>模型链路</h2>
          <p class="head-desc">关闭备用链路后，只使用主 Provider 的主 / 备双 Key 兜底，不再切换到下一个 Provider。</p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.fallbackEnabled.source) : '—' }}</span>
      </div>

      <div class="surface-card__body">
        <div class="setting-row">
          <div>
            <strong>启用备用链路</strong>
            <small>主 Provider 主备 Key 都失败后，按优先级继续尝试下一个 Provider</small>
          </div>
          <div class="setting-row__control">
            <el-switch v-model="llmForm.fallbackEnabled" />
          </div>
        </div>
        <div class="setting-row">
          <div>
            <strong>联网搜索</strong>
            <small>需要 DeepSeek 官方 Key（LLM_SEARCH_API_KEY 或在模型配置里填写）</small>
          </div>
          <div class="setting-row__control">
            <el-switch v-model="llmForm.searchEnabled" />
          </div>
        </div>

        <div class="card-actions">
          <span v-if="llmDirty" class="dirty-dot">有未保存的改动</span>
          <el-button type="primary" :disabled="!llmDirty" :loading="savingLlm" @click="saveLlm">
            保存并生效
          </el-button>
        </div>
      </div>
    </section>

    <section class="surface-card settings-wide">
      <div class="surface-card__head">
        <div>
          <h2>内容过滤</h2>
          <p class="head-desc">命中敏感词时改写或直接拦截，避免机器人说出不该说的话。</p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.filterMode?.source) : '—' }}</span>
      </div>

      <div class="surface-card__body">
        <div class="setting-row">
          <div>
            <strong>过滤方式</strong>
            <small>关闭：不改动；改写：用 * 遮蔽；拦截：该条回复不发送</small>
          </div>
          <div class="setting-row__control">
            <el-radio-group v-model="filterForm.mode" size="small">
              <el-radio-button label="off">关闭</el-radio-button>
              <el-radio-button label="rewrite">改写</el-radio-button>
              <el-radio-button label="block">拦截</el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <el-select
          v-model="filterForm.bannedWords"
          class="picker"
          multiple
          filterable
          allow-create
          default-first-option
          collapse-tags
          collapse-tags-tooltip
          :reserve-keyword="false"
          placeholder="输入敏感词后回车添加"
        />

        <div class="card-actions">
          <span v-if="filterDirty" class="dirty-dot">有未保存的改动</span>
          <el-button type="primary" :disabled="!filterDirty" :loading="savingFilter" @click="saveFilter">
            保存并生效
          </el-button>
        </div>
      </div>
    </section>
    </div>
  </div>
</template>

<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.75fr);
  gap: 12px;
  align-items: start;
}

.settings-wide {
  grid-column: 1 / -1;
}

.settings-grid .surface-card + .surface-card {
  margin-top: 0;
}
.picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.picker {
  width: 100%;
}

.option-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.option-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.option-id {
  color: var(--muted-soft);
  font-size: 10px;
}

.chat-list {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chat-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.chat-icon {
  display: grid;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 5px;
  color: var(--brand-deep);
  background: #eff6ff;
}

.chat-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.chat-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.chat-id {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted-soft);
  font-size: 10px;
}

.chat-empty {
  margin-top: 10px;
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: var(--radius-sm);
  text-align: center;
}

.settings-block {
  margin-top: 14px;
}

.card-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.dirty-dot {
  color: var(--warning);
  font-size: var(--fs-xs);
}

@media (max-width: 1100px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .picker-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .chat-item {
    flex-wrap: wrap;
  }

  .card-actions {
    flex-direction: column-reverse;
    align-items: stretch;
  }

  .card-actions .el-button {
    width: 100%;
  }
}
</style>




