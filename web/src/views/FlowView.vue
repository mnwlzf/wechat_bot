<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import { getErrorMessage } from '@/api/client'
import type { FlowEvent, FlowSnapshot, FlowStage, FlowWait } from '@/api/types'

/**
 * 实时流水：一条消息从进来到发出去，每一步都看得见。
 *
 * <p>被配额、群消息聚合、作息免打扰推迟的回复，以前在界面上只有一条静止的「已接收」，
 * 看不出卡在哪一层、还要等多久。这里把排队状态单独列出来，并要求接口说明「为什么在等」。</p>
 *
 * <p>用增量轮询（带 cursor）而不是全量刷新：列表只追加新事件，滚动位置不会被冲掉。</p>
 */

const STAGE_COLOR: Record<FlowStage, string> = {
  RECEIVED: 'info',
  SKIP: 'warning',
  BUFFER: 'primary',
  MERGE: 'info',
  QUOTA: 'danger',
  PACING: 'primary',
  LLM: 'success',
  OUTBOX: 'primary',
  SENT: 'success',
  FAILED: 'danger',
}

const events = ref<FlowEvent[]>([])
const waiting = ref<FlowWait[]>([])
const counters = ref<Record<string, number>>({})
const cursor = ref(0)
const live = ref(true)
const loading = ref(false)
const now = ref(Date.now())
const limit = 200

const pollMs = 1500
let pollTimer: ReturnType<typeof setInterval> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null

const queuedCount = computed(() => waiting.value.length)
const stats = computed(() => [
  { label: '收到消息', value: counters.value.RECEIVED ?? 0, hint: '进到机器人手里的条数' },
  { label: '已发出', value: counters.value.SENT ?? 0, hint: '真正发到微信的条数' },
  { label: '正在排队', value: queuedCount.value, hint: '眼下卡在某一层的会话数' },
  { label: '没回', value: counters.value.SKIP ?? 0, hint: '按策略 / 伪装层没发的条数' },
  { label: '被合并', value: counters.value.MERGE ?? 0, hint: '连发时被后一条并掉的条数' },
  { label: '发送失败', value: counters.value.FAILED ?? 0, hint: '重试到上限仍失败的条数' },
])

async function tick() {
  if (!live.value) return
  loading.value = events.value.length === 0
  try {
    const snapshot: FlowSnapshot = await adminApi.flow(cursor.value, limit)
    waiting.value = snapshot.waiting
    counters.value = snapshot.counters
    cursor.value = snapshot.cursor
    if (snapshot.events.length) {
      // 增量追加：新的排前面，太老的截掉
      const merged = [...snapshot.events, ...events.value]
      merged.sort((a, b) => b.seq - a.seq)
      events.value = merged.slice(0, limit)
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    stopPolling()
  } finally {
    loading.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(tick, pollMs)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function toggleLive() {
  live.value = !live.value
  if (live.value) {
    void tick()
    startPolling()
  } else {
    stopPolling()
  }
}

async function refresh() {
  cursor.value = 0
  events.value = []
  await tick()
}

function chatLabel(item: { chatName?: string | null; chatId?: string | null }) {
  if (item.chatName) return item.chatName
  if (item.chatId) return item.chatId
  return '—'
}

function timeLabel(value: string) {
  return dayjs(value).format('HH:mm:ss')
}

function durationLabel(value?: number | null) {
  if (value === null || value === undefined) return ''
  if (value < 1000) return `${value} ms`
  return `${(value / 1000).toFixed(1)} 秒`
}

/** 距离「解禁」还剩多久，每秒刷新一次；到点是 0，不再显示负数。 */
function remainingLabel(item: FlowWait) {
  if (!item.until) return '可能随时'
  const ms = Math.max(0, dayjs(item.until).valueOf() - now.value)
  if (ms === 0) return '马上就到'
  if (ms < 60_000) return `${Math.ceil(ms / 1000)} 秒`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.ceil((ms % 60_000) / 1000)
  return `${minutes} 分 ${seconds} 秒`
}

onMounted(async () => {
  await tick()
  startPolling()
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  stopPolling()
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>实时流水</h1>
        <p>
          消息进来 → 攒一攒 → 调模型 → 排期 → 发出去，每一步都在这里。
          被配额、群消息聚合或作息推迟的回复会出现在「正在等」里，并写明在等什么。
        </p>
      </div>
      <div class="head-actions">
        <el-button :icon="live ? VideoPause : VideoPlay" @click="toggleLive">
          {{ live ? '暂停刷新' : '继续刷新' }}
        </el-button>
        <el-button :icon="Refresh" @click="refresh">清空重来</el-button>
      </div>
    </div>

    <section class="live-bar" :class="{ paused: !live }">
      <span class="live-bar__dot"></span>
      <span>{{ live ? '每 1.5 秒自动刷新' : '已暂停（按钮恢复）' }}</span>
      <span class="live-bar__hint">内存里只保留最近 {{ limit }} 条，重启即清空</span>
    </section>

    <div class="stat-grid">
      <article v-for="item in stats" :key="item.label" class="stat-card">
        <span class="stat-card__label">{{ item.label }}</span>
        <strong class="stat-card__value">{{ item.value }}</strong>
        <span class="stat-card__hint">{{ item.hint }}</span>
      </article>
    </div>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>正在等</h2>
          <p class="head-desc">
            卡在配额、攒消息、发送队列里的会话；没人在等的话这里就是空的
          </p>
        </div>
        <el-tag :type="queuedCount ? 'warning' : 'info'" size="small">{{ queuedCount }} 个会话</el-tag>
      </div>
      <div class="surface-card__body">
        <div v-if="!waiting.length" class="empty-state">
          <strong>当前没有排队的回复</strong>
          来一条消息，这里会实时出现它在哪一层、还要等多久。
        </div>
        <div v-else class="wait-list">
          <div v-for="item in waiting" :key="(item.chatId || '') + item.stage" class="wait-item">
            <div class="wait-item__main">
              <el-tag :type="STAGE_COLOR[item.stage] || 'info'" size="small" effect="dark">
                {{ item.stageLabel }}
              </el-tag>
              <strong>{{ chatLabel(item) }}</strong>
              <span class="wait-item__reason">{{ item.reason }}</span>
            </div>
            <div class="wait-item__meta">
              <span class="wait-item__countdown">{{ remainingLabel(item) }}</span>
              <span v-if="item.until" class="wait-item__until">
                预计 {{ timeLabel(item.until) }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>流水</h2>
          <p class="head-desc">最新的在最上面；「等待」是排队时长，「耗时」是这一步本身用了多久</p>
        </div>
        <span class="head-desc">已记录 {{ events.length }} 条</span>
      </div>
      <div class="surface-card__body">
        <div v-if="!events.length" class="empty-state">
          <strong>还没有流水</strong>
          让机器人收到一条消息，这里就会开始滚动。
        </div>
        <el-table v-else :data="events" style="width: 100%" max-height="560" size="small">
          <el-table-column label="时间" width="88">
            <template #default="{ row }">
              <span class="mono">{{ timeLabel(row.at) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="阶段" width="104">
            <template #default="{ row }">
              <el-tag :type="STAGE_COLOR[row.stage as FlowStage] || 'info'" size="small">
                {{ row.stageLabel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="160">
            <template #default="{ row }">
              <div class="chat-cell">
                <strong>{{ chatLabel(row) }}</strong>
                <span v-if="row.chatId && row.chatName" class="chat-cell__id">{{ row.chatId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="240">
            <template #default="{ row }">
              <span class="detail">{{ row.detail }}</span>
            </template>
          </el-table-column>
          <el-table-column label="等待 / 耗时" width="118">
            <template #default="{ row }">
              <span v-if="row.waitMs" class="metric metric--wait">{{ durationLabel(row.waitMs) }}</span>
              <span v-if="row.durationMs" class="metric metric--duration">{{ durationLabel(row.durationMs) }}</span>
              <span v-if="!row.waitMs && !row.durationMs" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="200">
            <template #default="{ row }">
              <span v-if="row.preview" class="preview">{{ row.preview }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.live-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  margin-bottom: 18px;
  border-radius: 14px;
  background: rgba(64, 158, 255, 0.08);
  color: var(--text-soft, #5b6b82);
  font-size: 13px;
}

.live-bar.paused {
  background: rgba(144, 147, 153, 0.12);
}

.live-bar__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3fbf7f;
  box-shadow: 0 0 0 4px rgba(63, 191, 127, 0.16);
  animation: pulse 1.6s infinite;
}

.live-bar.paused .live-bar__dot {
  background: #909399;
  box-shadow: none;
  animation: none;
}

.live-bar__hint {
  margin-left: auto;
  color: var(--muted, #96a1b3);
}

@keyframes pulse {
  0% { opacity: 1; }
  50% { opacity: 0.35; }
  100% { opacity: 1; }
}

.stat-card__hint {
  color: var(--muted, #96a1b3);
  font-size: 11px;
}

.wait-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.wait-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(64, 158, 255, 0.06);
}

.wait-item__main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.wait-item__reason {
  color: var(--text-soft, #5b6b82);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wait-item__meta {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex: 0 0 auto;
}

.wait-item__countdown {
  font-weight: 600;
  color: #e6a23c;
}

.wait-item__until {
  color: var(--muted, #96a1b3);
  font-size: 12px;
}

.chat-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-cell__id {
  color: var(--muted, #96a1b3);
  font-size: 11px;
}

.detail {
  color: var(--text-soft, #5b6b82);
}

.metric {
  display: inline-block;
  margin-right: 8px;
  font-variant-numeric: tabular-nums;
}

.metric--wait {
  color: #e6a23c;
}

.metric--duration {
  color: #67c23a;
}

.preview {
  color: var(--muted, #96a1b3);
}

.muted {
  color: var(--muted, #c0c4cc);
}

.mono {
  font-variant-numeric: tabular-nums;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.empty-state {
  padding: 26px 8px;
  text-align: center;
  color: var(--muted, #96a1b3);
  line-height: 1.9;
}

.empty-state strong {
  display: block;
  color: var(--text, #1f2d3d);
  margin-bottom: 4px;
}
</style>
