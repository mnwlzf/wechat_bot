<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { MagicStick, Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { PageResult, ScheduledKind, ScheduledMessage, ScheduledRepeat } from '@/api/types'
import { getErrorMessage } from '@/api/client'
import ChatSelect from '@/components/ChatSelect.vue'

const loading = ref(false)
const saving = ref(false)
const parsing = ref(false)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const page = ref(0)
const pageSize = 20
const kindFilter = ref<'ALL' | ScheduledKind>('ALL')
const instruction = ref('')

const result = ref<PageResult<ScheduledMessage>>({
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: pageSize,
})

const form = reactive({
  kind: 'MESSAGE' as ScheduledKind,
  chatId: '',
  chatName: '',
  title: '',
  content: '',
  scheduledAt: '',
  repeatRule: 'NONE' as ScheduledRepeat,
  repeatUntil: '',
  cronExpression: '',
  splitLines: false,
  intervalMinutes: 5,
  maxTimes: 6,
  stopWords: '已打卡,打完了,好了',
  variants: '',
  finalMessage: '',
})

const formRules: FormRules = {
  chatId: [{ required: true, message: '请选择或填写会话', trigger: 'change' }],
  scheduledAt: [{ required: true, message: '请选择时间', trigger: 'change' }],
}

const statusLabels: Record<string, string> = {
  PENDING: '待发送',
  SENT: '已入队',
  ACKED: '已回执',
  EXPIRED: '已放弃',
  CANCELLED: '已取消',
  FAILED: '失败',
}

const statusTypes: Record<string, string> = {
  PENDING: 'warning',
  SENT: 'success',
  ACKED: 'success',
  EXPIRED: 'info',
  CANCELLED: 'info',
  FAILED: 'danger',
}

const repeatLabels: Record<ScheduledRepeat, string> = {
  NONE: '',
  DAILY: '每天',
  WEEKLY: '每周',
  CRON: '自定义',
}

const pendingCount = computed(
  () => result.value.content.filter((item) => item.status === 'PENDING').length,
)

const contentParts = computed(() => {
  if (!form.splitLines) return form.content.trim() ? 1 : 0
  return form.content.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).length
})

const variantLines = computed(() =>
  form.variants.split(/\r?\n/).map((line) => line.trim()).filter(Boolean),
)

async function load() {
  loading.value = true
  try {
    result.value = await adminApi.scheduledMessages(
      page.value,
      pageSize,
      kindFilter.value === 'ALL' ? undefined : kindFilter.value,
    )
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    kind: 'MESSAGE' as ScheduledKind,
    chatId: '',
    chatName: '',
    title: '',
    content: '',
    scheduledAt: dayjs().add(10, 'minute').format('YYYY-MM-DD HH:mm:ss'),
    repeatRule: 'NONE' as ScheduledRepeat,
    repeatUntil: '',
    cronExpression: '',
    splitLines: false,
    intervalMinutes: 5,
    maxTimes: 6,
    stopWords: '已打卡,打完了,好了',
    variants: '',
    finalMessage: '',
  })
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(item: ScheduledMessage) {
  editingId.value = item.id
  Object.assign(form, {
    kind: item.kind || 'MESSAGE',
    chatId: item.chatId,
    chatName: item.chatName,
    title: item.title || '',
    content: item.content || '',
    scheduledAt: dayjs(item.scheduledAt).format('YYYY-MM-DD HH:mm:ss'),
    repeatRule: item.repeatRule || 'NONE',
    repeatUntil: item.repeatUntil ? dayjs(item.repeatUntil).format('YYYY-MM-DD HH:mm:ss') : '',
    cronExpression: item.cronExpression || '',
    splitLines: item.splitLines,
    intervalMinutes: item.intervalMinutes || 5,
    maxTimes: item.maxTimes || 6,
    stopWords: item.stopWords || '',
    variants: item.variants || '',
    finalMessage: item.finalMessage || '',
  })
  dialogVisible.value = true
}

/** 一句话创建：先解析成草稿填进表单，人确认后再保存 */
async function parseInstruction() {
  const text = instruction.value.trim()
  if (!text) {
    ElMessage.warning('先写一句话，比如「每天下午六点提醒我打卡，没回就五分钟催一次」')
    return
  }
  parsing.value = true
  try {
    const draft = await adminApi.parseReminderInstruction(text)
    if (!draft.ok) {
      ElMessage.warning(draft.message || '没解析出来，手动填吧')
      return
    }
    editingId.value = null
    resetForm()
    form.kind = (draft.kind as ScheduledKind) || 'MESSAGE'
    form.title = draft.title || ''
    form.scheduledAt = draft.firstAt
      ? dayjs(draft.firstAt).format('YYYY-MM-DD HH:mm:ss')
      : form.scheduledAt
    form.repeatRule = (draft.repeatRule as ScheduledRepeat) || 'NONE'
    form.intervalMinutes = draft.intervalMinutes || 5
    form.maxTimes = draft.maxTimes || 6
    form.stopWords = draft.stopWords || ''
    form.variants = draft.variants || ''
    form.finalMessage = draft.finalMessage || ''
    form.content = variantLines.value[0] || ''
    dialogVisible.value = true
    ElMessage.success('解析好了，确认一下接收人和时间再保存')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    parsing.value = false
  }
}

function applyPreset(kind: '10m' | 'tonight' | 'tomorrow' | 'nextMonday') {
  const now = dayjs()
  if (kind === '10m') form.scheduledAt = now.add(10, 'minute').format('YYYY-MM-DD HH:mm:ss')
  if (kind === 'tonight') form.scheduledAt = now.hour(20).minute(0).second(0).format('YYYY-MM-DD HH:mm:ss')
  if (kind === 'tomorrow') form.scheduledAt = now.add(1, 'day').hour(9).minute(0).second(0).format('YYYY-MM-DD HH:mm:ss')
  if (kind === 'nextMonday') {
    const diff = (8 - now.day()) % 7 || 7
    form.scheduledAt = now.add(diff, 'day').hour(9).minute(0).second(0).format('YYYY-MM-DD HH:mm:ss')
  }
}

function onChatPicked(conversation: { chatId: string; chatName: string } | null) {
  form.chatName = conversation ? conversation.chatName : ''
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (form.kind === 'MESSAGE' && !form.content.trim()) {
    ElMessage.warning('请填消息内容')
    return
  }
  if (form.kind === 'REMINDER' && !form.title.trim() && !form.content.trim()) {
    ElMessage.warning('提醒要有个标题，比如「打卡」')
    return
  }
  saving.value = true
  try {
    const payload = {
      chatId: form.chatId,
      chatName: form.chatName || undefined,
      content: form.content.trim() || undefined,
      scheduledAt: dayjs(form.scheduledAt).toISOString(),
      repeatRule: form.repeatRule,
      repeatUntil: form.repeatUntil ? dayjs(form.repeatUntil).toISOString() : null,
      cronExpression: form.repeatRule === 'CRON' ? form.cronExpression.trim() : null,
      splitLines: form.kind === 'MESSAGE' ? form.splitLines : false,
      kind: form.kind,
      title: form.kind === 'REMINDER' ? form.title.trim() || undefined : null,
      intervalMinutes: form.kind === 'REMINDER' ? form.intervalMinutes : null,
      maxTimes: form.kind === 'REMINDER' ? form.maxTimes : null,
      stopWords: form.kind === 'REMINDER' ? form.stopWords.trim() || null : null,
      variants: form.kind === 'REMINDER' ? form.variants.trim() || null : null,
      finalMessage: form.kind === 'REMINDER' ? form.finalMessage.trim() || null : null,
    }
    if (editingId.value) {
      await adminApi.updateScheduledMessage(editingId.value, payload)
      ElMessage.success('已更新')
    } else if (form.kind === 'REMINDER') {
      await adminApi.createScheduledMessage(payload)
      ElMessage.success('提醒已建好，到点开始催')
    } else {
      await adminApi.createScheduledMessage(payload)
      ElMessage.success('已排入定时队列')
    }
    dialogVisible.value = false
    page.value = 0
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function cancel(item: ScheduledMessage) {
  try {
    await adminApi.cancelScheduledMessage(item.id)
    ElMessage.success('已取消')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function stop(item: ScheduledMessage) {
  try {
    await adminApi.stopScheduledMessage(item.id)
    ElMessage.success('已停止')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function remove(item: ScheduledMessage) {
  try {
    await ElMessageBox.confirm('删除后无法恢复，确定继续？', '删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteScheduledMessage(item.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function changePage(next: number) {
  page.value = next - 1
  load()
}

function changeKindFilter() {
  page.value = 0
  load()
}

function timeLabel(value: string) {
  return dayjs(value).format('MM-DD HH:mm')
}

function lineCount(content: string) {
  return content.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).length
}

function progress(row: ScheduledMessage) {
  const max = row.maxTimes || 0
  return max > 0 ? `已催 ${row.sentCount}/${max} 次` : `已发 ${row.sentCount} 次`
}

onMounted(async () => {
  await load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>定时任务</h1>
        <p>定时消息到点发一条；提醒会按间隔催，对方回话就停，最多催几次也由你定。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建</el-button>
      </div>
    </div>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>一句话创建</h2>
          <p class="head-desc">
            用大白话说就行，比如「每天下午六点提醒我打卡，没回就五分钟催一次，最多六次」。
            解析结果会填进表单，你确认后才真正生效。
          </p>
        </div>
      </div>
      <div class="surface-card__body">
        <div class="parse-row">
          <el-input
            v-model="instruction"
            placeholder="例：明天上午十点提醒我交报表 / 每天六点提醒我打卡，回「已打卡」就停"
            @keyup.enter="parseInstruction"
          />
          <el-button type="primary" :icon="MagicStick" :loading="parsing" @click="parseInstruction">
            解析
          </el-button>
        </div>
      </div>
    </section>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>任务列表</h2>
          <p class="head-desc">当前页等待中 {{ pendingCount }} 条，共 {{ result.totalElements }} 条记录</p>
        </div>
        <el-radio-group v-model="kindFilter" size="small" @change="changeKindFilter">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="MESSAGE">定时消息</el-radio-button>
          <el-radio-button value="REMINDER">提醒</el-radio-button>
        </el-radio-group>
      </div>
      <div class="surface-card__body">
        <el-table v-if="result.content.length" :data="result.content" style="width: 100%">
          <el-table-column label="类型" width="82">
            <template #default="{ row }">
              <el-tag :type="row.kind === 'REMINDER' ? 'primary' : 'info'" size="small" effect="plain">
                {{ row.kind === 'REMINDER' ? '提醒' : '消息' }}
              </el-tag>
              <span v-if="row.source === 'CHAT'" class="source-hint">聊天里建的</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">
              <span class="mono">{{ timeLabel(row.scheduledAt) }}</span>
              <el-tag
                v-if="row.repeatRule !== 'NONE'"
                size="small"
                type="warning"
                effect="plain"
                class="repeat-tag"
              >
                {{ repeatLabels[row.repeatRule as ScheduledRepeat] }}
              </el-tag>
              <span v-if="row.repeatRule === 'CRON' && row.cronExpression" class="mono cron-hint">
                {{ row.cronExpression }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="150">
            <template #default="{ row }">
              <strong class="chat-name">{{ row.chatName || row.chatId }}</strong>
              <span class="mono chat-id">{{ row.chatId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="230">
            <template #default="{ row }">
              <template v-if="row.kind === 'REMINDER'">
                <p class="content-clamp">{{ row.title || '提醒' }}</p>
                <span class="split-hint">
                  每 {{ row.intervalMinutes }} 分钟催一次，最多 {{ row.maxTimes }} 次 ·
                  停止词 {{ row.stopWords }}
                </span>
              </template>
              <template v-else>
                <p class="content-clamp">{{ row.content }}</p>
                <span v-if="row.splitLines" class="split-hint">分 {{ lineCount(row.content) }} 条发送</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="104">
            <template #default="{ row }">
              <el-tag :type="statusTypes[row.status]" size="small" effect="light">
                {{ statusLabels[row.status] || row.status }}
              </el-tag>
              <span v-if="row.sentCount > 0" class="sent-count">{{ progress(row) }}</span>
              <el-tooltip v-if="row.ackContent" :content="`回执：${row.ackContent}`" placement="top">
                <span class="ack-hint">说了「{{ row.ackContent }}」</span>
              </el-tooltip>
              <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
                <span class="error-dot">!</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="" width="196" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'PENDING'"
                text
                type="primary"
                size="small"
                @click="openEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                v-if="row.status === 'PENDING' && row.kind === 'MESSAGE'"
                text
                type="warning"
                size="small"
                @click="cancel(row)"
              >
                取消
              </el-button>
              <el-button
                v-if="row.status === 'PENDING' && row.kind === 'REMINDER'"
                text
                type="warning"
                size="small"
                @click="stop(row)"
              >
                停止
              </el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有任务</strong>
          提前写好群通知，或者让它到点催你打卡 —— 都可以用上面那句「一句话创建」。
        </div>

        <div v-if="result.totalPages > 1" class="pagination">
          <el-pagination
            background
            layout="prev, pager, next"
            :current-page="result.number + 1"
            :page-size="pageSize"
            :total="result.totalElements"
            @current-change="changePage"
          />
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑任务' : form.kind === 'REMINDER' ? '新建提醒' : '新建定时消息'"
      width="min(620px, 92vw)"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <el-form-item label="类型">
          <el-radio-group v-model="form.kind" :disabled="!!editingId">
            <el-radio-button value="MESSAGE">定时消息</el-radio-button>
            <el-radio-button value="REMINDER">提醒（等回执）</el-radio-button>
          </el-radio-group>
          <div class="field-hint">
            {{
              form.kind === 'REMINDER'
                ? '到点开始催，每 N 分钟一次，对方回话就停；最多催几次有上限。只支持私聊。'
                : '到点发一条；选每天/每周可以长期重复。'
            }}
          </div>
        </el-form-item>
        <el-form-item label="发送到" prop="chatId">
          <ChatSelect
            v-model="form.chatId"
            allow-create
            placeholder="搜群名 / 好友昵称 / chatId"
            @pick="onChatPicked"
          />
        </el-form-item>
        <el-form-item v-if="form.kind === 'REMINDER'" label="提醒什么">
          <el-input v-model="form.title" maxlength="120" placeholder="例如：打卡 / 吃药 / 交周报" />
        </el-form-item>
        <el-form-item :label="form.repeatRule === 'CRON' ? '首次时间' : '时间'" prop="scheduledAt">
          <el-date-picker
            v-model="form.scheduledAt"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择日期与时间"
            style="width: 100%"
          />
          <div class="preset-row">
            <el-button text size="small" @click="applyPreset('10m')">10 分钟后</el-button>
            <el-button text size="small" @click="applyPreset('tonight')">今天 20:00</el-button>
            <el-button text size="small" @click="applyPreset('tomorrow')">明天 09:00</el-button>
            <el-button text size="small" @click="applyPreset('nextMonday')">下周一 09:00</el-button>
          </div>
          <div class="field-hint">到点后最多延迟 20 秒进入投递队列，实际发送受伪装层配额影响。</div>
        </el-form-item>
        <el-form-item label="重复">
          <el-select v-model="form.repeatRule" style="width: 100%">
            <el-option label="不重复（只发一次）" value="NONE" />
            <el-option label="每天（同一时间）" value="DAILY" />
            <el-option label="每周（同一星期几与时间）" value="WEEKLY" />
            <el-option label="自定义 cron 表达式" value="CRON" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.repeatRule === 'CRON'" label="cron 表达式">
          <el-input
            v-model="form.cronExpression"
            placeholder="分 时 日 月 周，例如 0 9 * * 1-5"
            class="mono-input"
          />
          <div class="preset-row">
            <el-button text size="small" @click="form.cronExpression = '0 9 * * 1-5'">工作日 9:00</el-button>
            <el-button text size="small" @click="form.cronExpression = '30 20 * * *'">每天 20:30</el-button>
            <el-button text size="small" @click="form.cronExpression = '0 10 1 * *'">每月 1 号 10:00</el-button>
            <el-button text size="small" @click="form.cronExpression = '0 18 * * 5'">每周五 18:00</el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="form.repeatRule !== 'NONE'" label="重复截止（可选）">
          <el-date-picker
            v-model="form.repeatUntil"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="留空表示一直重复"
            style="width: 100%"
          />
        </el-form-item>

        <template v-if="form.kind === 'REMINDER'">
          <el-form-item label="催的间隔与次数">
            <div class="interval-row">
              <span>每</span>
              <el-input-number v-model="form.intervalMinutes" :min="5" :max="1440" :step="5" />
              <span>分钟催一次，最多</span>
              <el-input-number v-model="form.maxTimes" :min="1" :max="20" />
              <span>次</span>
            </div>
            <div class="field-hint">
              间隔最少 5 分钟、次数最多 20 次（服务端也会夹住，防止把对方刷屏）。
              半夜 00:30–07:30 不催也不计数，醒来接着催。
            </div>
          </el-form-item>
          <el-form-item label="什么话算「做完了」">
            <el-input v-model="form.stopWords" placeholder="已打卡,打完了,好了" />
            <div class="field-hint">
              逗号分隔。对方回其中任意一个（或者意思相近的话）就停止提醒。
            </div>
          </el-form-item>
          <el-form-item label="提醒文案（按你的人设写的，每行一句）">
            <el-input
              v-model="form.variants"
              type="textarea"
              :rows="4"
              placeholder="留空则发送时用模板；点上面的「一句话创建」会让模型按当前人设写几句"
            />
            <div class="field-hint">
              共 {{ variantLines.length }} 句，按次数依次使用，不会六次都是同一句。
            </div>
          </el-form-item>
          <el-form-item label="催满之后的收尾（可选）">
            <el-input v-model="form.finalMessage" maxlength="500" placeholder="算了，6 次都没回，我先不催了。" />
          </el-form-item>
        </template>

        <el-form-item v-else :label="'消息内容'" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="5000"
            show-word-limit
            :placeholder="form.splitLines ? '每行一条消息，按顺序依次发送' : '需要发送的文字内容'"
          />
          <div class="split-row">
            <el-switch v-model="form.splitLines" size="small" />
            <span>按行拆成多条发送</span>
            <span v-if="form.splitLines" class="split-count">共 {{ contentParts }} 条</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editingId ? '保存修改' : form.kind === 'REMINDER' ? '建好这个提醒' : '加入队列' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.parse-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.interval-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  color: var(--ink-soft);
  font-size: var(--fs-sm);
}

.chat-name {
  display: block;
  color: var(--ink);
  font-size: var(--fs-sm);
  font-weight: 550;
}

.chat-id {
  display: block;
  margin-top: 2px;
  color: var(--muted-soft);
  font-size: 10px;
}

.content-clamp {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  color: var(--ink-soft);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.split-hint {
  display: block;
  margin-top: 3px;
  color: var(--muted-soft);
  font-size: 10px;
}

.source-hint {
  display: block;
  margin-top: 4px;
  color: var(--muted-soft);
  font-size: 10px;
}

.repeat-tag {
  margin-top: 4px;
}

.cron-hint {
  display: block;
  margin-top: 3px;
  color: var(--muted-soft);
  font-size: 10px;
}

.mono-input {
  font-family: var(--mono);
}

.sent-count {
  display: block;
  margin-top: 4px;
  color: var(--muted-soft);
  font-size: 10px;
}

.ack-hint {
  display: block;
  margin-top: 4px;
  color: var(--muted-soft);
  font-size: 10px;
}

.error-dot {
  display: inline-grid;
  width: 14px;
  height: 14px;
  margin-top: 4px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: #dc2626;
  font-size: 10px;
  font-weight: 700;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: 4px;
}

.split-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.split-count {
  color: var(--ink-soft);
}

.pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}
</style>
