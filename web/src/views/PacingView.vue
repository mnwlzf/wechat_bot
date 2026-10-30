<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { DisguiseConfig, DisguisePreview, DisguiseStatus } from '@/api/types'
import { getErrorMessage } from '@/api/client'

interface PacingItem {
  label: string
  value: string
  description: string
  env: string
}

const loading = ref(false)
const previewLoading = ref(false)
const savingAway = ref(false)
const config = ref<DisguiseConfig | null>(null)
const status = ref<DisguiseStatus | null>(null)
const awayBlocksEnabled = ref(true)
const awayBlocks = ref<Array<{ start: string; end: string }>>([])
const previewText = ref('好的，我整理一下，稍后发你。')
const previewType = ref<'PRIVATE' | 'GROUP'>('PRIVATE')
const previewResult = ref<DisguisePreview | null>(null)

const stateLabels: Record<string, string> = {
  ACTIVE: '活跃在线',
  AWAY: '离开手机',
  ASLEEP: '睡眠窗口',
}

const stateLabel = computed(() => stateLabels[status.value?.state || ''] || status.value?.state || '未知')
const enabled = computed(() => Boolean(config.value?.enabled))
const nextActive = computed(() => {
  if (!status.value?.nextActiveAt) return '—'
  return dayjs(status.value.nextActiveAt).format('MM-DD HH:mm:ss')
})

const awayBlocksDirty = computed(() => {
  const c = config.value
  if (!c) return false
  const current = awayBlocks.value.map((block) => `${block.start}-${block.end}`).sort().join(',')
  const saved = [...c.awayBlocks].sort().join(',')
  return c.awayBlocksEnabled !== awayBlocksEnabled.value || current !== saved
})

const summary = computed(() => {
  const c = config.value
  const s = status.value
  if (!c) return []
  return [
    {
      label: '单条拟人延迟',
      value: `${seconds(c.readDelayMinMs)} – ${seconds(c.maxDelayMs)}`,
      note: '从收到消息到首条发出的范围',
    },
    {
      label: '单会话日上限',
      value: `${c.perDay} 条`,
      note: `${c.perMinute}/分钟 · ${c.per10Minutes}/10分钟 · ${c.perHour}/小时`,
    },
    {
      label: '账号日上限',
      value: `${c.globalPerDay} 条`,
      note: `${c.globalPerHour}/小时 · 所有会话共享`,
    },
    {
      label: '今日账号已发',
      value: `${s?.globalSentToday ?? 0} / ${c.globalPerDay}`,
      note: '滚动窗口统计，不按自然日零点重置',
    },
  ]
})

const delayItems = computed<PacingItem[]>(() => {
  const c = config.value
  if (!c) return []
  return [
    {
      label: '读消息延迟',
      value: `${seconds(c.readDelayMinMs)} – ${seconds(c.readDelayMaxMs)}`,
      description: '收到消息后先等待一段随机阅读时间。使用右偏分布：多数消息几秒内处理，少数会明显更慢。',
      env: 'DISGUISE_READ_DELAY_MIN_MS / DISGUISE_READ_DELAY_MAX_MS',
    },
    {
      label: '思考时间',
      value: `${ms(c.thinkBaseMs)} + 每字 9ms（最多 4.5 秒）`,
      description: '模拟理解问题的时间。基础思考时间固定，再按回复长度增加，但设有上限。',
      env: 'DISGUISE_THINK_BASE_MS',
    },
    {
      label: '打字速度',
      value: `${c.charsPerSecond} 字/秒，抖动 ±${percent(c.typingJitter)}`,
      description: '按回复字数计算打字耗时。同一会话手速保持稳定，不同会话略有差异，避免每条都同样快慢。',
      env: 'DISGUISE_CHARS_PER_SECOND / DISGUISE_TYPING_JITTER',
    },
    {
      label: '单条最大延迟',
      value: seconds(c.maxDelayMs),
      description: '拟人延迟的硬上限。读、想、打字、长停顿全部叠加后，也不会超过此时间。',
      env: 'DISGUISE_MAX_DELAY_MS',
    },
    {
      label: '群聊延迟系数',
      value: `× ${c.groupDelayFactor}`,
      description: '群聊需要先看上下文，整体延迟在私聊基础上乘以该系数。',
      env: 'DISGUISE_GROUP_DELAY_FACTOR',
    },
    {
      label: '长停顿',
      value: `${percent(c.longPauseProbability)} 概率，最长 ${seconds(c.longPauseMaxMs)}`,
      description: '小概率模拟“去忙别的了”，在正常延迟之外额外增加 15 秒到上限之间的停顿。',
      env: 'DISGUISE_LONG_PAUSE_PROBABILITY / DISGUISE_LONG_PAUSE_MAX_MS',
    },
    {
      label: '随机离开手机',
      value: `${percent(c.awayProbability)} 概率，${seconds(c.awayMinMs)} – ${seconds(c.awayMaxMs)}`,
      description: '小概率模拟离开手机，额外增加较长延迟。该延迟同样受单条最大延迟限制。',
      env: 'DISGUISE_AWAY_PROBABILITY / DISGUISE_AWAY_MIN_MS / DISGUISE_AWAY_MAX_MS',
    },
    {
      label: '睡眠窗口',
      value: `${c.quietStart} – ${c.quietEnd}（${c.zone}）`,
      description: `睡眠期间的消息顺延到下一次活跃时间；最长顺延 ${c.maxDeferMinutes} 分钟，超过则丢弃。`,
      env: 'DISGUISE_QUIET_START / DISGUISE_QUIET_END / DISGUISE_MAX_DEFER_MINUTES',
    },
    {
      label: '手动离线段',
      value: c.awayBlocksEnabled
        ? (c.awayBlocks.length ? c.awayBlocks.join('、') : '已开启，未配置时间段')
        : '已关闭',
      description: '由你手动维护每日离线时间段。独立开关关闭后，此规则不生效，但睡眠窗口仍按原日程执行。',
      env: 'DISGUISE_AWAY_BLOCKS_ENABLED / DISGUISE_AWAY_BLOCKS',
    },
    {
      label: '回复分条',
      value: `${c.maxParts} 条以内，每条约 ${c.maxPartChars} 字`,
      description: `总回复最多 ${c.maxReplyChars} 字；条间间隔 ${seconds(c.partGapMinMs)} – ${seconds(c.partGapMaxMs)}，并按下一段字数继续增加。`,
      env: 'DISGUISE_MAX_PARTS / DISGUISE_MAX_PART_CHARS / DISGUISE_MAX_REPLY_CHARS / DISGUISE_PART_GAP_MIN_MS / DISGUISE_PART_GAP_MAX_MS',
    },
  ]
})

const frequencyItems = computed<PacingItem[]>(() => {
  const c = config.value
  if (!c) return []
  return [
    {
      label: '单会话每分钟',
      value: `${c.perMinute} 条`,
      description: '同一会话在最近 60 秒内最多发送的条数。超过后，下一条自动推迟到窗口释放。',
      env: 'DISGUISE_PER_MINUTE',
    },
    {
      label: '单会话每 10 分钟',
      value: `${c.per10Minutes} 条`,
      description: '抑制短时间连续对话爆发。与每分钟限制同时生效，不是二选一。',
      env: 'DISGUISE_PER_10MIN',
    },
    {
      label: '单会话每小时',
      value: `${c.perHour} 条`,
      description: '限制同一联系人在一小时内的总发送量，避免长时间高频对聊。',
      env: 'DISGUISE_PER_HOUR',
    },
    {
      label: '单会话每天',
      value: `${c.perDay} 条`,
      description: '单联系人滚动 24 小时上限。达到后必须等待最早一条发送记录退出窗口。',
      env: 'DISGUISE_PER_DAY',
    },
    {
      label: '账号每小时',
      value: `${c.globalPerHour} 条`,
      description: '所有会话共享的账号级小时上限，用于防止跨会话群发。',
      env: 'DISGUISE_GLOBAL_PER_HOUR',
    },
    {
      label: '账号每天',
      value: `${c.globalPerDay} 条`,
      description: '所有会话共享的账号级 24 小时上限，优先级高于单会话配额。',
      env: 'DISGUISE_GLOBAL_PER_DAY',
    },
    {
      label: '同会话最小间隔',
      value: seconds(c.minGapMs),
      description: '同一会话相邻两条消息至少间隔该时间，避免两个气泡几乎同时弹出。',
      env: 'DISGUISE_MIN_GAP_MS',
    },
    {
      label: '连续发送后的强制休息',
      value: `${c.burstSize} 条后休息`,
      description: '最近 10 分钟内达到连续发送条数后，强制进入冷却。',
      env: 'DISGUISE_BURST_SIZE',
    },
    {
      label: '冷却时长',
      value: `${seconds(c.burstCooldownMinMs)} – ${seconds(c.burstCooldownMaxMs)}`,
      description: '冷却时长按会话稳定取值，避免每次都在同一秒恢复发送。',
      env: 'DISGUISE_BURST_COOLDOWN_MIN_MS / DISGUISE_BURST_COOLDOWN_MAX_MS',
    },
    {
      label: '今日账号已发',
      value: `${status.value?.globalSentToday ?? 0} / ${c.globalPerDay}`,
      description: '当前账号滚动 24 小时内的发送计数，用于直观判断距离账号日上限还有多少空间。',
      env: '实时统计',
    },
  ]
})

function applyAwayConfig(value: DisguiseConfig) {
  awayBlocksEnabled.value = value.awayBlocksEnabled
  awayBlocks.value = value.awayBlocks.flatMap((block) => {
    const separator = block.indexOf('-')
    if (separator <= 0) return []
    return [{ start: block.slice(0, separator), end: block.slice(separator + 1) }]
  })
}

function addAwayBlock() {
  if (awayBlocks.value.length >= 6) return
  const defaults = [
    { start: '09:00', end: '09:40' },
    { start: '12:00', end: '12:40' },
    { start: '15:00', end: '15:40' },
    { start: '18:00', end: '18:40' },
    { start: '21:00', end: '21:40' },
    { start: '23:00', end: '23:40' },
  ]
  awayBlocks.value.push({ ...defaults[awayBlocks.value.length] })
}

function removeAwayBlock(index: number) {
  awayBlocks.value.splice(index, 1)
}

async function saveAwayBlocks() {
  if (!config.value) return
  if (awayBlocks.value.some((block) => !block.start || !block.end)) {
    ElMessage.warning('请补全每个离线块的开始和结束时间')
    return
  }
  const blocks = awayBlocks.value.map((block) => `${block.start}-${block.end}`)
  if (awayBlocksEnabled.value && blocks.length === 0) {
    ElMessage.warning('开启离开手机后至少需要配置一个时间段')
    return
  }

  savingAway.value = true
  try {
    const updated = await adminApi.updateDisguiseAwayBlocks({
      enabled: awayBlocksEnabled.value,
      blocks,
    })
    config.value = updated
    applyAwayConfig(updated)
    try {
      status.value = await adminApi.disguiseStatus()
    } catch {
      // 配置已保存；状态刷新失败不覆盖成功提示。
    }
    ElMessage.success('离线块已保存并立即生效')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingAway.value = false
  }
}

async function load() {
  loading.value = true
  const [configResult, statusResult] = await Promise.allSettled([
    adminApi.disguiseConfig(),
    adminApi.disguiseStatus(),
  ])
  if (configResult.status === 'fulfilled') {
    config.value = configResult.value
    applyAwayConfig(configResult.value)
  } else {
    ElMessage.error(getErrorMessage(configResult.reason))
  }
  if (statusResult.status === 'fulfilled') {
    status.value = statusResult.value
  }
  loading.value = false
}

async function runPreview() {
  if (!previewText.value.trim()) {
    ElMessage.warning('请输入一段回复内容')
    return
  }
  previewLoading.value = true
  try {
    previewResult.value = await adminApi.disguisePreview({
      chatType: previewType.value,
      text: previewText.value,
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    previewLoading.value = false
  }
}

function seconds(value: number) {
  if (value >= 60000) {
    return `${(value / 60000).toFixed(value % 60000 === 0 ? 0 : 1)} 分钟`
  }
  return `${(value / 1000).toFixed(value % 1000 === 0 ? 0 : 1)} 秒`
}

function ms(value: number) {
  return `${value} ms`
}

function percent(value: number) {
  return `${(value * 100).toFixed(0)}%`
}

function decisionLabel(decision: string) {
  const labels: Record<string, string> = {
    SEND: '发送',
    DROP_RANDOM: '概率不回',
    DROP_DEFER: '顺延超限',
    DROP_REPEAT: '重复拦截',
    EMPTY: '内容为空',
  }
  return labels[decision] || decision
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>发信节奏</h1>
        <p>集中查看发信延迟、发送频率、配额、睡眠窗口和分条规则；离开手机的离线块可在这里手动配置。</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="status-strip" :class="{ off: !enabled }">
      <span class="status-strip__dot"></span>
      <div class="status-strip__copy">
        <strong>{{ enabled ? '拟人发信已启用' : '拟人发信已关闭' }}</strong>
        <span>
          当前状态：{{ stateLabel }} · 时区 {{ config?.zone || '—' }} · 下次活跃 {{ nextActive }}
          <template v-if="status"> · 今日账号已发 {{ status.globalSentToday }} 条</template>
        </span>
      </div>
      <span class="status-strip__tag">{{ enabled ? 'ENABLED' : 'OFF' }}</span>
    </section>

    <div class="summary-grid">
      <article v-for="item in summary" :key="item.label" class="summary-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.note }}</small>
      </article>
    </div>

    <section class="surface-card away-card">
      <div class="surface-card__head">
        <div>
          <h2>离开手机（手动离线块）</h2>
          <p class="head-desc">按每日固定时间段暂停回复。关闭此开关只会停用离线块，不会影响睡眠窗口。</p>
        </div>
        <span class="count-badge">{{ awayBlocks.length }} / 6 段</span>
      </div>
      <div class="surface-card__body">
        <div class="away-toolbar">
          <div class="away-toolbar__copy">
            <strong>启用离线块</strong>
            <span>开启后，命中时间段的消息会顺延到离线结束；关闭后账号在白天不再因离线块暂停。</span>
          </div>
          <el-switch
            v-model="awayBlocksEnabled"
            :disabled="savingAway"
            active-text="开启"
            inactive-text="关闭"
          />
        </div>

        <div v-if="awayBlocks.length" class="away-list">
          <div v-for="(block, index) in awayBlocks" :key="index" class="away-row">
            <span class="away-row__index">{{ index + 1 }}</span>
            <el-time-picker
              v-model="block.start"
              format="HH:mm"
              value-format="HH:mm"
              :clearable="false"
              :disabled="savingAway"
              placeholder="开始"
            />
            <span class="away-row__sep">至</span>
            <el-time-picker
              v-model="block.end"
              format="HH:mm"
              value-format="HH:mm"
              :clearable="false"
              :disabled="savingAway"
              placeholder="结束"
            />
            <el-button text type="danger" :disabled="savingAway" @click="removeAwayBlock(index)">
              删除
            </el-button>
          </div>
        </div>
        <div v-else class="away-empty">尚未配置离线时间段。添加后保存即可生效。</div>

        <div class="away-actions">
          <span>每日重复 · 最多 6 段 · 支持跨零点（例如 23:30-00:30）</span>
          <div class="away-actions__buttons">
            <el-button size="small" :disabled="awayBlocks.length >= 6 || savingAway" @click="addAwayBlock">
              添加时间段
            </el-button>
            <el-button
              type="primary"
              size="small"
              :disabled="!awayBlocksDirty || savingAway"
              :loading="savingAway"
              @click="saveAwayBlocks"
            >
              保存并生效
            </el-button>
          </div>
        </div>
      </div>
    </section>

    <div class="pacing-grid">
      <section class="surface-card">
        <div class="surface-card__head">
          <div>
            <h2>发信延迟</h2>
            <p class="head-desc">决定一条回复多久后发出。读消息、思考、打字、昼夜节律、长停顿和睡眠窗口都会参与计算。</p>
          </div>
          <span class="count-badge">{{ delayItems.length }} 项</span>
        </div>
        <div class="pacing-list">
          <article v-for="item in delayItems" :key="item.label" class="pacing-item">
            <div class="pacing-item__head">
              <strong>{{ item.label }}</strong>
              <code>{{ item.value }}</code>
            </div>
            <p>{{ item.description }}</p>
            <small class="mono">{{ item.env }}</small>
          </article>
        </div>
      </section>

      <section class="surface-card">
        <div class="surface-card__head">
          <div>
            <h2>发信频率</h2>
            <p class="head-desc">决定一段时间内最多能发多少条。单会话、账号级、最小间隔和爆发冷却会同时检查。</p>
          </div>
          <span class="count-badge">{{ frequencyItems.length }} 项</span>
        </div>
        <div class="pacing-list">
          <article v-for="item in frequencyItems" :key="item.label" class="pacing-item">
            <div class="pacing-item__head">
              <strong>{{ item.label }}</strong>
              <code>{{ item.value }}</code>
            </div>
            <p>{{ item.description }}</p>
            <small class="mono">{{ item.env }}</small>
          </article>
        </div>
      </section>
    </div>

    <section class="surface-card rule-card">
      <div class="surface-card__head">
        <div>
          <h2>执行顺序与最终规则</h2>
          <p class="head-desc">延迟和频率不是相加，而是分别计算后取“最晚可发送时间”。</p>
        </div>
      </div>
      <div class="surface-card__body">
        <div class="formula">
          最终发送时间 = max（拟人延迟，配额允许时间，睡眠 / 离线结束时间）
        </div>
        <ol class="rule-list">
          <li><b>清洗与分条</b><span>先做文风清洗、复读检查和按句分条。</span></li>
          <li><b>计算基础延迟</b><span>读消息 + 思考 + 按字数打字，再叠加昼夜节律和群聊系数。</span></li>
          <li><b>叠加随机停顿</b><span>按概率增加长停顿或离开手机时间，并受单条最大延迟限制。</span></li>
          <li><b>检查作息</b><span>命中睡眠窗口或已开启的手动离线段时，顺延到下一次活跃时间。</span></li>
          <li><b>检查配额</b><span>单会话分钟/10分钟/小时/天和账号小时/天同时检查，取最晚释放时间。</span></li>
          <li><b>记录并发送</b><span>分条消息按间隔依次发出，每条都占用对应窗口的配额。</span></li>
        </ol>
        <div class="note-line">
          修改方式：离线块可在上方直接编辑并立即生效；其余值来自后端环境变量，调整后重启后端生效。
        </div>
      </div>
    </section>

    <div class="lower-grid">
      <section class="surface-card">
        <div class="surface-card__head">
          <div>
            <h2>延迟试算</h2>
            <p class="head-desc">输入一段回复，预览清洗、分条和延迟结果，不占用真实配额。</p>
          </div>
        </div>
        <div class="surface-card__body">
          <el-input
            v-model="previewText"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="输入一段模型回复"
          />
          <div class="preview-actions">
            <el-radio-group v-model="previewType" size="small">
              <el-radio-button label="PRIVATE">私聊</el-radio-button>
              <el-radio-button label="GROUP">群聊</el-radio-button>
            </el-radio-group>
            <el-button type="primary" size="small" :loading="previewLoading" @click="runPreview">试算</el-button>
          </div>
          <div v-if="previewResult" class="preview-result">
            <div class="preview-result__head">
              <strong>{{ decisionLabel(previewResult.decision) }}</strong>
              <span>AI 味评分 {{ previewResult.aiScore }}</span>
            </div>
            <p>{{ previewResult.note || '已完成延迟和分条计算' }}</p>
            <ol>
              <li v-for="(part, index) in previewResult.parts" :key="index">
                <b>第 {{ index + 1 }} 条 · 约 {{ part.delaySeconds }} 秒</b>
                <span>{{ part.text }}</span>
              </li>
            </ol>
            <small v-if="previewResult.dropped">当前策略会丢弃这条回复，不会发送。</small>
          </div>
        </div>
      </section>

      <section class="surface-card">
        <div class="surface-card__head">
          <div>
            <h2>最近发信决策</h2>
            <p class="head-desc">最近的实际延迟、分条与丢弃记录，便于快速定位节奏问题。</p>
          </div>
          <span class="count-badge">{{ status?.recent.length || 0 }} 条</span>
        </div>
        <div class="surface-card__body recent-body">
          <el-table v-if="status?.recent.length" :data="status.recent.slice(0, 8)" size="small" style="width: 100%">
            <el-table-column label="时间" width="92">
              <template #default="{ row }">{{ dayjs(row.at).format('MM-DD HH:mm') }}</template>
            </el-table-column>
            <el-table-column prop="chatId" label="会话" min-width="120" show-overflow-tooltip />
            <el-table-column label="决策" width="82">
              <template #default="{ row }">{{ decisionLabel(row.decision) }}</template>
            </el-table-column>
            <el-table-column label="延迟" width="72">
              <template #default="{ row }">{{ row.delaySeconds }}s</template>
            </el-table-column>
            <el-table-column prop="parts" label="分条" width="58" />
          </el-table>
          <div v-else class="empty-state compact-empty">
            <strong>还没有发信记录</strong>
            收到消息并完成一次拟人发信后，这里会显示决策。
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.status-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid #d7e7ff;
  border-radius: var(--radius-sm);
  background: #f6f9ff;
}

.status-strip.off {
  border-color: #fde2c8;
  background: #fffaf4;
}

.status-strip__dot {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.12);
}

.status-strip.off .status-strip__dot {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.14);
}

.status-strip__copy {
  min-width: 0;
  flex: 1;
}

.status-strip__copy strong {
  display: block;
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 650;
}

.status-strip__copy span {
  display: block;
  overflow: hidden;
  margin-top: 1px;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-strip__tag {
  flex: 0 0 auto;
  padding: 2px 7px;
  border-radius: 999px;
  color: #15803d;
  background: #ecfdf3;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.status-strip.off .status-strip__tag {
  color: #b45309;
  background: #fff7ed;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.summary-card {
  padding: 10px 13px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: #fff;
}

.summary-card span,
.summary-card small {
  display: block;
  color: var(--muted);
  font-size: 10px;
}

.summary-card strong {
  display: block;
  margin: 2px 0;
  color: var(--ink);
  font-size: 18px;
  font-weight: 680;
  letter-spacing: -0.02em;
}

.away-card {
  margin-bottom: 12px;
}

.away-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 10px 12px;
  border: 1px solid #dbeafe;
  border-radius: var(--radius-sm);
  background: #f5f9ff;
}

.away-toolbar__copy {
  min-width: 0;
}

.away-toolbar__copy strong,
.away-toolbar__copy span {
  display: block;
}

.away-toolbar__copy strong {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 650;
}

.away-toolbar__copy span {
  margin-top: 2px;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.5;
}

.away-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.away-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.away-row__index {
  display: grid;
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  color: #1d4ed8;
  background: #eff6ff;
  font-size: 10px;
  font-weight: 700;
}

.away-row :deep(.el-date-editor.el-input) {
  width: 122px;
}

.away-row__sep {
  color: var(--muted);
  font-size: 10px;
}

.away-empty {
  margin-top: 10px;
  padding: 14px;
  border: 1px dashed var(--line);
  border-radius: var(--radius-sm);
  color: var(--muted);
  font-size: 10px;
  text-align: center;
}

.away-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.away-actions > span {
  color: var(--muted-soft);
  font-size: 10px;
}

.away-actions__buttons {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.pacing-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.pacing-list {
  padding: 0 16px 8px;
}

.pacing-item {
  padding: 9px 0;
  border-bottom: 1px solid var(--line-soft);
}

.pacing-item:last-child {
  border-bottom: 0;
}

.pacing-item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.pacing-item__head strong {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 650;
}

.pacing-item__head code {
  flex: 0 0 auto;
  padding: 2px 6px;
  border-radius: 4px;
  color: #1d4ed8;
  background: #eff6ff;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 10px;
}

.pacing-item p {
  margin: 3px 0 2px;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.pacing-item > small {
  display: block;
  overflow: hidden;
  color: var(--muted-soft);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-card {
  margin-top: 12px;
}

.formula {
  margin-bottom: 10px;
  padding: 9px 12px;
  border: 1px solid #dbeafe;
  border-radius: var(--radius-sm);
  color: #1e40af;
  background: #f5f9ff;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
}

.rule-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: rule;
}

.rule-list li {
  position: relative;
  padding: 7px 0 7px 28px;
  border-bottom: 1px solid var(--line-soft);
  counter-increment: rule;
}

.rule-list li::before {
  position: absolute;
  top: 8px;
  left: 0;
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  border-radius: 50%;
  color: #1d4ed8;
  background: #eff6ff;
  content: counter(rule);
  font-size: 9px;
  font-weight: 700;
}

.rule-list b,
.rule-list span {
  display: block;
}

.rule-list b {
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.rule-list span {
  margin-top: 1px;
  color: var(--muted);
  font-size: 10px;
}

.note-line {
  margin-top: 10px;
  color: var(--muted-soft);
  font-size: 10px;
}

.lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  margin-top: 12px;
  align-items: start;
}

.preview-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 8px;
}

.preview-result {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  background: #fbfcfe;
}

.preview-result__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--ink);
  font-size: var(--fs-sm);
}

.preview-result__head span {
  color: var(--muted-soft);
  font-size: 10px;
}

.preview-result p {
  margin: 4px 0 6px;
  color: var(--muted);
  font-size: 10px;
}

.preview-result ol {
  margin: 0;
  padding-left: 18px;
}

.preview-result li {
  padding: 4px 0;
  color: var(--ink-soft);
  font-size: 10px;
}

.preview-result li b,
.preview-result li span {
  display: block;
}

.preview-result li span {
  color: var(--muted);
}

.preview-result > small {
  display: block;
  margin-top: 6px;
  color: var(--danger);
  font-size: 10px;
}

.recent-body {
  padding-bottom: 10px;
}

.compact-empty {
  padding: 22px 12px;
}

@media (max-width: 1100px) {
  .pacing-grid,
  .lower-grid {
    grid-template-columns: 1fr;
  }

  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 680px) {
  .summary-grid,
  .rule-list {
    grid-template-columns: 1fr;
  }

  .pacing-item__head {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .pacing-item__head code {
    white-space: normal;
  }

  .away-toolbar,
  .away-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .away-row {
    flex-wrap: wrap;
  }

  .away-actions__buttons {
    width: 100%;
  }

  .away-actions__buttons .el-button {
    flex: 1;
  }
}
</style>
