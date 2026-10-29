<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Cpu,
  Message,
  Refresh,
  Right,
  Timer,
} from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Dashboard, HookStatus, Message as ChatMessage } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const router = useRouter()
const loading = ref(false)
const hook = ref<HookStatus | null>(null)
const hookOnline = computed(() => Boolean(hook.value?.connected))
const hookLabel = computed(() => {
  if (!hook.value) return 'HOOK CHECKING'
  if (!hook.value.enabled) return 'HOOK DISABLED'
  return hook.value.connected ? 'BOT ONLINE' : 'HOOK OFFLINE'
})
const hookSummary = computed(() => {
  if (!hook.value) return '正在检查原生 Hook 状态。'
  if (!hook.value.configured) return '尚未配置允许的微信会话白名单。'
  if (!hook.value.connected) return hook.value.message || '等待微信 Hook 连接。'
  return `Hook ${hook.value.version} · ${hook.value.protocol} · ${hook.value.allowedChats.length} 个白名单会话`
})
const data = ref<Dashboard>({
  conversations: 0,
  messages24h: 0,
  inbound24h: 0,
  pendingOutbox: 0,
  enabledProviders: 0,
  recentMessages: [],
})

const stats = [
  { key: 'conversations', label: '会话总数', icon: ChatDotRound, tone: 'blue' },
  { key: 'messages24h', label: '24h 消息', icon: Message, tone: 'violet' },
  { key: 'inbound24h', label: '24h 入站', icon: Timer, tone: 'cyan' },
  { key: 'pendingOutbox', label: '待发送', icon: Refresh, tone: 'amber' },
] as const

async function load() {
  loading.value = true
  const [dashboardResult, hookResult] = await Promise.allSettled([
    adminApi.dashboard(),
    adminApi.hookStatus(),
  ])
  if (dashboardResult.status === 'fulfilled') {
    data.value = dashboardResult.value
  } else {
    ElMessage.error(getErrorMessage(dashboardResult.reason))
  }
  if (hookResult.status === 'fulfilled') {
    hook.value = hookResult.value
  } else {
    ElMessage.error(getErrorMessage(hookResult.reason))
  }
  loading.value = false
}

function statValue(key: (typeof stats)[number]['key']) {
  return data.value[key]
}

function directionTag(message: ChatMessage) {
  return message.direction === 'INBOUND' ? '收' : '发'
}

function timeLabel(value: string) {
  return dayjs(value).format('MM-DD HH:mm:ss')
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>运行概览</h1>
        <p>查看机器人最近的对话量、模型状态与消息投递情况。</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新数据</el-button>
    </div>

    <section class="hero-card">
      <div class="hero-copy">
        <div class="hero-status" :class="{ offline: !hookOnline }"><span class="pulse"></span> {{ hookLabel }}</div>
        <h2>{{ hookOnline ? '微信 AI 工作流已就绪' : '等待微信 Hook 连接' }}</h2>
        <p>
          当前启用了 <strong>{{ data.enabledProviders }}</strong> 个模型提供方。{{ hookSummary }}
          Java 单体直接消费 Outbox，断线重试和游标恢复都在服务端完成。
        </p>
        <div class="hero-actions">
          <el-button type="primary" @click="router.push('/debug')">在线调试</el-button>
          <el-button plain @click="router.push('/providers')">配置模型</el-button>
        </div>
      </div>
      <div class="hero-visual">
        <div class="signal-ring ring-one"></div>
        <div class="signal-ring ring-two"></div>
        <div class="signal-core"><el-icon :size="34"><ChatDotRound /></el-icon></div>
        <span class="float-chip chip-a">DeepSeek</span>
        <span class="float-chip chip-b">OpenAI Compatible</span>
        <span class="float-chip chip-c">Native Hook DLL</span>
      </div>
    </section>

    <div class="stats-grid">
      <article v-for="item in stats" :key="item.key" class="stat-card surface-card">
        <div class="stat-icon" :class="item.tone">
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
        </div>
        <div>
          <span class="stat-label">{{ item.label }}</span>
          <strong class="stat-value">{{ statValue(item.key) }}</strong>
        </div>
        <span class="stat-spark"></span>
      </article>
    </div>

    <div class="dashboard-grid">
      <section class="surface-card messages-card">
        <div class="surface-card__head">
          <div>
            <h2>最近消息</h2>
            <p>最新 8 条入站与出站消息</p>
          </div>
          <el-button text type="primary" @click="router.push('/conversations')">
            查看全部 <el-icon class="el-icon--right"><Right /></el-icon>
          </el-button>
        </div>
        <div class="surface-card__body">
          <div v-if="data.recentMessages.length === 0" class="empty-state">
            <strong>还没有消息</strong>
            在在线调试中发送第一条消息，或等待微信 Hook 接收新消息。
          </div>
          <el-table v-else :data="data.recentMessages" style="width: 100%">
            <el-table-column label="方向" width="66">
              <template #default="{ row }">
                <span class="direction-pill" :class="row.direction.toLowerCase()">{{ directionTag(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="内容" min-width="240">
              <template #default="{ row }">
                <div class="message-preview">{{ row.content }}</div>
                <div class="message-meta">
                  {{ row.senderName || '未知发送者' }}
                  <template v-if="row.model"> · {{ row.model }}</template>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'SUCCESS'" type="success" effect="light" size="small">成功</el-tag>
                <el-tag v-else-if="row.status === 'FAILED'" type="danger" effect="light" size="small">失败</el-tag>
                <el-tag v-else type="info" effect="light" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="128">
              <template #default="{ row }">{{ timeLabel(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <aside class="side-stack">
        <section class="surface-card health-card">
          <div class="surface-card__head"><h2>接入检查</h2><Cpu class="head-icon" /></div>
          <div class="check-list">
            <div class="check-row">
              <span>模型提供方</span>
              <el-tag :type="data.enabledProviders > 0 ? 'success' : 'warning'" effect="light" size="small">
                {{ data.enabledProviders > 0 ? `${data.enabledProviders} 个启用` : '待配置' }}
              </el-tag>
            </div>
            <div class="check-row">
              <span>PostgreSQL</span>
              <el-tag type="success" effect="light" size="small">已连接</el-tag>
            </div>
            <div class="check-row">
              <span>Redis</span>
              <el-tag type="info" effect="light" size="small">已配置（支持降级）</el-tag>
            </div>
            <div class="check-row">
              <span>待投递回复</span>
              <el-tag :type="data.pendingOutbox > 0 ? 'warning' : 'info'" effect="light" size="small">
                {{ data.pendingOutbox }}
              </el-tag>
            </div>
          </div>
        </section>

        <section class="quick-card">
          <div>
            <span>QUICK START</span>
            <h3>三步接通真实微信</h3>
            <p>复制 Agent Token，启动 Windows 适配器并确认消息轮询。</p>
          </div>
          <el-button plain @click="router.push('/bot')">查看配置</el-button>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.hero-card {
  position: relative;
  display: flex;
  min-height: 248px;
  justify-content: space-between;
  overflow: hidden;
  margin-bottom: 18px;
  padding: 34px 38px;
  border-radius: 20px;
  color: #fff;
  background:
    radial-gradient(circle at 78% 20%, rgba(56, 189, 248, 0.3), transparent 22%),
    radial-gradient(circle at 95% 100%, rgba(37, 99, 235, 0.4), transparent 32%),
    linear-gradient(120deg, #0b1220, #12213d 58%, #0d1b34);
  box-shadow: 0 22px 55px rgba(15, 23, 42, 0.18);
}

.hero-copy {
  position: relative;
  z-index: 2;
  max-width: 630px;
}

.hero-status {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #93c5fd;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.16em;
}

.hero-status.offline {
  color: #fca5a5;
}

.hero-status.offline .pulse {
  background: #fb7185;
  box-shadow: 0 0 0 5px rgba(251, 113, 133, 0.12);
}

.pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4ade80;
  box-shadow: 0 0 0 5px rgba(74, 222, 128, 0.12);
}

.hero-card h2 {
  margin: 17px 0 10px;
  font-size: 28px;
  letter-spacing: -0.04em;
}

.hero-card p {
  margin: 0;
  color: #94a3b8;
  font-size: 13px;
  line-height: 1.8;
}

.hero-card p strong {
  color: #dbeafe;
}

.hero-actions {
  display: flex;
  gap: 9px;
  margin-top: 24px;
}

.hero-visual {
  position: relative;
  width: 330px;
  flex: 0 0 auto;
}

.signal-ring,
.signal-core {
  position: absolute;
  top: 50%;
  left: 55%;
  transform: translate(-50%, -50%);
  border-radius: 50%;
}

.signal-ring {
  border: 1px solid rgba(96, 165, 250, 0.19);
}

.ring-one {
  width: 158px;
  height: 158px;
}

.ring-two {
  width: 244px;
  height: 244px;
}

.signal-core {
  display: grid;
  width: 86px;
  height: 86px;
  place-items: center;
  color: #bfdbfe;
  background: radial-gradient(circle at 35% 30%, #3b82f6, #1e3a8a);
  box-shadow: 0 0 60px rgba(59, 130, 246, 0.45);
}

.float-chip {
  position: absolute;
  padding: 7px 10px;
  border: 1px solid rgba(148, 163, 184, 0.15);
  border-radius: 8px;
  color: #cbd5e1;
  background: rgba(15, 23, 42, 0.64);
  font-size: 11px;
  letter-spacing: 0.03em;
  backdrop-filter: blur(10px);
}

.chip-a { top: 28px; right: 22px; }
.chip-b { top: 112px; right: 0; }
.chip-c { bottom: 30px; left: 22px; }

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 104px;
  overflow: hidden;
  padding: 20px;
}

.stat-icon {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 12px;
}

.stat-icon.blue { color: #2563eb; background: #eff6ff; }
.stat-icon.violet { color: #7c3aed; background: #f5f3ff; }
.stat-icon.cyan { color: #0891b2; background: #ecfeff; }
.stat-icon.amber { color: #d97706; background: #fffbeb; }

.stat-label,
.stat-value {
  display: block;
}

.stat-label {
  color: var(--muted);
  font-size: 12px;
}

.stat-value {
  margin-top: 5px;
  font-size: 25px;
  letter-spacing: -0.04em;
}

.stat-spark {
  position: absolute;
  right: -20px;
  bottom: -38px;
  width: 100px;
  height: 100px;
  border: 18px solid rgba(59, 130, 246, 0.035);
  border-radius: 50%;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(280px, 0.75fr);
  gap: 18px;
}

.surface-card__head p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.message-preview {
  max-width: 500px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-meta {
  margin-top: 3px;
  color: #94a3b8;
  font-size: 12px;
}

.direction-pill {
  display: inline-grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 7px;
  font-size: 12px;
}

.direction-pill.inbound { color: #2563eb; background: #eff6ff; }
.direction-pill.outbound { color: #047857; background: #ecfdf5; }

.side-stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.head-icon {
  color: #3b82f6;
}

.check-list {
  padding: 0 22px 20px;
}

.check-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 0;
  border-bottom: 1px solid #f1f5f9;
  color: #475569;
  font-size: 13px;
}

.check-row:last-child {
  border-bottom: 0;
}

.quick-card {
  display: flex;
  min-height: 170px;
  flex-direction: column;
  justify-content: space-between;
  padding: 24px;
  border-radius: 18px;
  color: #e2e8f0;
  background:
    radial-gradient(circle at 90% 10%, rgba(56, 189, 248, 0.2), transparent 35%),
    #111c33;
}

.quick-card span {
  color: #60a5fa;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.14em;
}

.quick-card h3 {
  margin: 10px 0 8px;
  font-size: 17px;
}

.quick-card p {
  margin: 0;
  color: #8192ad;
  font-size: 12px;
  line-height: 1.7;
}

.quick-card .el-button {
  align-self: flex-start;
  margin-top: 20px;
  color: #dbeafe;
  border-color: rgba(148, 163, 184, 0.24);
  background: rgba(148, 163, 184, 0.06);
}

@media (max-width: 1120px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .hero-visual {
    opacity: 0.65;
  }
}

@media (max-width: 860px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .hero-visual {
    position: absolute;
    inset: 0 0 0 auto;
    opacity: 0.35;
  }
}

@media (max-width: 620px) {
  .hero-card {
    padding: 28px 24px;
  }

  .hero-card h2 {
    font-size: 23px;
  }

  .stats-grid {
    grid-template-columns: 1fr;
  }
}
</style>




