<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type {
  ChatChoice,
  ReplyMode,
  ReplyPolicyEffective,
  ReplyPolicyOverview,
  ReplyPolicyRow,
} from '@/api/types'
import { getErrorMessage } from '@/api/client'
import ChatSelect from '@/components/ChatSelect.vue'

const route = useRoute()

const modeOptions: Array<{ value: ReplyMode; label: string; tip: string }> = [
  {
    value: 'ALWAYS',
    label: '主动回复',
    tip: '群里每条消息都当话题参与。适合陪聊群、客服群，注意别在不相关的群里开。',
  },
  {
    value: 'RANDOM',
    label: '随机回复',
    tip: '被 @ 或命中唤醒词一定回；其余消息按概率插话，避免每条都接话显得不像人。',
  },
  {
    value: 'WAKE',
    label: '关键词唤醒',
    tip: '只有消息里出现唤醒词才回，其余只记录不回复。最省 token，也最不打扰。',
  },
  {
    value: 'HUMAN',
    label: '拟人回复',
    tip: '把「要不要回、回什么」都交给模型判断：它会像群成员一样自己决定不接、接一句或接几句。最像人，也最费 token——判定按冷却时间节流，冷却期内的消息只入库不判定。',
  },
]

const loading = ref(false)
const savingDefault = ref(false)
const saving = ref(false)
const overview = ref<ReplyPolicyOverview | null>(null)
const dialogVisible = ref(false)
const editingChatId = ref('')
const chatInput = ref('')
const chatNameInput = ref('')

const defaultForm = reactive({
  mode: 'WAKE' as ReplyMode,
  wakeWords: '',
  randomPercent: 30,
  humanCooldown: 60,
})
const chatForm = reactive({
  mode: 'WAKE' as ReplyMode,
  wakeWords: '',
  randomPercent: 30,
  humanCooldown: 60,
  enabled: true,
})

const overrides = computed(() => overview.value?.overrides || [])
const effective = computed(() => overview.value?.defaultPolicy || null)
/** 正在编辑的那个群实际生效的策略，只用于弹窗里的提示，不参与回填 */
const chatEffective = ref<ReplyPolicyEffective | null>(null)

const modeLabels: Record<ReplyMode, string> = {
  ALWAYS: '主动回复',
  RANDOM: '随机回复',
  WAKE: '关键词唤醒',
  HUMAN: '拟人回复',
}

const sourceLabels: Record<string, string> = {
  bot: '机器人配置',
  default: '默认策略',
  chat: '按群策略',
}

function modeLabel(mode?: ReplyMode | null) {
  return mode ? modeLabels[mode] : '继承'
}

function percent(rate?: number | null) {
  return `${Math.round((rate ?? 0) * 100)}%`
}

function policySummary(row: ReplyPolicyRow) {
  if (row.mode === 'RANDOM') return `随机 ${percent(row.randomRate)}`
  if (row.mode === 'WAKE') return row.wakeWords ? `唤醒：${row.wakeWords}` : '唤醒词继承默认'
  if (row.mode === 'ALWAYS') return '每条都回'
  if (row.mode === 'HUMAN') {
    return row.humanCooldownSeconds === null || row.humanCooldownSeconds === undefined
      ? '模型判断 · 间隔继承默认'
      : `模型判断 · 间隔 ${row.humanCooldownSeconds} 秒`
  }
  return '模式继承默认'
}

function timeLabel(value?: string | null) {
  return value ? dayjs(value).format('MM-DD HH:mm') : '—'
}

async function load() {
  loading.value = true
  try {
    overview.value = await adminApi.replyPolicies()
    const policy = overview.value.defaultPolicy
    defaultForm.mode = policy.mode
    defaultForm.wakeWords = policy.wakeWords
    defaultForm.randomPercent = Math.round(policy.randomRate * 100)
    defaultForm.humanCooldown = policy.humanCooldownSeconds
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function saveDefault() {
  if (defaultForm.mode === 'WAKE' && !defaultForm.wakeWords.trim()) {
    ElMessage.warning('关键词唤醒模式需要至少填一个唤醒词')
    return
  }
  savingDefault.value = true
  try {
    await adminApi.saveDefaultReplyPolicy({
      mode: defaultForm.mode,
      wakeWords: defaultForm.wakeWords.trim(),
      randomRate: defaultForm.randomPercent / 100,
      humanCooldownSeconds: defaultForm.humanCooldown,
    })
    ElMessage.success('默认回复策略已保存，没单独配置的群立即生效')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingDefault.value = false
  }
}

function openNewChat() {
  editingChatId.value = ''
  chatInput.value = ''
  chatNameInput.value = ''
  chatEffective.value = effective.value
  chatForm.mode = effective.value?.mode || 'WAKE'
  chatForm.wakeWords = effective.value?.wakeWords || ''
  chatForm.randomPercent = Math.round((effective.value?.randomRate ?? 0.3) * 100)
  chatForm.humanCooldown = effective.value?.humanCooldownSeconds ?? 60
  chatForm.enabled = true
  dialogVisible.value = true
}

/**
 * 编辑按群策略：只回显这条策略自己存过的值。
 * 之前用「默认策略」的值兜底，导致只改了模式的群看起来像把唤醒词也写死了。
 */
function openEditChat(row: ReplyPolicyRow) {
  editingChatId.value = row.chatId || ''
  chatInput.value = row.chatId || ''
  chatNameInput.value = row.chatName
  const fallbackMode = effective.value?.mode || 'WAKE'
  const mode = row.mode || fallbackMode
  chatForm.mode = mode
  // 唤醒模式下后端要求必须有关键词，所以继承默认词；其他模式留空表示继承
  chatForm.wakeWords = row.wakeWords ?? (mode === 'WAKE' ? effective.value?.wakeWords || '' : '')
  chatForm.randomPercent = Math.round((row.randomRate ?? effective.value?.randomRate ?? 0.3) * 100)
  chatForm.humanCooldown = row.humanCooldownSeconds ?? effective.value?.humanCooldownSeconds ?? 60
  chatForm.enabled = row.enabled
  dialogVisible.value = true
  void loadChatEffective(row.chatId || '')
}

async function loadChatEffective(chatId: string) {
  if (!chatId) {
    chatEffective.value = effective.value
    return
  }
  try {
    chatEffective.value = (await adminApi.chatReplyPolicy(chatId)).effective
  } catch {
    chatEffective.value = effective.value
  }
}

async function onChatPick(picked: ChatChoice | string | null) {
  const conversation = typeof picked === 'string' ? null : picked
  const value = chatInput.value
  if (conversation) {
    chatNameInput.value = conversation.chatName
  }
  chatEffective.value = effective.value
  if (!value) return
  try {
    const detail = await adminApi.chatReplyPolicy(value)
    chatEffective.value = detail.effective
    if (detail.override) {
      // 已经有按群策略：按它自己的值回显
      const mode = detail.override.mode || detail.effective.mode
      chatForm.mode = mode
      chatForm.wakeWords = detail.override.wakeWords
        ?? (mode === 'WAKE' ? detail.effective.wakeWords : '')
      chatForm.randomPercent = Math.round(
        (detail.override.randomRate ?? detail.effective.randomRate) * 100,
      )
      chatForm.humanCooldown = detail.override.humanCooldownSeconds
        ?? detail.effective.humanCooldownSeconds
      chatForm.enabled = detail.override.enabled
    } else {
      chatForm.mode = detail.effective.mode
      chatForm.wakeWords = detail.effective.mode === 'WAKE' ? detail.effective.wakeWords : ''
      chatForm.randomPercent = Math.round(detail.effective.randomRate * 100)
      chatForm.humanCooldown = detail.effective.humanCooldownSeconds
    }
  } catch {
    /* 手填的 chatId 可能还没有会话记录，用默认值兜底 */
  }
}

async function submit() {
  if (!chatInput.value) {
    ElMessage.warning('请选择或粘贴要配置的群')
    return
  }
  if (chatForm.mode === 'WAKE' && !chatForm.wakeWords.trim()) {
    ElMessage.warning('关键词唤醒模式需要至少填一个唤醒词')
    return
  }
  saving.value = true
  try {
    await adminApi.saveChatReplyPolicy(chatInput.value, chatNameInput.value, {
      mode: chatForm.mode,
      wakeWords: chatForm.wakeWords.trim(),
      randomRate: chatForm.randomPercent / 100,
      humanCooldownSeconds: chatForm.humanCooldown,
      enabled: chatForm.enabled,
    })
    ElMessage.success('该群的回复策略已保存')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function resetToDefault(row: ReplyPolicyRow) {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.chatName || row.chatId}」会立即回到默认回复策略，确定继续？`,
      '恢复默认策略',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteChatReplyPolicy(row.chatId || '')
    ElMessage.success('已恢复默认回复策略')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

onMounted(async () => {
  await load()
  const chat = typeof route.query.chat === 'string' ? route.query.chat : ''
  if (chat) {
    openNewChat()
    chatInput.value = chat
    chatNameInput.value = typeof route.query.name === 'string' ? route.query.name : chat
    await onChatPick(chat)
    editingChatId.value = chat
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>回复策略</h1>
        <p>
          决定「这个群要不要回、什么时候回」：主动接话、随机插话，还是只有被叫到才回。
          没单独配置的群用默认策略；只调一个群，也只影响那个群。
        </p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="surface-card" v-loading="loading">
      <div class="surface-card__head">
        <div>
          <h2>默认策略</h2>
          <p class="head-desc">
            覆盖顺序：机器人配置的唤醒词（<code>groupWakeWords</code>）→ 默认策略 → 按群策略；没配的项逐级往下沿用。
          </p>
        </div>
        <div class="head-actions">
          <el-button type="primary" :loading="savingDefault" @click="saveDefault">保存默认策略</el-button>
        </div>
      </div>
      <div class="surface-card__body">
        <el-radio-group v-model="defaultForm.mode" class="mode-group">
          <el-radio-button v-for="option in modeOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>

        <div class="mode-row">
          <template v-if="defaultForm.mode === 'WAKE'">
            <span class="field-label">唤醒词</span>
            <el-input
              v-model="defaultForm.wakeWords"
              class="field-input"
              placeholder="多个用逗号分隔，例如：@机器人,小助手"
            />
          </template>
          <template v-else-if="defaultForm.mode === 'RANDOM'">
            <span class="field-label">随机命中概率</span>
            <el-slider v-model="defaultForm.randomPercent" :min="0" :max="100" :step="5" class="field-slider" />
            <span class="field-value">{{ defaultForm.randomPercent }}%</span>
          </template>
          <template v-else-if="defaultForm.mode === 'HUMAN'">
            <span class="field-label">判定间隔</span>
            <el-input-number v-model="defaultForm.humanCooldown" :min="0" :max="3600" :step="10" controls-position="right" />
            <span class="field-label">秒（间隔内只记录、不判定）</span>
          </template>
          <span v-else class="muted">每条消息都接话。</span>
        </div>

        <p class="mode-tip">{{ modeOptions.find((item) => item.value === defaultForm.mode)?.tip }}</p>

        <p v-if="effective" class="hint">
          当前生效：<b>{{ modeLabel(effective.mode) }}</b>
          <template v-if="effective.mode === 'WAKE'">（唤醒词 {{ effective.wakeWords || '未设置' }}）</template>
          <template v-else-if="effective.mode === 'RANDOM'">（{{ percent(effective.randomRate) }}）</template>
          <template v-else-if="effective.mode === 'HUMAN'">（判定间隔 {{ effective.humanCooldownSeconds }} 秒）</template>
          · 来自 {{ sourceLabels[effective.source] || effective.source }}
          <template v-if="overview && !overview.defaultRow">
            · 还没保存过默认策略，这里显示的是机器人配置的基线
          </template>
        </p>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>按群策略</h2>
          <p class="head-desc">单独给某个群换一种回法；删掉即回到默认策略，也可以只停用而保留配置</p>
        </div>
        <div class="head-actions">
          <el-button :icon="Plus" @click="openNewChat">新增按群策略</el-button>
        </div>
      </div>
      <div class="surface-card__body">
        <el-table v-if="overrides.length" :data="overrides" style="width: 100%">
          <el-table-column label="群" min-width="200">
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
          <el-table-column label="模式" width="110">
            <template #default="{ row }">
              <span class="mode-tag" :class="(row.mode || 'inherit').toLowerCase()">{{ modeLabel(row.mode) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="细节" min-width="200">
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
          <strong>所有群都用默认策略</strong>
          想让某个群主动接话、或者只在被叫到时才回，再在这里加一条。
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingChatId ? '编辑按群回复策略' : '新增按群回复策略'"
      width="min(560px, 94vw)"
    >
      <el-form label-position="top">
        <el-form-item label="群">
          <ChatSelect
            v-model="chatInput"
            allow-create
            :disabled="Boolean(editingChatId)"
            placeholder="搜群名 / 好友昵称 / chatId"
            class="chat-select"
            @pick="onChatPick"
          />
        </el-form-item>

        <p v-if="chatEffective" class="inherit-hint">
          该群当前生效：{{ modeLabel(chatEffective.mode) }}
          <template v-if="chatEffective.mode === 'WAKE'">（唤醒词 {{ chatEffective.wakeWords || '未设置' }}）</template>
          <template v-else-if="chatEffective.mode === 'RANDOM'">（命中 {{ percent(chatEffective.randomRate) }}）</template>
          <template v-else-if="chatEffective.mode === 'HUMAN'">（判定间隔 {{ chatEffective.humanCooldownSeconds }} 秒）</template>
          · 来源 {{ sourceLabels[chatEffective.source] || chatEffective.source }}
        </p>

        <el-form-item label="回复模式">
          <el-radio-group v-model="chatForm.mode">
            <el-radio-button v-for="option in modeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="chatForm.mode === 'WAKE'" label="唤醒词">
          <el-input v-model="chatForm.wakeWords" placeholder="多个用逗号分隔，例如：@机器人,小助手" />
        </el-form-item>

        <el-form-item v-if="chatForm.mode === 'RANDOM'" label="随机命中概率">
          <div class="slider-row">
            <el-slider v-model="chatForm.randomPercent" :min="0" :max="100" :step="5" />
            <span class="field-value">{{ chatForm.randomPercent }}%</span>
          </div>
        </el-form-item>

        <el-form-item v-if="chatForm.mode === 'HUMAN'" label="判定间隔（秒）">
          <div class="slider-row">
            <el-input-number v-model="chatForm.humanCooldown" :min="0" :max="3600" :step="10" controls-position="right" />
            <span class="muted">间隔内的消息只入库、不判定；填 0 表示每条都问模型（很费 token）</span>
          </div>
        </el-form-item>

        <el-form-item label="启用这条按群策略">
          <div class="slider-row">
            <el-switch v-model="chatForm.enabled" />
            <span class="muted">停用后该群立即回退默认策略，配置会保留</span>
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
.mode-group {
  margin-bottom: 12px;
}

.mode-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  min-height: 32px;
}

.field-label {
  color: var(--muted);
  font-size: var(--fs-xs);
}

.field-input {
  width: min(420px, 100%);
}

.field-slider {
  width: min(320px, 60%);
}

.field-value {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.mode-tip {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.hint {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.hint b {
  color: var(--ink);
  font-weight: 600;
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

.mode-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  background: var(--surface-soft);
  color: var(--ink-soft);
}

.mode-tag.always {
  background: rgba(64, 158, 255, 0.12);
  color: #2f6fd0;
}

.mode-tag.random {
  background: rgba(230, 162, 60, 0.16);
  color: #a8701c;
}

.mode-tag.wake {
  background: rgba(103, 194, 58, 0.14);
  color: #3d7d1f;
}

.mode-tag.human {
  background: rgba(139, 92, 246, 0.16);
  color: #6d28d9;
}

.slider-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.slider-row .el-slider {
  flex: 1;
}

.inherit-hint {
  margin: 0 0 14px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-soft);
  color: var(--muted);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
</style>
