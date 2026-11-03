<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type {
  Conversation,
  SendPolicyEffective,
  SendPolicyInput,
  SendPolicyOverview,
  SendPolicyRow,
} from '@/api/types'
import { getErrorMessage } from '@/api/client'

type DialogMode = 'default' | 'chat'

const route = useRoute()

const loading = ref(false)
const saving = ref(false)
const overview = ref<SendPolicyOverview | null>(null)
const conversations = ref<Conversation[]>([])
const dialogVisible = ref(false)
const dialogMode = ref<DialogMode>('default')
const editingChatId = ref('')
const chatInput = ref('')
const chatNameInput = ref('')

const form = reactive({
  readDelayMinMs: 1200,
  readDelayMaxMs: 7000,
  maxDelayMs: 90000,
  splitEnabled: true,
  maxParts: 3,
  maxPartChars: 90,
  perMinute: 3,
  per10Minutes: 8,
  perHour: 20,
  perDay: 120,
  minGapMs: 1200,
  emojiLimit: 1,
  dropPercent: 0,
  promptEnabled: true,
  enabled: true,
})

const defaultPolicy = computed(() => overview.value?.defaultPolicy || null)
const overrides = computed(() => overview.value?.overrides || [])

const activeConversation = computed(() =>
  conversations.value.find((item) => item.chatId === chatInput.value),
)

function seconds(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}

function policySummary(policy: SendPolicyEffective | SendPolicyRow) {
  const parts = [
    `延迟 ${seconds(policy.readDelayMinMs ?? 0)}–${seconds(policy.readDelayMaxMs ?? 0)}`,
    policy.splitEnabled === false ? '整条发送' : `最多 ${policy.maxParts ?? 3} 条气泡`,
    `${policy.perMinute ?? 0}/分钟 · ${policy.perHour ?? 0}/小时 · ${policy.perDay ?? 0}/天`,
  ]
  return parts.join(' · ')
}

function fill(policy: SendPolicyEffective | SendPolicyRow) {
  form.readDelayMinMs = policy.readDelayMinMs ?? form.readDelayMinMs
  form.readDelayMaxMs = policy.readDelayMaxMs ?? form.readDelayMaxMs
  form.maxDelayMs = policy.maxDelayMs ?? form.maxDelayMs
  form.splitEnabled = policy.splitEnabled ?? form.splitEnabled
  form.maxParts = policy.maxParts ?? form.maxParts
  form.maxPartChars = policy.maxPartChars ?? form.maxPartChars
  form.perMinute = policy.perMinute ?? form.perMinute
  form.per10Minutes = policy.per10Minutes ?? form.per10Minutes
  form.perHour = policy.perHour ?? form.perHour
  form.perDay = policy.perDay ?? form.perDay
  form.minGapMs = policy.minGapMs ?? form.minGapMs
  form.emojiLimit = policy.emojiLimit ?? form.emojiLimit
  form.dropPercent = Math.round((policy.dropRate ?? 0) * 1000) / 10
  form.promptEnabled = policy.promptEnabled ?? form.promptEnabled
  form.enabled = 'enabled' in policy ? policy.enabled : true
}

function toInput(): SendPolicyInput {
  return {
    readDelayMinMs: form.readDelayMinMs,
    readDelayMaxMs: form.readDelayMaxMs,
    maxDelayMs: form.maxDelayMs,
    splitEnabled: form.splitEnabled,
    maxParts: form.maxParts,
    maxPartChars: form.maxPartChars,
    perMinute: form.perMinute,
    per10Minutes: form.per10Minutes,
    perHour: form.perHour,
    perDay: form.perDay,
    minGapMs: form.minGapMs,
    emojiLimit: form.emojiLimit,
    dropRate: Math.round(form.dropPercent) / 100,
    promptEnabled: form.promptEnabled,
    enabled: form.enabled,
  }
}

async function load() {
  loading.value = true
  try {
    overview.value = await adminApi.sendPolicies()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadConversations() {
  try {
    const data = await adminApi.conversations({ page: 0, size: 100 })
    conversations.value = data.content
  } catch {
    /* 会话列表不可用时仍可手填 chatId */
  }
}

function openDefault() {
  if (!defaultPolicy.value) return
  dialogMode.value = 'default'
  editingChatId.value = ''
  fill(defaultPolicy.value)
  dialogVisible.value = true
}

function openNewChat() {
  dialogMode.value = 'chat'
  editingChatId.value = ''
  chatInput.value = ''
  chatNameInput.value = ''
  if (defaultPolicy.value) {
    fill(defaultPolicy.value)
  }
  dialogVisible.value = true
}

async function openEditChat(row: SendPolicyRow) {
  await openChatById(row.chatId || '', row.chatName, row)
}

/** 打开某个会话的策略弹窗：先显示已保存的值，再并入生效值。 */
async function openChatById(chatId: string, chatName: string, saved?: SendPolicyRow) {
  dialogMode.value = 'chat'
  editingChatId.value = chatId
  chatInput.value = chatId
  chatNameInput.value = chatName
  if (saved) {
    fill(saved)
    form.enabled = saved.enabled
  } else if (defaultPolicy.value) {
    fill(defaultPolicy.value)
  }
  dialogVisible.value = true
  try {
    const detail = await adminApi.chatSendPolicy(chatId)
    fill(detail.effective)
    form.enabled = detail.override ? detail.override.enabled : true
  } catch {
    /* 没有会话记录时用默认值兜底 */
  }
}

async function onChatPick(value: string) {
  chatNameInput.value = activeConversation.value?.chatName || chatNameInput.value
  if (!value) return
  try {
    const detail = await adminApi.chatSendPolicy(value)
    fill(detail.effective)
    if (detail.override) {
      form.enabled = detail.override.enabled
    }
  } catch {
    /* 手填的 chatId 可能还没有会话记录，直接用默认值 */
  }
}

async function submit() {
  if (dialogMode.value === 'chat' && !chatInput.value) {
    ElMessage.warning('请选择或粘贴要配置的会话/群')
    return
  }
  if (form.readDelayMinMs >= form.readDelayMaxMs) {
    ElMessage.warning('最长读消息延迟需要大于最短读消息延迟')
    return
  }
  saving.value = true
  try {
    if (dialogMode.value === 'default') {
      await adminApi.saveDefaultSendPolicy(toInput())
      ElMessage.success('默认策略已保存，未单独配置的会话立即生效')
    } else {
      await adminApi.saveChatSendPolicy(chatInput.value, chatNameInput.value, toInput())
      ElMessage.success('该会话的发信策略已保存')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function resetToDefault(row: SendPolicyRow) {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.chatName || row.chatId}」会立即回到默认策略，确定继续？`,
      '恢复默认策略',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteChatSendPolicy(row.chatId || '')
    ElMessage.success('已恢复默认策略')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function timeLabel(value?: string | null) {
  return value ? dayjs(value).format('MM-DD HH:mm') : '—'
}

onMounted(async () => {
  await Promise.all([load(), loadConversations()])
  const chat = typeof route.query.chat === 'string' ? route.query.chat : ''
  if (chat) {
    const name = typeof route.query.name === 'string' ? route.query.name : chat
    await openChatById(chat, name)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>发信策略</h1>
        <p>
          控制「这条回复怎么发出去」：等多久、分几条、多快、要不要洗文风。
          没单独配置的会话用默认策略；只配一个群，也只影响那个群。
        </p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="surface-card" v-loading="loading">
      <div class="surface-card__head">
        <div>
          <h2>默认策略</h2>
          <p class="head-desc">
            覆盖顺序：环境变量（<code>app.disguise.*</code>）→ 默认策略 → 按群策略；没配的项逐级往下沿用。
          </p>
        </div>
        <div class="head-actions">
          <el-button type="primary" :disabled="!defaultPolicy" @click="openDefault">编辑默认策略</el-button>
        </div>
      </div>
      <div class="surface-card__body">
        <div v-if="defaultPolicy" class="stat-grid">
          <div class="stat">
            <span class="stat-label">读消息延迟</span>
            <strong>{{ seconds(defaultPolicy.readDelayMinMs) }} – {{ seconds(defaultPolicy.readDelayMaxMs) }}</strong>
            <span class="stat-note">单条上限 {{ seconds(defaultPolicy.maxDelayMs) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">分条</span>
            <strong>{{ defaultPolicy.splitEnabled ? `最多 ${defaultPolicy.maxParts} 条` : '整条发送' }}</strong>
            <span class="stat-note">单条目标 {{ defaultPolicy.maxPartChars }} 字</span>
          </div>
          <div class="stat">
            <span class="stat-label">单会话频率</span>
            <strong>{{ defaultPolicy.perMinute }}/分钟</strong>
            <span class="stat-note">
              {{ defaultPolicy.per10Minutes }}/10 分钟 · {{ defaultPolicy.perHour }}/小时 ·
              {{ defaultPolicy.perDay }}/天
            </span>
          </div>
          <div class="stat">
            <span class="stat-label">风格</span>
            <strong>{{ defaultPolicy.promptEnabled ? '追加口语约束' : '不追加约束' }}</strong>
            <span class="stat-note">
              表情上限 {{ defaultPolicy.emojiLimit }} · 不回复概率
              {{ Math.round(defaultPolicy.dropRate * 1000) / 10 }}%
            </span>
          </div>
        </div>
        <p v-if="overview && !overview.defaultRow" class="hint">
          还没有保存过默认策略，上面显示的就是环境变量里的基线值。点「编辑默认策略」即可改。
        </p>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>按群覆盖</h2>
          <p class="head-desc">单独给某个群/客户调慢、放宽或收紧；删掉即回到默认策略</p>
        </div>
        <div class="head-actions">
          <el-button :icon="Plus" @click="openNewChat">新增按群策略</el-button>
        </div>
      </div>
      <div class="surface-card__body">
        <el-table v-if="overrides.length" :data="overrides" style="width: 100%">
          <el-table-column label="会话/群" min-width="220">
            <template #default="{ row }">
              <div class="chat-cell">
                <strong>{{ row.chatName || row.chatId }}</strong>
                <span class="mono">{{ row.chatId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '生效' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="策略" min-width="320">
            <template #default="{ row }"><span class="muted">{{ policySummary(row) }}</span></template>
          </el-table-column>
          <el-table-column label="更新时间" width="118">
            <template #default="{ row }"><span class="muted">{{ timeLabel(row.updatedAt) }}</span></template>
          </el-table-column>
          <el-table-column label="" width="150" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEditChat(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="resetToDefault(row)">恢复默认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>所有会话都用默认策略</strong>
          给某个客户群单独配一套（比如慢一点、少分条）时，再在这里加一条。
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'default' ? '编辑默认策略' : editingChatId ? '编辑按群策略' : '新增按群策略'"
      width="min(720px, 94vw)"
    >
      <el-form label-position="top">
        <el-form-item v-if="dialogMode === 'chat'" label="会话/群">
          <el-select
            v-model="chatInput"
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :disabled="Boolean(editingChatId)"
            placeholder="选择会话，或直接粘贴 wxid / @chatroom"
            class="chat-select"
            @change="onChatPick"
          >
            <el-option
              v-for="item in conversations"
              :key="item.chatId"
              :label="`${item.chatName}${item.chatType === 'GROUP' ? '（群）' : ''}`"
              :value="item.chatId"
            />
          </el-select>
        </el-form-item>

        <div class="form-grid">
          <el-form-item label="最短读消息延迟（毫秒）">
            <el-input-number v-model="form.readDelayMinMs" :min="200" :max="600000" :step="100" controls-position="right" />
          </el-form-item>
          <el-form-item label="最长读消息延迟（毫秒）">
            <el-input-number v-model="form.readDelayMaxMs" :min="201" :max="600000" :step="100" controls-position="right" />
          </el-form-item>
          <el-form-item label="单条最长延迟（毫秒）">
            <el-input-number v-model="form.maxDelayMs" :min="1000" :max="3600000" :step="1000" controls-position="right" />
          </el-form-item>
          <el-form-item label="两条之间最小间隔（毫秒）">
            <el-input-number v-model="form.minGapMs" :min="0" :max="600000" :step="100" controls-position="right" />
          </el-form-item>
        </div>

        <el-form-item label="分条发送">
          <div class="inline-form">
            <el-switch v-model="form.splitEnabled" />
            <template v-if="form.splitEnabled">
              <span class="inline-label">最多</span>
              <el-input-number v-model="form.maxParts" :min="1" :max="10" size="small" controls-position="right" />
              <span class="inline-label">条，单条约</span>
              <el-input-number v-model="form.maxPartChars" :min="20" :max="2000" :step="10" size="small" controls-position="right" />
              <span class="inline-label">字</span>
            </template>
            <span v-else class="muted">整段话一条发完</span>
          </div>
        </el-form-item>

        <div class="form-grid form-grid--four">
          <el-form-item label="每分钟">
            <el-input-number v-model="form.perMinute" :min="0" :max="600" controls-position="right" />
          </el-form-item>
          <el-form-item label="每 10 分钟">
            <el-input-number v-model="form.per10Minutes" :min="0" :max="6000" controls-position="right" />
          </el-form-item>
          <el-form-item label="每小时">
            <el-input-number v-model="form.perHour" :min="0" :max="10000" controls-position="right" />
          </el-form-item>
          <el-form-item label="每天">
            <el-input-number v-model="form.perDay" :min="0" :max="100000" controls-position="right" />
          </el-form-item>
        </div>

        <div class="form-grid">
          <el-form-item label="表情数量上限">
            <el-input-number v-model="form.emojiLimit" :min="0" :max="20" controls-position="right" />
          </el-form-item>
          <el-form-item label="不回复概率（%）">
            <el-input-number v-model="form.dropPercent" :min="0" :max="100" :step="1" controls-position="right" />
          </el-form-item>
        </div>

        <el-form-item label="向模型追加微信口语约束">
          <div class="inline-form">
            <el-switch v-model="form.promptEnabled" />
            <span class="muted">关掉后这个会话不追加「像真人打字」的约束（一般不用关）</span>
          </div>
        </el-form-item>

        <el-form-item v-if="dialogMode === 'chat'" label="启用这条按群策略">
          <div class="inline-form">
            <el-switch v-model="form.enabled" />
            <span class="muted">停用后该群立即回退到默认策略，配置会保留</span>
          </div>
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
.stat-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.stat-label {
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.stat strong {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.stat-note {
  color: var(--muted-soft);
  font-size: 10px;
}

.hint {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.chat-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-cell .mono {
  color: var(--muted-soft);
  font-size: 10px;
}

.chat-select {
  width: 100%;
}

.form-grid {
  display: grid;
  gap: 0 16px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.form-grid--four {
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
}

.inline-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.inline-label {
  color: var(--muted);
  font-size: var(--fs-xs);
}
</style>
