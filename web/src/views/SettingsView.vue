<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Refresh, User } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type {
  ChatCandidate,
  ChatKind,
  SearchSettings,
  SearchSettingsInput,
  SearchTestResult,
  SettingsSnapshot,
} from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const savingChat = ref(false)
const savingLlm = ref(false)
const savingSearch = ref(false)
const testingSearch = ref(false)
const searchSnapshot = ref<SearchSettings | null>(null)
const searchTestResult = ref<SearchTestResult | null>(null)
const searchTestQuery = ref('今天有哪些重要新闻')
const snapshot = ref<SettingsSnapshot | null>(null)

/** 白名单搜索：按好友昵称 / 备注、或群名称搜索，再勾选加入 */
const searchKind = ref<'private' | 'groups'>('private')
const searchKeyword = ref('')
const searchResults = ref<ChatCandidate[]>([])
const searchLoading = ref(false)
const searchError = ref('')
const searched = ref(false)
const picked = ref<string[]>([])
const manualInput = ref('')
/** chatId → 名称，搜索到过的会话都记下来，白名单列表里就能显示中文名 */
const chatNames = ref<Record<string, string>>({})

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

const privateChats = computed(() =>
  chatForm.allowedChats.filter((chatId) => !isGroupChat(chatId) && !looksNonHuman(chatId)),
)
const groupChats = computed(() => chatForm.allowedChats.filter(isGroupChat))
/** 历史白名单里可能残留公众号 / 机器人：后端不会回复，这里单独列出来方便清理 */
const staleChats = computed(() => chatForm.allowedChats.filter(looksNonHuman))

function isGroupChat(chatId: string) {
  return chatId.endsWith('@chatroom')
}

function nameOf(chatId: string) {
  return chatNames.value[chatId] || chatId
}

function kindLabel(kind: ChatKind) {
  switch (kind) {
    case 'GROUP':
      return '群聊'
    case 'PRIVATE':
      return '私聊'
    case 'OFFICIAL':
      return '公众号 / 机器人'
    default:
      return '系统会话'
  }
}

/** 公众号 / 机器人 / 系统会话永远不回复：前端先挡一道，后端还有同样的判断 */
function looksNonHuman(chatId: string) {
  const id = chatId.trim().toLowerCase()
  return (
    id.startsWith('gh_') ||
    id.startsWith('wxid_wi_') ||
    id.endsWith('@weclaw') ||
    id.endsWith('@openim') ||
    id.endsWith('@kf') ||
    id.endsWith('_officialaccounts') ||
    ['filehelper', 'weixin', 'fmessage', 'medianote', 'newsapp', 'qqmail', 'qmessage', 'tmessage', 'notifymessage'].includes(id)
  )
}

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
/** 按昵称 / 备注 / 群名称搜索可加入白名单的会话。 */
async function runSearch(notify = false) {
  searchLoading.value = true
  searchError.value = ''
  try {
    const rows = await adminApi.searchChats({
      kind: searchKind.value,
      keyword: searchKeyword.value.trim(),
      limit: 200,
    })
    searchResults.value = rows
    searched.value = true
    rows.forEach((row) => {
      chatNames.value[row.chatId] = row.name
    })
    if (notify) {
      ElMessage.success(rows.length ? `找到 ${rows.length} 个会话` : '没有找到匹配的会话')
    }
  } catch (error) {
    searchResults.value = []
    searchError.value = getErrorMessage(error)
  } finally {
    searchLoading.value = false
  }
}

function togglePick(chatId: string) {
  // 整行都可以点：公众号 / 已在白名单的行点不动，避免误选
  const item = searchResults.value.find((row) => row.chatId === chatId)
  if (!item || item.blocked || chatForm.allowedChats.includes(chatId)) return
  picked.value = picked.value.includes(chatId)
    ? picked.value.filter((entry) => entry !== chatId)
    : [...picked.value, chatId]
}

async function addPicked() {
  const existing = new Set(chatForm.allowedChats)
  const added = picked.value.filter((chatId) => !existing.has(chatId))
  chatForm.allowedChats = [...chatForm.allowedChats, ...added]
  picked.value = []
  if (!added.length) return
  const saved = await persistChat(`已加入白名单并立即生效（${added.length} 个会话）`)
  if (!saved) return
  await loadNames()
}

function addManual() {
  const parts = manualInput.value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  if (!parts.length) return
  const existing = new Set(chatForm.allowedChats)
  const added: string[] = []
  const rejected: string[] = []
  for (const chatId of parts) {
    if (looksNonHuman(chatId)) {
      rejected.push(chatId)
      continue
    }
    if (searchKind.value === 'groups' && !isGroupChat(chatId)) {
      rejected.push(chatId)
      continue
    }
    if (searchKind.value === 'private' && isGroupChat(chatId)) {
      rejected.push(chatId)
      continue
    }
    if (existing.has(chatId)) continue
    existing.add(chatId)
    added.push(chatId)
  }
  chatForm.allowedChats = [...chatForm.allowedChats, ...added]
  manualInput.value = ''
  if (added.length) {
    void persistChat(`已加入白名单并立即生效（${added.length} 个会话）`).then((saved) => {
      if (saved) void loadNames()
    })
  }
  if (rejected.length) {
    ElMessage.warning(`已跳过 ${rejected.join('、')}：公众号 / 机器人 / 系统会话不会回复，或 ID 类型与当前搜索不匹配`)
  }
}

/** 载入两类会话的名称，让已有白名单条目也能显示中文名；Hook 不可用时后端会退回本地会话库。 */
async function loadNames() {
  for (const kind of ['private', 'groups'] as const) {
    try {
      const rows = await adminApi.searchChats({ kind, keyword: '', limit: 500 })
      rows.forEach((row) => {
        chatNames.value[row.chatId] = row.name
      })
    } catch {
      /* 名称只用于展示，失败不影响白名单编辑 */
    }
  }
}

/** 微信通讯录读不出来时（加密库 + Hook 查不了库），让用户自己给会话起名字。 */
async function editAlias(item: ChatCandidate) {
  try {
    const { value } = await ElMessageBox.prompt(
      '给这个会话起个名字，之后搜名字或备注都能找到它；留空表示删除备注。',
      item.group ? '设置群备注' : '设置好友备注',
      {
        inputValue: item.name === item.chatId ? '' : item.name,
        inputPlaceholder: item.group ? '例如：家人群' : '例如：张三',
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (input: string) => (input ?? '').length <= 60 || '名字最多 60 个字',
      },
    )
    const saved = await adminApi.saveChatAlias(item.chatId, (value ?? '').trim())
    chatNames.value[item.chatId] = saved.name || item.chatId
    await runSearch()
    ElMessage.success(saved.name ? '备注已保存' : '备注已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getErrorMessage(error))
  }
}

/** 把白名单 + 两个开关写回后端；加会话、删会话都会自动调它，不用再单独点保存。 */
async function persistChat(successMessage = '会话策略已生效，无需重启') {
  savingChat.value = true
  try {
    snapshot.value = await adminApi.updateWeChatPolicy({
      groupsEnabled: chatForm.groupsEnabled,
      acceptAll: chatForm.acceptAll,
      allowedChats: chatForm.allowedChats,
    })
    ElMessage.success(successMessage)
    return true
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    return false
  } finally {
    savingChat.value = false
  }
}

async function saveChat() {
  await persistChat()
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
  picked.value = picked.value.filter((item) => item !== chatId)
  void persistChat('已移出白名单并立即生效')
}

function sourceLabel(source?: string) {
  return source === 'db' ? '数据库' : '环境变量'
}

onMounted(async () => {
  await load()
  await loadNames()
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
          <p class="head-desc">
            只处理两类会话：<strong>群聊</strong>和<strong>真人私聊</strong>。
            其它（公众号 / 订阅号 / 机器人 / 系统会话）一律忽略。
            按昵称 / 备注搜好友、按群名称搜群聊，勾选后加入。
          </p>
        </div>
        <span class="count-badge">{{ snapshot ? sourceLabel(snapshot.allowedChats.source) : '—' }}</span>
      </div>

      <div class="surface-card__body">
        <div class="policy-note">
          只保留<strong>群聊</strong>和<strong>真人私聊</strong>两种会话：微信系统号、公众号（服务号 / 订阅号）、
          机器人（如微信 AI 助手）<strong>一律忽略</strong>，即使写进白名单也不会回复。
        </div>

        <div class="policy-split">
          <section class="policy-panel">
            <header class="policy-panel__head">
              <h3>私聊白名单 <span class="count-badge">{{ privateChats.length }}</span></h3>
              <small>{{ privateChats.length ? '只回复名单内的好友' : '名单为空，按「全量接收私聊」开关处理' }}</small>
            </header>
            <ul v-if="privateChats.length" class="chat-list">
              <li v-for="chatId in privateChats" :key="chatId" class="chat-item">
                <span class="chat-icon">
                  <el-icon><User /></el-icon>
                </span>
                <span class="chat-copy">
                  <strong>{{ nameOf(chatId) }}</strong>
                  <span v-if="nameOf(chatId) !== chatId" class="mono chat-id">{{ chatId }}</span>
                </span>
                <el-button text type="danger" size="small" @click="removeChat(chatId)">移除</el-button>
              </li>
            </ul>
            <div v-else class="chat-empty hint">还没有私聊白名单</div>
          </section>

          <section class="policy-panel">
            <header class="policy-panel__head">
              <h3>群聊白名单 <span class="count-badge">{{ groupChats.length }}</span></h3>
              <small>{{ groupChats.length ? '名单内的群，命中唤醒词才回复' : '群聊不做全量接收，为空即不回复任何群' }}</small>
            </header>
            <ul v-if="groupChats.length" class="chat-list">
              <li v-for="chatId in groupChats" :key="chatId" class="chat-item">
                <span class="chat-icon">
                  <el-icon><ChatDotRound /></el-icon>
                </span>
                <span class="chat-copy">
                  <strong>{{ nameOf(chatId) }}</strong>
                  <span v-if="nameOf(chatId) !== chatId" class="mono chat-id">{{ chatId }}</span>
                </span>
                <el-button text type="danger" size="small" @click="removeChat(chatId)">移除</el-button>
              </li>
            </ul>
            <div v-else class="chat-empty hint">还没有群聊白名单</div>
          </section>
        </div>

        <div v-if="staleChats.length" class="stale-block">
          <div class="stale-block__head">
            <strong>白名单里有 {{ staleChats.length }} 个不会回复的会话</strong>
            <small>公众号、机器人、系统会话永远不会自动回复，建议移除以保持名单干净。</small>
          </div>
          <ul class="chat-list">
            <li v-for="chatId in staleChats" :key="chatId" class="chat-item">
              <span class="chat-copy">
                <strong>{{ nameOf(chatId) }}</strong>
                <span v-if="nameOf(chatId) !== chatId" class="mono chat-id">{{ chatId }}</span>
              </span>
              <el-tag type="danger" effect="plain" size="small">不会回复</el-tag>
              <el-button text type="danger" size="small" @click="removeChat(chatId)">移除</el-button>
            </li>
          </ul>
        </div>

        <div class="chat-search">
          <el-radio-group v-model="searchKind" size="small">
            <el-radio-button label="private">搜好友</el-radio-button>
            <el-radio-button label="groups">搜群聊</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="searchKeyword"
            class="chat-search__input"
            clearable
            :placeholder="searchKind === 'groups' ? '输入群名称，例如：项目群' : '输入昵称或备注，例如：张三'"
            @keyup.enter="runSearch(true)"
          />
          <el-button :loading="searchLoading" :icon="Refresh" @click="runSearch(true)">搜索</el-button>
          <el-button type="primary" :disabled="!picked.length" @click="addPicked">
            添加所选{{ picked.length ? `（${picked.length}）` : '' }}
          </el-button>
        </div>

        <div v-if="searchError" class="inline-error">{{ searchError }}</div>

        <div v-if="searchResults.length" class="hint">
          勾选结果（也可以直接点整行）后点「添加所选」会立刻写入数据库并生效，不需要再点保存。
        </div>

        <ul v-if="searchResults.length" class="candidate-list">
          <li
            v-for="item in searchResults"
            :key="item.chatId"
            class="candidate-item"
            :class="{
              'is-blocked': item.blocked,
              'is-added': chatForm.allowedChats.includes(item.chatId),
              'is-picked': picked.includes(item.chatId),
            }"
            @click="togglePick(item.chatId)"
          >
            <el-checkbox
              class="candidate-check"
              :model-value="picked.includes(item.chatId)"
              :disabled="item.blocked || chatForm.allowedChats.includes(item.chatId)"
            />
            <span class="candidate-copy">
              <strong>{{ item.name }}</strong>
              <span v-if="item.name !== item.chatId" class="mono chat-id">{{ item.chatId }}</span>
              <span v-if="item.lastMessage" class="candidate-last">{{ item.lastMessage }}</span>
            </span>
            <el-tag :type="item.group ? 'warning' : 'info'" effect="plain" size="small">
              {{ kindLabel(item.kind) }}
            </el-tag>
            <span v-if="item.blocked" class="candidate-note">{{ item.blockedReason }}</span>
            <span v-else-if="chatForm.allowedChats.includes(item.chatId)" class="candidate-note">已在白名单</span>
            <el-button text size="small" :disabled="item.blocked" @click.stop="editAlias(item)">
              {{ item.name === item.chatId ? '设备注' : '改备注' }}
            </el-button>
          </li>
        </ul>
        <div v-else-if="searched && !searchLoading" class="chat-empty hint">
          没有找到匹配的{{ searchKind === 'groups' ? '群聊' : '好友' }}，换一个关键词试试。
        </div>

        <div class="manual-add">
          <el-input
            v-model="manualInput"
            class="chat-search__input"
            :placeholder="searchKind === 'groups' ? '也可以粘贴群 ID（@chatroom 结尾），多个用逗号分隔' : '也可以粘贴 wxid，多个用逗号分隔'"
            @keyup.enter="addManual"
          />
          <el-button @click="addManual">手动添加</el-button>
        </div>

        <div class="settings-block">
          <div class="setting-row">
            <div>
              <strong>全量接收私聊</strong>
              <small>私聊白名单为空时，是否接收所有私聊（公众号 / 机器人 / 系统会话除外）</small>
            </div>
            <div class="setting-row__control">
              <el-switch v-model="chatForm.acceptAll" />
            </div>
          </div>
          <div class="setting-row">
            <div>
              <strong>处理群聊消息</strong>
              <small>关闭时群消息直接忽略；开启后只有群聊白名单里的群、且命中唤醒词才会回复</small>
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
.policy-note {
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid #fde68a;
  border-radius: var(--radius-sm);
  color: #92400e;
  background: #fffbeb;
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.policy-note strong {
  color: #b45309;
}

.policy-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
}

.policy-panel {
  padding: 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.policy-panel__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.policy-panel__head h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.policy-panel__head small {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.5;
}

.stale-block {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid #fecaca;
  border-radius: var(--radius-sm);
  background: #fef2f2;
}

.stale-block__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stale-block__head strong {
  color: #b91c1c;
  font-size: var(--fs-sm);
  font-weight: 600;
}

.stale-block__head small {
  color: #b91c1c;
  font-size: 10px;
  line-height: 1.5;
}

.candidate-last {
  display: block;
  max-width: 100%;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.chat-search__input {
  flex: 1;
  min-width: 180px;
}

.manual-add {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.inline-error {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  color: #b91c1c;
  background: #fef2f2;
  font-size: 10px;
  line-height: 1.5;
}

.candidate-list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow: auto;
}

.candidate-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fff;
  cursor: pointer;
}

/* 整行可点选，勾选框只做视觉提示，避免“点了没反应” */
.candidate-check {
  pointer-events: none;
}

.candidate-item.is-picked {
  border-color: var(--brand);
  background: #f2f7ff;
}

.candidate-item.is-added {
  background: #f8fafc;
}

.candidate-item.is-blocked {
  background: #f8fafc;
  opacity: 0.75;
}

.candidate-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.candidate-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.candidate-note {
  color: var(--muted);
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
  background: #fff;
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

  .policy-split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .chat-search,
  .manual-add {
    flex-direction: column;
    align-items: stretch;
  }

  .chat-search__input {
    width: 100%;
  }

  .chat-item,
  .candidate-item {
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












