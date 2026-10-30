<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Refresh, User } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { ChatCandidate, SearchSettings, SearchSettingsInput, SearchTestResult, SettingsSnapshot } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const loadingCandidates = ref(false)
const savingChat = ref(false)
const savingLlm = ref(false)
const savingSearch = ref(false)
const testingSearch = ref(false)
const searchSnapshot = ref<SearchSettings | null>(null)
const searchTestResult = ref<SearchTestResult | null>(null)
const searchTestQuery = ref('今天有哪些重要新闻')
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
})

const searchForm = reactive<SearchSettingsInput>({
  enabled: true,
  mode: 'HEURISTIC',
  baseUrl: 'https://api.deepseek.com',
  apiKey: '',
  backupApiKey: '',
  clearApiKey: false,
  clearBackupApiKey: false,
  count: 5,
  needContent: false,
  maxResults: 4,
  maxContentChars: 1200,
  timeoutSeconds: 20,
})
const filterForm = reactive({
  bannedWords: [] as string[],
  mode: 'off' as 'off' | 'rewrite' | 'block',
})

const savingFilter = ref(false)

const mediaForm = reactive({
  enabled: true,
  replyMode: 'llm' as 'ignore' | 'ack' | 'llm',
  ackText: '收到，我看下～',
})

const savingMedia = ref(false)

const mediaDirty = computed(() => {
  const saved = snapshot.value
  if (!saved) return false
  return (
    saved.mediaEnabled?.value !== mediaForm.enabled ||
    saved.mediaReplyMode?.value !== mediaForm.replyMode ||
    saved.mediaAckText?.value !== mediaForm.ackText
  )
})

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
  return saved.fallbackEnabled.value !== llmForm.fallbackEnabled
})

const searchDirty = computed(() => {
  const saved = searchSnapshot.value
  if (!saved) return false
  return (
    saved.enabled !== searchForm.enabled ||
    saved.mode !== searchForm.mode ||
    saved.baseUrl !== searchForm.baseUrl ||
    saved.count !== searchForm.count ||
    saved.needContent !== searchForm.needContent ||
    saved.maxResults !== searchForm.maxResults ||
    saved.maxContentChars !== searchForm.maxContentChars ||
    saved.timeoutSeconds !== searchForm.timeoutSeconds ||
    Boolean(searchForm.apiKey?.trim()) ||
    Boolean(searchForm.backupApiKey?.trim()) ||
    searchForm.clearApiKey ||
    searchForm.clearBackupApiKey
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

function applySearchSnapshot(value: SearchSettings) {
  searchSnapshot.value = value
  Object.assign(searchForm, {
    enabled: value.enabled,
    mode: value.mode,
    baseUrl: value.baseUrl,
    apiKey: '',
    backupApiKey: '',
    clearApiKey: false,
    clearBackupApiKey: false,
    count: value.count,
    needContent: value.needContent,
    maxResults: value.maxResults,
    maxContentChars: value.maxContentChars,
    timeoutSeconds: value.timeoutSeconds,
  })
}

async function load() {
  loading.value = true
  try {
    const [data, search] = await Promise.all([adminApi.settings(), adminApi.searchSettings()])
    snapshot.value = data
    chatForm.allowedChats = [...data.allowedChats.value]
    chatForm.acceptAll = data.acceptAll.value
    chatForm.groupsEnabled = data.groupsEnabled.value
    llmForm.fallbackEnabled = data.fallbackEnabled.value
    filterForm.bannedWords = [...(data.bannedWords?.value ?? [])]
    filterForm.mode = data.filterMode?.value ?? 'off'
    mediaForm.enabled = data.mediaEnabled?.value ?? true
    mediaForm.replyMode = data.mediaReplyMode?.value ?? 'llm'
    mediaForm.ackText = data.mediaAckText?.value ?? '收到，我看下～'
    applySearchSnapshot(search)
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
    })
    ElMessage.success('模型策略已生效，无需重启')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingLlm.value = false
  }
}

async function saveSearch() {
  savingSearch.value = true
  try {
    const payload: SearchSettingsInput = {
      ...searchForm,
      apiKey: searchForm.apiKey?.trim() || undefined,
      backupApiKey: searchForm.backupApiKey?.trim() || undefined,
    }
    applySearchSnapshot(await adminApi.updateSearchSettings(payload))
    searchTestResult.value = null
    ElMessage.success('联网搜索配置已生效')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingSearch.value = false
  }
}

async function testSearch() {
  testingSearch.value = true
  try {
    searchTestResult.value = await adminApi.testSearchSettings(searchTestQuery.value)
    if (searchTestResult.value.success) {
      ElMessage.success(`搜索成功，返回 ${searchTestResult.value.resultCount} 条结果`)
    } else {
      ElMessage.error(searchTestResult.value.message)
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    testingSearch.value = false
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

async function saveMedia() {
  savingMedia.value = true
  try {
    snapshot.value = await adminApi.updateMediaPolicy({
      enabled: mediaForm.enabled,
      replyMode: mediaForm.replyMode,
      ackText: mediaForm.ackText,
    })
    ElMessage.success('媒体消息策略已生效')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingMedia.value = false
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
          <h2>联网搜索</h2>
          <p class="head-desc">独立配置搜索 Base URL、主 / 备 Key 和结果参数，不复用任何聊天模型 Provider 的地址或密钥。</p>
        </div>
        <span class="count-badge">{{ searchForm.enabled ? '已启用' : '已关闭' }}</span>
      </div>

      <div class="surface-card__body">
        <div class="setting-row">
          <div>
            <strong>启用联网搜索</strong>
            <small>关闭后不会触发搜索，也不会读取搜索 Key；聊天模型仍正常工作</small>
          </div>
          <div class="setting-row__control">
            <el-switch v-model="searchForm.enabled" />
          </div>
        </div>

        <div class="search-config-grid">
          <el-form label-position="top" class="search-form">
            <div class="form-grid">
              <el-form-item label="搜索模式">
                <el-radio-group v-model="searchForm.mode" size="small">
                  <el-radio-button label="OFF">关闭</el-radio-button>
                  <el-radio-button label="HEURISTIC">按需触发</el-radio-button>
                  <el-radio-button label="ALWAYS">总是搜索</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="搜索 Base URL">
                <el-input v-model="searchForm.baseUrl" placeholder="https://api.deepseek.com" />
              </el-form-item>
            </div>
            <div class="form-grid">
              <el-form-item label="搜索 API Key">
                <el-input
                  v-model="searchForm.apiKey"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  :disabled="searchForm.clearApiKey"
                  :placeholder="searchForm.clearApiKey ? '将清除现有搜索 Key' : searchSnapshot?.hasApiKey ? '留空表示保持现有搜索 Key' : '搜索专用 Key，例如 sk-...'"
                />
              </el-form-item>
              <el-form-item label="搜索备用 API Key">
                <el-input
                  v-model="searchForm.backupApiKey"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  :disabled="searchForm.clearBackupApiKey"
                  :placeholder="searchForm.clearBackupApiKey ? '将清除现有备用搜索 Key' : searchSnapshot?.hasBackupApiKey ? '留空表示保持现有备用 Key' : '可选'"
                />
              </el-form-item>
            </div>
            <div class="search-key-status">
              <span>
                主 Key：
                <code v-if="searchSnapshot?.hasApiKey">{{ searchSnapshot.apiKeyHint }}</code>
                <b v-else>未配置</b>
                <el-button
                  v-if="searchSnapshot?.hasApiKey && !searchForm.clearApiKey"
                  text
                  type="danger"
                  size="small"
                  @click="searchForm.clearApiKey = true; searchForm.apiKey = ''"
                >
                  清除
                </el-button>
                <el-button v-else-if="searchForm.clearApiKey" text size="small" @click="searchForm.clearApiKey = false">
                  撤销
                </el-button>
              </span>
              <span>
                备用 Key：
                <code v-if="searchSnapshot?.hasBackupApiKey">{{ searchSnapshot.backupApiKeyHint }}</code>
                <b v-else>未配置</b>
                <el-button
                  v-if="searchSnapshot?.hasBackupApiKey && !searchForm.clearBackupApiKey"
                  text
                  type="danger"
                  size="small"
                  @click="searchForm.clearBackupApiKey = true; searchForm.backupApiKey = ''"
                >
                  清除
                </el-button>
                <el-button v-else-if="searchForm.clearBackupApiKey" text size="small" @click="searchForm.clearBackupApiKey = false">
                  撤销
                </el-button>
              </span>
            </div>
          </el-form>

          <el-form label-position="top" class="search-form">
            <div class="form-grid three">
              <el-form-item label="请求条数">
                <el-input-number v-model="searchForm.count" :min="1" :max="20" controls-position="right" />
              </el-form-item>
              <el-form-item label="最多采用结果">
                <el-input-number v-model="searchForm.maxResults" :min="1" :max="10" controls-position="right" />
              </el-form-item>
              <el-form-item label="单条内容上限">
                <el-input-number v-model="searchForm.maxContentChars" :min="100" :max="10000" :step="100" controls-position="right" />
              </el-form-item>
            </div>
            <div class="form-grid">
              <el-form-item label="超时时间（秒）">
                <el-input-number v-model="searchForm.timeoutSeconds" :min="5" :max="120" controls-position="right" />
              </el-form-item>
              <el-form-item label="返回网页正文">
                <el-switch v-model="searchForm.needContent" active-text="需要" inactive-text="仅摘要" />
              </el-form-item>
            </div>
            <div class="search-test-row">
              <el-input v-model="searchTestQuery" placeholder="测试搜索关键词" @keyup.enter="testSearch" />
              <el-button :disabled="searchDirty" :loading="testingSearch" @click="testSearch">测试搜索</el-button>
            </div>
            <div v-if="searchDirty" class="field-hint">修改后请先保存，再测试搜索。</div>
            <div v-if="searchTestResult" class="search-test-result" :class="{ failed: !searchTestResult.success }">
              {{ searchTestResult.success ? `成功：${searchTestResult.resultCount} 条结果，${searchTestResult.latencyMs} ms` : `失败：${searchTestResult.message}` }}
            </div>
          </el-form>
        </div>

        <div class="card-actions">
          <span v-if="searchDirty" class="dirty-dot">有未保存的改动</span>
          <el-button type="primary" :disabled="!searchDirty" :loading="savingSearch" @click="saveSearch">
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

    <section class="surface-card settings-wide">
      <div class="surface-card__head">
        <div>
          <h2>媒体消息</h2>
          <p class="head-desc">图片、语音、文件、链接的处理方式。图片会解码后直接交给模型看，语音、文件、链接仍只有占位文本。</p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.mediaReplyMode?.source) : '—' }}</span>
      </div>

      <div class="surface-card__body">
        <div class="setting-row">
          <div>
            <strong>接收并记录媒体消息</strong>
            <small>关闭后图片/语音/文件直接丢弃，不写会话记录</small>
          </div>
          <div class="setting-row__control">
            <el-switch v-model="mediaForm.enabled" />
          </div>
        </div>

        <div class="setting-row">
          <div>
            <strong>回复策略</strong>
            <small>只记录：不回复；固定话术：回一句确认；交给模型：文本类占位（如「[语音 4″]」）交给模型回应，图片则连图一起发（需模型支持图片输入，否则自动降级为纯文本）</small>
          </div>
          <div class="setting-row__control">
            <el-radio-group v-model="mediaForm.replyMode" size="small" :disabled="!mediaForm.enabled">
              <el-radio-button label="ignore">只记录</el-radio-button>
              <el-radio-button label="ack">固定话术</el-radio-button>
              <el-radio-button label="llm">交给模型</el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <el-input
          v-model="mediaForm.ackText"
          class="picker"
          maxlength="500"
          :disabled="!mediaForm.enabled || mediaForm.replyMode !== 'ack'"
          placeholder="固定话术内容，例如：收到，我看下～"
        />

        <div class="card-actions">
          <span v-if="mediaDirty" class="dirty-dot">有未保存的改动</span>
          <el-button type="primary" :disabled="!mediaDirty" :loading="savingMedia" @click="saveMedia">
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
.search-config-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
  gap: 12px;
  margin-top: 8px;
}

.search-form {
  padding: 10px 12px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.search-key-status {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  color: var(--muted);
  font-size: 10px;
}

.search-key-status span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.search-key-status code {
  color: var(--ink-soft);
  font-size: 10px;
}

.search-key-status b {
  color: var(--muted-soft);
  font-weight: 500;
}

.search-test-row {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}

.search-test-row .el-input {
  flex: 1;
}

.search-test-result {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 4px;
  color: #15803d;
  background: #f0fdf4;
  font-size: 10px;
}

.search-test-result.failed {
  color: #b91c1c;
  background: #fef2f2;
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
  .search-config-grid {
    grid-template-columns: 1fr;
  }

  .settings-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .search-config-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
  gap: 12px;
  margin-top: 8px;
}

.search-form {
  padding: 10px 12px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.search-key-status {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  color: var(--muted);
  font-size: 10px;
}

.search-key-status span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.search-key-status code {
  color: var(--ink-soft);
  font-size: 10px;
}

.search-key-status b {
  color: var(--muted-soft);
  font-weight: 500;
}

.search-test-row {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}

.search-test-row .el-input {
  flex: 1;
}

.search-test-result {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 4px;
  color: #15803d;
  background: #f0fdf4;
  font-size: 10px;
}

.search-test-result.failed {
  color: #b91c1c;
  background: #fef2f2;
}
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












