<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Dashboard, HookStatus, Message as ChatMessage } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const router = useRouter()
const loading = ref(false)
const hook = ref<HookStatus | null>(null)
const hookOnline = computed(() => Boolean(hook.value?.connected))
const hookLabel = computed(() => {
  if (!hook.value) return '检查中'
  if (!hook.value.enabled) return 'Hook 已关闭'
  return hook.value.connected ? '机器人在线' : '微信未连接'
})
const hookSummary = computed(() => {
  if (!hook.value) return '正在检查原生 Hook 状态'
  if (!hook.value.configured) return hook.value.message || '尚未配置允许的微信会话白名单'
  if (!hook.value.connected) return hook.value.message || '等待微信 Hook 连接'
  return `Hook ${hook.value.version} · ${hook.value.protocol} · 白名单 ${hook.value.allowedChats.length} 个会话`
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
  { key: 'conversations', label: '会话总数' },
  { key: 'messages24h', label: '24 小时消息' },
  { key: 'inbound24h', label: '24 小时入站' },
  { key: 'pendingOutbox', label: '待投递' },
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
        <p>机器人最近的对话量、模型状态与消息投递情况。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <section class="status-bar" :class="{ offline: !hookOnline }">
      <span class="status-bar__dot"></span>
      <div class="status-bar__copy">
        <strong>{{ hookLabel }}</strong>
        <span>{{ hookSummary }}</span>
      </div>
      <div class="status-bar__actions">
        <el-button size="small" @click="router.push('/debug')">在线调试</el-button>
        <el-button size="small" type="primary" @click="router.push('/providers')">模型配置</el-button>
      </div>
    </section>

    <div class="stat-grid">
      <article v-for="item in stats" :key="item.key" class="stat-card">
        <span class="stat-card__label">{{ item.label }}</span>
        <strong class="stat-card__value">{{ statValue(item.key) }}</strong>
      </article>
    </div>

    <div class="content-grid">
      <section class="surface-card">
        <div class="surface-card__head">
          <div>
            <h2>最近消息</h2>
            <p class="head-desc">最新 8 条入站与出站消息</p>
          </div>
          <el-button text type="primary" size="small" @click="router.push('/conversations')">
            查看全部
          </el-button>
        </div>
        <div class="surface-card__body">
          <el-table v-if="data.recentMessages.length" :data="data.recentMessages" style="width: 100%">
            <el-table-column label="方向" width="64">
              <template #default="{ row }">
                <span class="direction" :class="row.direction.toLowerCase()">{{ directionTag(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="内容" min-width="240">
              <template #default="{ row }">
                <div class="message-text">{{ row.content }}</div>
                <div class="message-meta">
                  {{ row.senderName || '未知发送者' }}
                  <template v-if="row.model"> · {{ row.model }}</template>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <span class="state" :class="row.status.toLowerCase()">
                  {{ row.status === 'SUCCESS' ? '成功' : row.status === 'FAILED' ? '失败' : row.status }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="122">
              <template #default="{ row }"><span class="muted">{{ timeLabel(row.createdAt) }}</span></template>
            </el-table-column>
          </el-table>
          <div v-else class="empty-state">
            <strong>还没有消息</strong>
            在在线调试中发一条，或等微信收到新消息。
          </div>
        </div>
      </section>

      <section class="surface-card">
        <div class="surface-card__head">
          <h2>接入检查</h2>
        </div>
        <div class="surface-card__body">
          <ul class="check-list">
            <li>
              <span>模型提供方</span>
              <b :class="data.enabledProviders > 0 ? 'ok' : 'warn'">
                {{ data.enabledProviders > 0 ? `${data.enabledProviders} 个启用` : '待配置' }}
              </b>
            </li>
            <li>
              <span>PostgreSQL</span>
              <b class="ok">已连接</b>
            </li>
            <li>
              <span>Redis</span>
              <b class="muted">已配置 · 支持降级</b>
            </li>
            <li>
              <span>待投递回复</span>
              <b :class="data.pendingOutbox > 0 ? 'warn' : 'ok'">{{ data.pendingOutbox }}</b>
            </li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
  padding: 16px 20px;
  border: 1px solid #d7e7ff;
  border-radius: var(--radius);
  background: #f5f9ff;
}

.status-bar.offline {
  border-color: #fde2c8;
  background: #fffaf4;
}

.status-bar__dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.14);
}

.status-bar.offline .status-bar__dot {
  background: #f59e0b;
  box-shadow: 0 0 0 4px rgba(245, 158, 11, 0.16);
}

.status-bar__copy {
  min-width: 0;
  flex: 1;
}

.status-bar__copy strong {
  display: block;
  color: var(--ink);
  font-size: var(--fs-md);
  font-weight: 600;
}

.status-bar__copy span {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.status-bar__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.stat-card__label {
  color: var(--muted);
  font-size: var(--fs-xs);
}

.stat-card__value {
  color: var(--ink);
  font-size: 26px;
  font-weight: 680;
  letter-spacing: -0.02em;
  line-height: 1.1;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 2.1fr) minmax(260px, 1fr);
  gap: 18px;
  align-items: start;
}

.direction {
  display: inline-grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #1d4ed8;
  background: #eff6ff;
}

.direction.outbound {
  color: #047857;
  background: #ecfdf5;
}

.message-text {
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  color: var(--ink-soft);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.message-meta {
  margin-top: 3px;
  color: var(--muted-soft);
  font-size: 11px;
}

.state {
  font-size: var(--fs-xs);
  font-weight: 550;
  color: var(--muted);
}

.state.success {
  color: var(--success);
}

.state.failed {
  color: var(--danger);
}

.check-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.check-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line-soft);
  font-size: var(--fs-sm);
}

.check-list li:last-child {
  border-bottom: 0;
}

.check-list span {
  color: var(--muted);
}

.check-list b {
  font-weight: 550;
}

.check-list .ok {
  color: var(--success);
}

.check-list .warn {
  color: var(--warning);
}

@media (max-width: 1080px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .status-bar {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .status-bar__actions {
    width: 100%;
  }
}

@media (max-width: 520px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
