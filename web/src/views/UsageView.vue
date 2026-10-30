<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { UsageSummary } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const days = ref(7)
const usage = ref<UsageSummary | null>(null)

const totals = computed(() => usage.value?.totals)
const maxDaily = computed(() => {
  const series = usage.value?.daily ?? []
  return Math.max(1, ...series.map((item) => Math.max(item.inbound, item.outbound)))
})

const kpis = computed(() => {
  const t = totals.value
  if (!t) return []
  const replyRate = t.inbound > 0 ? Math.round((t.replied / t.inbound) * 100) : 0
  return [
    { label: '收到消息', value: formatNumber(t.inbound), hint: `${t.activeConversations} 个活跃会话` },
    { label: '已回复', value: formatNumber(t.replied), hint: `回复率 ${replyRate}%` },
    { label: '未发送', value: formatNumber(t.ignored), hint: '伪装层跳过 / 敏感词拦截' },
    { label: '失败', value: formatNumber(t.failed), hint: '模型或投递异常' },
    {
      label: 'Token 消耗',
      value: formatTokens(t.promptTokens + t.completionTokens),
      hint: `输入 ${formatTokens(t.promptTokens)} / 输出 ${formatTokens(t.completionTokens)}`,
    },
    { label: '平均耗时', value: `${t.avgLatencyMs} ms`, hint: '模型响应时间' },
  ]
})

const providerRows = computed(() => {
  const list = usage.value?.providers ?? []
  const totalReplies = list.reduce((sum, item) => sum + item.replies, 0) || 1
  return list.map((item) => ({
    ...item,
    share: Math.round((item.replies / totalReplies) * 100),
  }))
})

const costVisible = computed(() => (usage.value?.estimatedCost ?? 0) > 0)

async function load() {
  loading.value = true
  try {
    usage.value = await adminApi.usage(days.value)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function changeDays(next: number) {
  days.value = next
  await load()
}

function barHeight(value: number) {
  return `${Math.max(2, Math.round((value / maxDaily.value) * 100))}%`
}

function dayLabel(date: string) {
  return dayjs(date).format('MM-DD')
}

function formatNumber(value: number) {
  return value.toLocaleString('zh-CN')
}

function formatTokens(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`
  return String(value)
}

function formatCost(value: number) {
  return `¥ ${value.toFixed(4)}`
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>用量统计</h1>
        <p>收发量、token 消耗与响应耗时，数据直接取自消息记录，无需额外埋点。</p>
      </div>
      <div class="head-actions">
        <el-radio-group :model-value="days" size="small" @change="(value: string | number | boolean) => changeDays(Number(value))">
          <el-radio-button :label="7">7 天</el-radio-button>
          <el-radio-button :label="14">14 天</el-radio-button>
          <el-radio-button :label="30">30 天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div v-for="item in kpis" :key="item.label" class="kpi-card">
        <span class="kpi-label">{{ item.label }}</span>
        <strong class="kpi-value">{{ item.value }}</strong>
        <span class="kpi-hint">{{ item.hint }}</span>
      </div>
    </div>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>每日收发</h2>
          <p class="head-desc">
            {{ usage ? `${dayLabel(usage.daily[0]?.date || usage.from)} 至 ${dayLabel(usage.daily[usage.daily.length - 1]?.date || usage.to)}` : '' }}
          </p>
        </div>
        <div class="legend">
          <span><i class="dot inbound"></i>收到</span>
          <span><i class="dot outbound"></i>发出</span>
          <span v-if="costVisible" class="cost">预估成本 {{ formatCost(usage?.estimatedCost || 0) }}</span>
        </div>
      </div>
      <div class="surface-card__body">
        <div v-if="usage && usage.daily.length" class="chart">
          <div v-for="item in usage.daily" :key="item.date" class="chart-col">
            <div class="bars">
              <div
                class="bar inbound"
                :style="{ height: barHeight(item.inbound) }"
                :title="`${item.date} 收到 ${item.inbound} 条`"
              ></div>
              <div
                class="bar outbound"
                :style="{ height: barHeight(item.outbound) }"
                :title="`${item.date} 发出 ${item.outbound} 条`"
              ></div>
            </div>
            <span class="chart-label">{{ dayLabel(item.date) }}</span>
          </div>
        </div>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有用量数据</strong>
          收到第一条消息后这里就会开始统计。
        </div>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>模型明细</h2>
          <p class="head-desc">按 provider 与模型拆分，关键词规则回复也会单独统计。</p>
        </div>
      </div>
      <div class="surface-card__body">
        <el-table v-if="providerRows.length" :data="providerRows" style="width: 100%">
          <el-table-column label="Provider" min-width="140">
            <template #default="{ row }"><strong class="provider-name">{{ row.provider }}</strong></template>
          </el-table-column>
          <el-table-column label="模型" min-width="170">
            <template #default="{ row }"><span class="mono">{{ row.model }}</span></template>
          </el-table-column>
          <el-table-column label="回复数" width="96">
            <template #default="{ row }">
              <span class="mono">{{ row.replies }}</span>
              <div class="share"><i :style="{ width: `${row.share}%` }"></i></div>
            </template>
          </el-table-column>
          <el-table-column label="输入 Token" width="110" align="right">
            <template #default="{ row }"><span class="mono">{{ formatTokens(row.promptTokens) }}</span></template>
          </el-table-column>
          <el-table-column label="输出 Token" width="110" align="right">
            <template #default="{ row }"><span class="mono">{{ formatTokens(row.completionTokens) }}</span></template>
          </el-table-column>
          <el-table-column label="平均耗时" width="100" align="right">
            <template #default="{ row }"><span class="mono">{{ row.avgLatencyMs }} ms</span></template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有模型调用记录</strong>
          机器人回复过消息后，这里会显示各模型的用量分布。
        </div>
        <p v-if="usage && !costVisible" class="cost-hint">
          想看到预估成本？在「运行策略」里填写每千 token 单价即可（不填则只统计用量）。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.kpi-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: #fff;
}

.kpi-label {
  color: var(--muted);
  font-size: var(--fs-xs);
}

.kpi-value {
  color: var(--ink);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.4px;
}

.kpi-hint {
  color: var(--muted-soft);
  font-size: 10px;
}

.legend {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.legend .dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 4px;
  border-radius: 2px;
}

.dot.inbound {
  background: #93b4f7;
}

.dot.outbound {
  background: #3b82f6;
}

.cost {
  color: var(--ink-soft);
  font-weight: 550;
}

.chart {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 168px;
  padding-top: 6px;
}

.chart-col {
  display: flex;
  flex: 1;
  height: 100%;
  flex-direction: column;
  justify-content: flex-end;
  gap: 5px;
}

.bars {
  display: flex;
  height: 100%;
  align-items: flex-end;
  gap: 2px;
}

.bar {
  flex: 1;
  min-height: 2px;
  border-radius: 2px 2px 0 0;
  transition: opacity 0.15s ease;
}

.bar:hover {
  opacity: 0.75;
}

.bar.inbound {
  background: #93b4f7;
}

.bar.outbound {
  background: #3b82f6;
}

.chart-label {
  color: var(--muted-soft);
  font-size: 10px;
  text-align: center;
  white-space: nowrap;
}

.provider-name {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 550;
}

.share {
  height: 3px;
  margin-top: 3px;
  border-radius: 999px;
  background: #eef2f7;
}

.share i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #3b82f6;
}

.cost-hint {
  margin: 10px 0 0;
  color: var(--muted-soft);
  font-size: var(--fs-xs);
}
</style>
