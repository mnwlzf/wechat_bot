<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { adminApi } from '@/api'
import type { Conversation } from '@/api/types'

/**
 * 会话选择器：输入群名 / 好友昵称 / chatId 都能搜到，不用记 wxid。
 *
 * <p>列表走服务端搜索（`/api/admin/conversations?query=`），所以会话上千条也不会漏；
 * 下拉项显示「群聊 / 私聊」标签，选中后通过 `pick` 把整条会话抛给父组件（拿 chatName 用）。</p>
 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    disabled?: boolean
    /** 允许把没出现在列表里的 chatId 直接填进来（老数据兜底） */
    allowCreate?: boolean
  }>(),
  { placeholder: '搜群名 / 好友昵称 / chatId', disabled: false, allowCreate: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  pick: [conversation: Conversation | null]
}>()

const options = ref<Conversation[]>([])
const loading = ref(false)

async function search(query: string) {
  loading.value = true
  try {
    const data = await adminApi.conversations({ query: query.trim() || undefined, page: 0, size: 50 })
    options.value = data.content
  } catch {
    /* 搜索失败就保留上一次的结果，不打断输入 */
  } finally {
    loading.value = false
  }
}

function onPick(value: string | undefined | null) {
  const chatId = value || ''
  emit('update:modelValue', chatId)
  emit('pick', options.value.find((item) => item.chatId === chatId) || null)
}

// 外部改了值（比如从路由带进来 chatId）时，补一次搜索把名字也加载出来
watch(
  () => props.modelValue,
  (value) => {
    if (value && !options.value.some((item) => item.chatId === value)) {
      void search(value)
    }
  },
)

onMounted(() => void search(''))
</script>

<template>
  <el-select
    :model-value="props.modelValue"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :disabled="props.disabled"
    :allow-create="props.allowCreate"
    :default-first-option="props.allowCreate"
    :reserve-keyword="false"
    :placeholder="props.placeholder"
    class="chat-select"
    @update:model-value="onPick"
  >
    <el-option v-for="item in options" :key="item.chatId" :label="item.chatName" :value="item.chatId">
      <div class="chat-option">
        <span class="chat-option__name">{{ item.chatName }}</span>
        <span class="chat-option__tag">{{ item.chatType === 'GROUP' ? '群聊' : '私聊' }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<style scoped>
.chat-select {
  width: 100%;
}

.chat-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.chat-option__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-option__tag {
  flex: 0 0 auto;
  color: var(--muted);
  font-size: 10px;
}
</style>
