<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Delete, MagicStick, Promotion, Setting } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Bot, Provider } from '@/api/types'
import { getErrorMessage } from '@/api/client'

interface DebugMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  provider?: string
  model?: string
  latencyMs?: number
}

const router = useRouter()
const loading = ref(false)
const sending = ref(false)
const bots = ref<Bot[]>([])
const providers = ref<Provider[]>([])
const activeBotId = ref('')
const input = ref('')
const chatBody = ref<HTMLElement>()
const messages = ref<DebugMessage[]>([])
// 模拟微信侧的消息形态：媒体类型决定模型看到 [图片]/[语音 5″] 时的额外说明，引用用来复现引用回复
const mediaKind = ref('')
const quoteOn = ref(false)
const quoteAuthor = ref('张三')
const quoteText = ref('')

const mediaOptions = [
  { label: '纯文本', value: '', sample: '' },
  { label: '图片', value: 'IMAGE', sample: '[图片]' },
  { label: '语音', value: 'VOICE', sample: '[语音 5″]' },
  { label: '表情', value: 'STICKER', sample: '[表情]' },
  { label: '视频', value: 'VIDEO', sample: '[视频 12″]' },
  { label: '文件', value: 'FILE', sample: '[文件] 报价单.pdf · 1.5 MB' },
  { label: '链接', value: 'LINK', sample: '[链接] 春季新品发布' },
  { label: '位置', value: 'LOCATION', sample: '[位置] 中关村 · 北京市海淀区中关村大街1号' },
  { label: '名片', value: 'CARD', sample: '[名片] 张三' },
  { label: '小程序', value: 'MINIAPP', sample: '[小程序] 滴滴出行' },
  { label: '聊天记录', value: 'RECORD', sample: '[聊天记录] 群聊的聊天记录' },
  { label: '转账', value: 'TRANSFER', sample: '[转账] ¥520.00' },
  { label: '红包', value: 'REDPACKET', sample: '[红包] 恭喜发财，大吉大利' },
  { label: '视频号', value: 'CHANNEL', sample: '[视频号] 今天的日落' },
]

/** 切到某个媒体类型时，如果输入框还是空的（或还是上一个示例），顺手填个示例方便直接发。 */
function onMediaKindChange(value: string) {
  const sample = mediaOptions.find((item) => item.value === value)?.sample || ''
  const isSample = !input.value.trim() || mediaOptions.some((item) => item.sample && item.sample === input.value.trim())
  if (isSample) input.value = sample
}

const activeBot = computed(() => bots.value.find((bot) => bot.id === activeBotId.value))
const provider = computed(() => providers.value.find((item) => item.id === activeBot.value?.modelProviderId))
const canChat = computed(() => Boolean(activeBot.value?.enabled && activeBot.value?.modelProviderId))

const suggestions = [
  '用一句话介绍你能做什么',
  '帮我写一段礼貌的请假说明',
  '解释一下 Redis 在机器人里的作用',
  '给我三个提高客户回复效率的建议',
]

async function load() {
  loading.value = true
  try {
    const [botList, providerList] = await Promise.all([adminApi.bots(), adminApi.providers()])
    bots.value = botList
    providers.value = providerList
    activeBotId.value = botList[0]?.id || ''
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function send() {
  const content = input.value.trim()
  if (!content || sending.value) return
  if (!activeBot.value) {
    ElMessage.warning('请先配置机器人')
    return
  }
  if (!canChat.value) {
    ElMessage.warning('请为机器人选择一个已启用的模型')
    return
  }

  messages.value.push({ id: crypto.randomUUID(), role: 'user', content })
  const extra: { quoteAuthor?: string; quoteText?: string; mediaKind?: string } = {}
  if (mediaKind.value) extra.mediaKind = mediaKind.value
  if (quoteOn.value && quoteText.value.trim()) {
    extra.quoteAuthor = quoteAuthor.value.trim() || '张三'
    extra.quoteText = quoteText.value.trim()
  }
  input.value = ''
  sending.value = true
  await scrollToBottom()
  try {
    const result = await adminApi.debugChat(activeBot.value.id, content, extra)
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'assistant',
      content: result.reply,
      provider: result.provider,
      model: result.model,
      latencyMs: result.latencyMs,
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function useSuggestion(text: string) {
  input.value = text
}

function clearChat() {
  messages.value = []
}

async function scrollToBottom() {
  await nextTick()
  if (chatBody.value) {
    chatBody.value.scrollTop = chatBody.value.scrollHeight
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>在线调试</h1>
        <p>直接调用机器人的完整 AI 链路，验证提示词、模型和上下文配置。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Delete" :disabled="messages.length === 0" @click="clearChat">清空对话</el-button>
        <el-button :icon="Setting" @click="router.push('/bot')">机器人配置</el-button>
      </div>
    </div>

    <div class="debug-layout">
      <section class="chat-panel">
        <header class="chat-topbar">
          <div class="assistant-profile">
            <span class="assistant-avatar"><el-icon :size="20"><ChatDotRound /></el-icon></span>
            <div>
              <strong>{{ activeBot?.name || '微信 AI 助手' }}</strong>
              <span><i :class="{ off: !canChat }"></i>{{ canChat ? '就绪' : '未就绪' }}</span>
            </div>
          </div>
          <el-select v-model="activeBotId" placeholder="选择机器人" style="width: 190px">
            <el-option v-for="bot in bots" :key="bot.id" :label="bot.name" :value="bot.id" />
          </el-select>
        </header>

        <div ref="chatBody" class="chat-body">
          <div v-if="messages.length === 0" class="welcome">
            <div class="welcome-icon"><el-icon :size="28"><MagicStick /></el-icon></div>
            <h2>开始一次测试对话</h2>
            <p>消息会进入真实后端链路并写入会话审计，但不会发送到微信。</p>
            <div class="suggestions">
              <button v-for="suggestion in suggestions" :key="suggestion" type="button" @click="useSuggestion(suggestion)">
                {{ suggestion }}
              </button>
            </div>
          </div>

          <template v-for="message in messages" :key="message.id">
            <div class="bubble-row" :class="message.role">
              <span class="bubble-avatar">
                <el-icon v-if="message.role === 'assistant'"><MagicStick /></el-icon>
                <template v-else>你</template>
              </span>
              <div class="bubble-stack">
                <span class="bubble-name">{{ message.role === 'assistant' ? activeBot?.name || 'AI 助手' : '管理员' }}</span>
                <div class="bubble">{{ message.content }}</div>
                <div v-if="message.role === 'assistant'" class="bubble-meta">
                  <span>{{ message.provider }}</span>
                  <span>{{ message.model }}</span>
                  <span>{{ message.latencyMs }} ms</span>
                </div>
              </div>
            </div>
          </template>

          <div v-if="sending" class="typing-row">
            <span class="bubble-avatar"><el-icon><MagicStick /></el-icon></span>
            <div class="typing"><i></i><i></i><i></i></div>
          </div>
        </div>

        <footer class="composer">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="10000"
            placeholder="输入测试消息，Enter 发送，Shift + Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <div class="composer-tools">
            <el-select
              v-model="mediaKind"
              size="small"
              style="width: 118px"
              placeholder="消息类型"
              @change="onMediaKindChange"
            >
              <el-option v-for="option in mediaOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-checkbox v-model="quoteOn" size="small">引用一条消息</el-checkbox>
            <span class="composer-hint">模拟微信消息形态，只影响模型看到的内容</span>
          </div>
          <div v-if="quoteOn" class="quote-fields">
            <el-input v-model="quoteAuthor" size="small" maxlength="180" placeholder="被引用的人（如 张三）" style="width: 170px" />
            <el-input v-model="quoteText" size="small" maxlength="1000" placeholder="被引用的原文，例如 6 或 [图片]" />
          </div>
          <div class="composer-foot">
            <span>{{ input.length }} / 10000</span>
            <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!input.trim() || !canChat" @click="send">
              发送
            </el-button>
          </div>
        </footer>
      </section>

      <aside class="inspector">
        <section class="surface-card inspector-card">
          <div class="inspector-head"><h3>当前配置</h3><span>LIVE</span></div>
          <dl>
            <div><dt>机器人</dt><dd>{{ activeBot?.name || '—' }}</dd></div>
            <div><dt>模型提供方</dt><dd>{{ provider?.name || '未选择' }}</dd></div>
            <div><dt>模型</dt><dd>{{ provider?.model || '—' }}</dd></div>
            <div><dt>Temperature</dt><dd>{{ activeBot?.temperature ?? '—' }}</dd></div>
            <div><dt>上下文窗口</dt><dd>{{ activeBot?.contextWindow ?? '—' }} 条</dd></div>
            <div><dt>输出上限</dt><dd>{{ activeBot?.maxTokens ?? '—' }} tokens</dd></div>
          </dl>
        </section>

        <section class="surface-card inspector-card">
          <div class="inspector-head"><h3>系统提示词</h3></div>
          <p class="prompt-preview">{{ activeBot?.systemPrompt || '尚未配置系统提示词。' }}</p>
          <el-button text type="primary" @click="router.push('/bot')">编辑提示词</el-button>
        </section>

        <el-alert
          v-if="!activeBot?.modelProviderId"
          title="尚未选择模型"
          description="先在模型配置中添加提供方，再回到机器人配置中绑定模型。"
          type="warning"
          :closable="false"
          show-icon
        />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.head-actions { display: flex; gap: 8px; }
.debug-layout { display: grid; grid-template-columns: minmax(0, 1.8fr) minmax(250px, 0.6fr); gap: 12px; }

.chat-panel {
  display: flex;
  min-height: 600px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow-sm);
}

.chat-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
  background: #fff;
}

.assistant-profile { display: flex; align-items: center; gap: 8px; }
.assistant-avatar { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 7px; color: #dbeafe; background: #334155; }
.assistant-profile strong, .assistant-profile span { display: block; }
.assistant-profile strong { color: var(--ink); font-size: var(--fs-sm); }
.assistant-profile span { display: flex; align-items: center; gap: 4px; margin-top: 1px; color: var(--muted-soft); font-size: 10px; }
.assistant-profile i { width: 5px; height: 5px; border-radius: 50%; background: #22c55e; }
.assistant-profile i.off { background: #f59e0b; }

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 16px;
  background: #fbfcfe;
}

.welcome { max-width: 560px; margin: 28px auto 0; text-align: center; }
.welcome-icon { display: grid; width: 44px; height: 44px; margin: 0 auto 12px; place-items: center; border-radius: 10px; color: #2563eb; background: #eff6ff; }
.welcome h2 { margin: 0 0 5px; font-size: var(--fs-lg); letter-spacing: -0.02em; }
.welcome p { margin: 0; color: var(--muted); font-size: var(--fs-xs); line-height: 1.55; }
.suggestions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; margin-top: 16px; }
.suggestions button { padding: 8px 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink-soft); background: #fff; font-size: var(--fs-xs); text-align: left; cursor: pointer; transition: 0.15s ease; }
.suggestions button:hover { border-color: #bfdbfe; color: #1d4ed8; background: #f8fbff; }

.bubble-row { display: flex; align-items: flex-start; gap: 7px; margin-bottom: 12px; }
.bubble-row.user { flex-direction: row-reverse; }
.bubble-avatar { display: grid; width: 24px; height: 24px; flex: 0 0 auto; place-items: center; border-radius: 6px; color: #1d4ed8; background: #dbeafe; font-size: 10px; font-weight: 700; }
.bubble-row.user .bubble-avatar { color: #475569; background: #e2e8f0; }
.bubble-stack { max-width: min(80%, 560px); }
.bubble-row.user .bubble-stack { text-align: right; }
.bubble-name { display: block; margin-bottom: 3px; color: var(--muted-soft); font-size: 10px; }
.bubble { display: inline-block; padding: 8px 10px; border-radius: 3px 10px 10px 10px; color: var(--ink-soft); background: #fff; box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04); font-size: var(--fs-sm); line-height: 1.6; text-align: left; white-space: pre-wrap; word-break: break-word; }
.bubble-row.user .bubble { border-radius: 10px 3px 10px 10px; color: #fff; background: #2563eb; }
.bubble-meta { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 3px; color: var(--muted-soft); font-size: 8px; }

.typing-row { display: flex; gap: 7px; }
.typing { display: flex; align-items: center; gap: 4px; padding: 8px 11px; border-radius: 3px 10px 10px 10px; background: #fff; box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04); }
.typing i { width: 4px; height: 4px; border-radius: 50%; background: var(--muted-soft); animation: blink 1.1s infinite ease-in-out; }
.typing i:nth-child(2) { animation-delay: 0.15s; }
.typing i:nth-child(3) { animation-delay: 0.3s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-2px); } }

.composer { padding: 10px 12px 12px; border-top: 1px solid var(--line-soft); background: #fff; }
.composer :deep(.el-textarea__inner) { padding: 8px 10px; border-radius: 7px; box-shadow: 0 0 0 1px var(--line) inset; }
.composer-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 7px; }
.composer-tools :deep(.el-checkbox__label) { font-size: var(--fs-xs); }
.composer-hint { color: var(--muted-soft); font-size: 10px; }
.quote-fields { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.composer-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 6px; }
.composer-foot span { color: var(--muted-soft); font-size: 10px; }

.inspector { display: flex; flex-direction: column; gap: 10px; }
.inspector-card { padding: 14px; }
.inspector-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.inspector-head h3 { margin: 0; font-size: var(--fs-sm); }
.inspector-head span { padding: 2px 5px; border-radius: 4px; color: #15803d; background: #f0fdf4; font-size: 8px; font-weight: 700; }
.inspector dl { margin: 0; }
.inspector dl > div { display: flex; justify-content: space-between; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--line-soft); font-size: var(--fs-xs); }
.inspector dl > div:last-child { border-bottom: 0; }
.inspector dt { color: var(--muted-soft); }
.inspector dd { overflow: hidden; margin: 0; color: var(--ink-soft); text-align: right; text-overflow: ellipsis; white-space: nowrap; }
.prompt-preview { display: -webkit-box; overflow: hidden; margin: 0 0 8px; color: var(--muted); font-size: var(--fs-xs); line-height: 1.65; -webkit-box-orient: vertical; -webkit-line-clamp: 8; white-space: pre-wrap; }

@media (max-width: 980px) {
  .debug-layout { grid-template-columns: 1fr; }
  .inspector { display: grid; grid-template-columns: 1fr 1fr; }
  .inspector :deep(.el-alert) { grid-column: 1 / -1; }
}

@media (max-width: 640px) {
  .chat-panel { min-height: 540px; }
  .chat-topbar { align-items: stretch; flex-direction: column; }
  .suggestions { grid-template-columns: 1fr; }
  .inspector { grid-template-columns: 1fr; }
  .chat-body { padding: 14px 12px; }
  .bubble-stack { max-width: 86%; }
}
</style>
