<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import type { Conversation, PageResult, ScheduledMessage } from '@/api/types'
import { getErrorMessage } from '@/api/client'

const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
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

const conversations = ref<Conversation[]>([])

const form = reactive({
  chatId: '',
  chatName: '',
  content: '',
  scheduledAt: '',
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

const pendingCount = computed(
  () => result.value.content.filter((item) => item.status === 'PENDING').length,
)

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

async function loadConversations() {
  try {
    const data = await adminApi.conversations({ page: 0, size: 100 })
    conversations.value = data.content
  } catch {
    /* 会话不可用时允许手填 chatId */
  }
}

function openCreate() {
  Object.assign(form, {
    chatId: '',
    chatName: '',
    content: '',
    scheduledAt: dayjs().add(10, 'minute').format('YYYY-MM-DD HH:mm:ss'),
  })
  dialogVisible.value = true
}

function onChatPicked(chatId: string) {
  const found = conversations.value.find((item) => item.chatId === chatId)
  form.chatName = found ? found.chatName : ''
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await adminApi.createScheduledMessage({
      chatId: form.chatId,
      chatName: form.chatName || undefined,
      content: form.content,
      scheduledAt: dayjs(form.scheduledAt).toISOString(),
    })
    ElMessage.success('已排入定时队列')
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

onMounted(async () => {
  await Promise.all([load(), loadConversations()])
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h1>定时消息</h1>
        <p>按预约时间发送，到点自动进入投递队列，走同一套重试与伪装节奏。</p>
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
          <el-table-column label="发送时间" width="128">
            <template #default="{ row }">
              <span class="mono">{{ timeLabel(row.scheduledAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="170">
            <template #default="{ row }">
              <strong class="chat-name">{{ row.chatName || row.chatId }}</strong>
              <span class="mono chat-id">{{ row.chatId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="240">
            <template #default="{ row }"><p class="content-clamp">{{ row.content }}</p></template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="statusTypes[row.status]" size="small" effect="light">
                {{ statusLabels[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="" width="120" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'PENDING'"
                text
                type="primary"
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

    <el-dialog v-model="dialogVisible" title="新建定时消息" width="min(560px, 92vw)">
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <el-form-item label="发送到" prop="chatId">
          <el-select
            v-model="form.chatId"
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="选择会话，或直接粘贴 wxid / @chatroom"
            style="width: 100%"
            @change="onChatPicked"
          >
            <el-option
              v-for="item in conversations"
              :key="item.chatId"
              :label="`${item.chatName}${item.chatType === 'GROUP' ? '（群）' : ''}`"
              :value="item.chatId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发送时间" prop="scheduledAt">
          <el-date-picker
            v-model="form.scheduledAt"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择日期与时间"
            style="width: 100%"
          />
          <div class="field-hint">到点后最多延迟 20 秒进入投递队列，实际发送受伪装层配额影响。</div>
        </el-form-item>
        <el-form-item label="消息内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="5000"
            show-word-limit
            placeholder="需要发送的文字内容"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">加入队列</el-button>
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

.pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}
</style>
