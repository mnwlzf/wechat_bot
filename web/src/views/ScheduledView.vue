<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { PageResult, ScheduledMessage, ScheduledRepeat } from '@/api/types'
import { getErrorMessage } from '@/api/client'
import ChatSelect from '@/components/ChatSelect.vue'

const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const page = ref(0)
const pageSize = 20

const result = ref<PageResult<ScheduledMessage>>({
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: pageSize,
})

const form = reactive({
  chatId: '',
  chatName: '',
  content: '',
  scheduledAt: '',
  repeatRule: 'NONE' as ScheduledRepeat,
  repeatUntil: '',
  cronExpression: '',
  splitLines: false,
})

const formRules: FormRules = {
  chatId: [{ required: true, message: '请选择或填写会话', trigger: 'change' }],
  content: [{ required: true, message: '请输入消息内容', trigger: 'blur' }],
  scheduledAt: [{ required: true, message: '请选择发送时间', trigger: 'change' }],
}

const statusLabels: Record<string, string> = {
  PENDING: '待发送',
  SENT: '已入队',
  CANCELLED: '已取消',
  FAILED: '失败',
}

const statusTypes: Record<string, string> = {
  PENDING: 'warning',
  SENT: 'success',
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

async function load() {
  loading.value = true
  try {
    result.value = await adminApi.scheduledMessages(page.value, pageSize)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    chatId: '',
    chatName: '',
    content: '',
    scheduledAt: dayjs().add(10, 'minute').format('YYYY-MM-DD HH:mm:ss'),
    repeatRule: 'NONE' as ScheduledRepeat,
    repeatUntil: '',
    cronExpression: '',
    splitLines: false,
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
    chatId: item.chatId,
    chatName: item.chatName,
    content: item.content,
    scheduledAt: dayjs(item.scheduledAt).format('YYYY-MM-DD HH:mm:ss'),
    repeatRule: item.repeatRule || 'NONE',
    repeatUntil: item.repeatUntil ? dayjs(item.repeatUntil).format('YYYY-MM-DD HH:mm:ss') : '',
    cronExpression: item.cronExpression || '',
    splitLines: item.splitLines,
  })
  dialogVisible.value = true
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
  saving.value = true
  try {
    const payload = {
      chatId: form.chatId,
      chatName: form.chatName || undefined,
      content: form.content,
      scheduledAt: dayjs(form.scheduledAt).toISOString(),
      repeatRule: form.repeatRule,
      repeatUntil: form.repeatUntil ? dayjs(form.repeatUntil).toISOString() : null,
      cronExpression: form.repeatRule === 'CRON' ? form.cronExpression.trim() : null,
      splitLines: form.splitLines,
    }
    if (editingId.value) {
      await adminApi.updateScheduledMessage(editingId.value, payload)
      ElMessage.success('定时消息已更新')
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

async function remove(item: ScheduledMessage) {
  try {
    await ElMessageBox.confirm('删除后无法恢复，确定继续？', '删除定时消息', {
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

function timeLabel(value: string) {
  return dayjs(value).format('MM-DD HH:mm')
}

function lineCount(content: string) {
  return content.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).length
}

onMounted(async () => {
  await load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>定时消息</h1>
        <p>自己填时间、接收人和内容，到点自动进入投递队列，走同一套重试与伪装节奏。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建定时消息</el-button>
      </div>
    </div>

    <section class="surface-card">
      <div class="surface-card__head">
        <div>
          <h2>计划列表</h2>
          <p class="head-desc">当前页待发送 {{ pendingCount }} 条，共 {{ result.totalElements }} 条记录</p>
        </div>
      </div>
      <div class="surface-card__body">
        <el-table v-if="result.content.length" :data="result.content" style="width: 100%">
          <el-table-column label="发送时间" width="150">
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
          <el-table-column label="会话" min-width="170">
            <template #default="{ row }">
              <strong class="chat-name">{{ row.chatName || row.chatId }}</strong>
              <span class="mono chat-id">{{ row.chatId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="240">
            <template #default="{ row }">
              <p class="content-clamp">{{ row.content }}</p>
              <span v-if="row.splitLines" class="split-hint">分 {{ lineCount(row.content) }} 条发送</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="statusTypes[row.status]" size="small" effect="light">
                {{ statusLabels[row.status] || row.status }}
              </el-tag>
              <span v-if="row.sentCount > 0" class="sent-count">已发 {{ row.sentCount }} 次</span>
              <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
                <span class="error-dot">!</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="" width="164" align="right">
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
                v-if="row.status === 'PENDING'"
                text
                type="warning"
                size="small"
                @click="cancel(row)"
              >
                取消
              </el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <strong>还没有定时消息</strong>
          适合提前写好群通知、每日提醒，到点自动发送。
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
      :title="editingId ? '编辑定时消息' : '新建定时消息'"
      width="min(580px, 92vw)"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <el-form-item label="发送到" prop="chatId">
          <ChatSelect
            v-model="form.chatId"
            allow-create
            placeholder="搜群名 / 好友昵称 / chatId"
            @pick="onChatPicked"
          />
        </el-form-item>
        <el-form-item :label="form.repeatRule === 'CRON' ? '首次发送时间' : '发送时间'" prop="scheduledAt">
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
          <div class="field-hint">
            顺序是「分 时 日 月 周」，周一到周日写 1-7（也可以写 MON-FRI）。
            上面的发送时间作为起点，之后按这个表达式推进；留了过去的日期会自动顺延到下一次触发。
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
        <el-form-item label="消息内容" prop="content">
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
          {{ editingId ? '保存修改' : '加入队列' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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
