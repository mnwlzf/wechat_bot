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
  input.value = ''
  sending.value = true
  await scrollToBottom()
  try {
    const result = await adminApi.debugChat(activeBot.value.id, content)
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
            :rows="3"
            resize="none"
            maxlength="10000"
            placeholder="输入测试消息，Enter 发送，Shift + Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
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
.head-actions { display: flex; gap: 9px; }
.debug-layout { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(260px, 0.65fr); gap: 18px; }

.chat-panel {
  display: flex;
  min-height: 690px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #dfe7f1;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 12px 35px rgba(15, 23, 42, 0.045);
}

.chat-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 20px;
  border-bottom: 1px solid #edf2f7;
  background: #fbfdff;
}

.assistant-profile { display: flex; align-items: center; gap: 10px; }
.assistant-avatar { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 12px; color: #bfdbfe; background: linear-gradient(145deg, #0f172a, #1e3a8a); }
.assistant-profile strong, .assistant-profile span { display: block; }
.assistant-profile strong { color: #1e293b; font-size: 13px; }
.assistant-profile span { display: flex; align-items: center; gap: 5px; margin-top: 3px; color: #94a3b8; font-size: 11px; }
.assistant-profile i { width: 6px; height: 6px; border-radius: 50%; background: #22c55e; }
.assistant-profile i.off { background: #f59e0b; }

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 30px 28px;
  background:
    radial-gradient(circle at 50% 0%, rgba(59, 130, 246, 0.035), transparent 32%),
    #fbfdff;
}

.welcome { max-width: 620px; margin: 48px auto 0; text-align: center; }
.welcome-icon { display: grid; width: 58px; height: 58px; margin: 0 auto 17px; place-items: center; border-radius: 17px; color: #2563eb; background: #eff6ff; }
.welcome h2 { margin: 0 0 8px; font-size: 19px; letter-spacing: -0.03em; }
.welcome p { margin: 0; color: #94a3b8; font-size: 12px; line-height: 1.7; }
.suggestions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 25px; }
.suggestions button { padding: 11px 12px; border: 1px solid #e5edf7; border-radius: 10px; color: #475569; background: #fff; font-size: 12px; text-align: left; cursor: pointer; transition: 0.18s ease; }
.suggestions button:hover { border-color: #bfdbfe; color: #1d4ed8; background: #f8fbff; transform: translateY(-1px); }

.bubble-row { display: flex; align-items: flex-start; gap: 9px; margin-bottom: 22px; }
.bubble-row.user { flex-direction: row-reverse; }
.bubble-avatar { display: grid; width: 30px; height: 30px; flex: 0 0 auto; place-items: center; border-radius: 9px; color: #1d4ed8; background: #dbeafe; font-size: 11px; font-weight: 700; }
.bubble-row.user .bubble-avatar { color: #475569; background: #e2e8f0; }
.bubble-stack { max-width: min(76%, 580px); }
.bubble-row.user .bubble-stack { text-align: right; }
.bubble-name { display: block; margin-bottom: 5px; color: #94a3b8; font-size: 11px; }
.bubble { display: inline-block; padding: 12px 14px; border-radius: 4px 14px 14px 14px; color: #334155; background: #fff; box-shadow: 0 3px 12px rgba(15, 23, 42, 0.05); font-size: 13px; line-height: 1.8; text-align: left; white-space: pre-wrap; word-break: break-word; }
.bubble-row.user .bubble { border-radius: 14px 4px 14px 14px; color: #fff; background: linear-gradient(145deg, #2563eb, #1d4ed8); }
.bubble-meta { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 5px; color: #94a3b8; font-size: 8px; }

.typing-row { display: flex; gap: 9px; }
.typing { display: flex; align-items: center; gap: 5px; padding: 12px 15px; border-radius: 4px 14px 14px 14px; background: #fff; box-shadow: 0 3px 12px rgba(15, 23, 42, 0.05); }
.typing i { width: 5px; height: 5px; border-radius: 50%; background: #94a3b8; animation: blink 1.1s infinite ease-in-out; }
.typing i:nth-child(2) { animation-delay: 0.15s; }
.typing i:nth-child(3) { animation-delay: 0.3s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }

.composer { padding: 14px 18px 16px; border-top: 1px solid #edf2f7; background: #fff; }
.composer :deep(.el-textarea__inner) { padding: 11px 13px; border-radius: 11px; box-shadow: 0 0 0 1px #e5edf7 inset; }
.composer-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 9px; }
.composer-foot span { color: #b0bccd; font-size: 11px; }

.inspector { display: flex; flex-direction: column; gap: 16px; }
.inspector-card { padding: 20px; }
.inspector-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.inspector-head h3 { margin: 0; font-size: 13px; }
.inspector-head span { padding: 3px 6px; border-radius: 5px; color: #15803d; background: #f0fdf4; font-size: 8px; font-weight: 700; }
.inspector dl { margin: 0; }
.inspector dl > div { display: flex; justify-content: space-between; gap: 12px; padding: 9px 0; border-bottom: 1px solid #f1f5f9; font-size: 12px; }
.inspector dl > div:last-child { border-bottom: 0; }
.inspector dt { color: #94a3b8; }
.inspector dd { overflow: hidden; margin: 0; color: #475569; text-align: right; text-overflow: ellipsis; white-space: nowrap; }
.prompt-preview { display: -webkit-box; overflow: hidden; margin: 0 0 10px; color: #64748b; font-size: 12px; line-height: 1.8; -webkit-box-orient: vertical; -webkit-line-clamp: 10; white-space: pre-wrap; }

@media (max-width: 980px) {
  .debug-layout { grid-template-columns: 1fr; }
  .inspector { display: grid; grid-template-columns: 1fr 1fr; }
  .inspector :deep(.el-alert) { grid-column: 1 / -1; }
}

@media (max-width: 640px) {
  .chat-panel { min-height: 620px; }
  .chat-topbar { align-items: stretch; flex-direction: column; }
  .suggestions { grid-template-columns: 1fr; }
  .inspector { grid-template-columns: 1fr; }
  .chat-body { padding: 22px 16px; }
  .bubble-stack { max-width: 84%; }
}
</style>
