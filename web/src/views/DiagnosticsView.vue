<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { DiagnosticsPayload, FailedOutbox } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const retrying = ref<string | null>(null)
const payload = ref<DiagnosticsPayload | null>(null)

const hook = computed(() => payload.value?.wechat.hook)
const callback = computed<Record<string, any>>(() => payload.value?.wechat.callback || {})
const guard = computed<Record<string, any>>(() => payload.value?.wechat.guard || {})
const guardRows = computed(() => {
  const status = guard.value.status
  if (!status || typeof status !== 'object') return []
  return Object.entries(status)
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
    .map(([key, value]) => ({ key, value: String(value) }))
})

async function load() {
  loading.value = true
  try {
    payload.value = await adminApi.diagnostics()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function retry(item: FailedOutbox) {
  retrying.value = item.id
  try {
    await adminApi.retryOutbox(item.id)
    ElMessage.success('已重新加入发送队列')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    retrying.value = null
  }
}

function timeLabel(value?: string) {
  return value ? dayjs(value).format('MM-DD HH:mm:ss') : '—'
}

function statusType(value?: string) {
  if (!value) return 'info'
  if (value.includes('ACCEPTED') || value === 'ok') return 'success'
  if (value.includes('DUPLICATE')) return 'info'
  return 'warning'
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>诊断中心</h1>
        <p>一眼看清微信钩子、回调链路与投递队列的状态，不用翻日志。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <section class="status-bar" :class="{ offline: hook && !hook.reachable }">
      <span class="status-bar__dot"></span>
      <div class="status-bar__copy">
        <strong>{{ hook ? (hook.reachable ? '钩子可达' : '钩子不可达') : '检查中' }}</strong>
        <span v-if="hook">
          {{ hook.baseUrl }} · 实例 {{ hook.instance || '未知' }} · 探活 {{ hook.latencyMs }}ms
          <template v-if="hook.error"> · {{ hook.error }}</template>
        </span>
      </div>
      <span class="status-bar__tag" :class="hook?.reachable ? 'ok' : 'warn'">
        {{ hook?.reachable ? 'ONLINE' : 'OFFLINE' }}
      </span>
    </section>

    <div class="diag-grid">
      <section class="surface-card">
        <div class="surface-card__head">
          <div>
            <h2>回调链路</h2>
            <p class="head-desc">钩子推送到后端的原始事件</p>
          </div>
        </div>
        <div class="surface-card__body">
          <ul class="metrics">
            <li><span>累计回调</span><b>{{ callback.total ?? 0 }}</b></li>
            <li><span>已接收</span><b class="ok">{{ callback.accepted ?? 0 }}</b></li>
            <li><span>已忽略</span><b>{{ callback.ignored ?? 0 }}</b></li>
            <li><span>最近事件</span><b class="muted">{{ timeLabel(callback.lastEventAt) }}</b></li>
          </ul>

          <el-table
            v-if="payload?.recentCallbacks.length"
            :data="payload.recentCallbacks"
            size="small"
            style="width: 100%"
          >
            <el-table-column label="时间" width="118">
              <template #default="{ row }"><span class="muted">{{ timeLabel(row.receivedAt) }}</span></template>
            </el-table-column>
            <el-table-column label="结果" width="96">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small" effect="light">
                  {{ row.status || '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="会话" min-width="150">
              <template #default="{ row }"><span class="mono small">{{ row.chatId || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="内容" min-width="180">
              <template #default="{ row }">
                <span class="clamp">{{ row.payload?.content || row.reason || '' }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else-if="!loading" class="empty-state">
            <strong>还没有回调</strong>
            微信收到新消息后会出现在这里。
          </div>
        </div>
      </section>

      <div class="side-stack">
        <section class="surface-card">
          <div class="surface-card__head">
            <div>
              <h2>失败投递</h2>
              <p class="head-desc">重试机会用完的消息</p>
            </div>
            <span class="count-badge" :class="{ warn: (payload?.outbox.failedCount || 0) > 0 }">
              {{ payload?.outbox.failedCount ?? 0 }}
            </span>
          </div>
          <div class="surface-card__body">
            <ul v-if="payload?.outbox.failed.length" class="failed-list">
              <li v-for="item in payload.outbox.failed" :key="item.id">
                <div class="failed-head">
                  <span class="mono small">{{ item.chatId }}</span>
                  <el-button
                    text
                    type="primary"
                    size="small"
                    :loading="retrying === item.id"
                    @click="retry(item)"
                  >
                    重试
                  </el-button>
                </div>
                <p class="failed-text">{{ item.content }}</p>
                <p class="failed-error">{{ item.lastError || '未记录错误' }} · 已尝试 {{ item.attempts }} 次</p>
              </li>
            </ul>
            <div v-else class="empty-state small-state">
              <strong>没有失败任务</strong>
            </div>
          </div>
        </section>

        <section class="surface-card">
          <div class="surface-card__head">
            <h2>守护进程</h2>
          </div>
          <div class="surface-card__body">
            <ul v-if="guardRows.length" class="check-list">
              <li v-for="row in guardRows" :key="row.key">
                <span>{{ row.key }}</span>
                <b class="muted">{{ row.value }}</b>
              </li>
            </ul>
            <div v-else class="empty-state small-state">
              <strong>暂无守护状态</strong>
              未启用计划任务时属于正常情况。
            </div>
          </div>
        </section>
      </div>
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

.status-bar__tag {
  flex: 0 0 auto;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.06em;
}

.status-bar__tag.ok {
  color: #15803d;
  background: #ecfdf3;
}

.status-bar__tag.warn {
  color: #b45309;
  background: #fff7ed;
}

.diag-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
  gap: 18px;
  align-items: start;
}

.side-stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.side-stack .surface-card + .surface-card {
  margin-top: 0;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
}

.metrics li {
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.metrics span {
  display: block;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.metrics b {
  display: block;
  margin-top: 4px;
  color: var(--ink);
  font-size: var(--fs-md);
  font-weight: 620;
}

.metrics .ok {
  color: var(--success);
}

.count-badge.warn {
  color: #b45309;
  background: #fff7ed;
}

.failed-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.failed-list li {
  padding: 12px 0;
  border-bottom: 1px solid var(--line-soft);
}

.failed-list li:last-child {
  border-bottom: 0;
}

.failed-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.failed-text {
  margin: 6px 0 4px;
  color: var(--ink-soft);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.failed-error {
  margin: 0;
  color: var(--danger);
  font-size: 11px;
  line-height: 1.5;
}

.clamp {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  font-size: var(--fs-xs);
  color: var(--ink-soft);
}

.small {
  font-size: 11px;
}

.small-state {
  padding: 20px 0;
}

@media (max-width: 1080px) {
  .diag-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
