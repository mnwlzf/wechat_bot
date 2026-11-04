<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { adminApi } from '@/api'
import type { ChatCandidate, ChatChoice, Conversation } from '@/api/types'

/**
 * 会话选择器：输入群名称 / 好友昵称 / 备注 / chatId 都能搜到，不用记 wxid。
 *
 * <p>两个数据源合并，缺一个不影响另一个：</p>
 * <ul>
 *   <li>会话表（`/api/admin/conversations?query=`）：聊过的会话，带着最后一条消息；</li>
 *   <li>会话目录（`/api/admin/wechat/chats?keyword=`）：本机备注 / 联系人昵称 / 群名称 / 最近回调，
 *       所以「还没跟机器人说过话」的群和好友也能按名字搜到。</li>
 * </ul>
 * <p>目录里的名字优先级更高：会话表里常常只存了一个 wxid / @chatroom。</p>
 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    disabled?: boolean
    /** 允许把没出现在列表里的 chatId 直接填进来（老数据兜底） */
    allowCreate?: boolean
  }>(),
  { placeholder: '搜群名称 / 好友昵称 / 备注 / chatId', disabled: false, allowCreate: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  pick: [conversation: ChatChoice | null]
}>()

interface Option {
  chatId: string
  name: string
  group: boolean
  /** 还没跟机器人说过话（会话表里没有），只能按名字绑 */
  pending?: boolean
  blocked?: boolean
}

const options = ref<Option[]>([])
const loading = ref(false)

function groupOf(chatId: string, chatType?: string) {
  if (chatType) return chatType === 'GROUP'
  return chatId.endsWith('@chatroom')
}

async function search(query: string) {
  const keyword = query.trim()
  loading.value = true
  // 两个请求各自成败，任何一边挂了都还有另一边可用
  const [directory, conversations] = await Promise.all([
    adminApi
      .searchChats({ kind: 'all', keyword: keyword || undefined, limit: 80 })
      .catch(() => [] as ChatCandidate[]),
    adminApi
      .conversations({ query: keyword || undefined, page: 0, size: 80 })
      .then((page) => page.content)
      .catch(() => [] as Conversation[]),
  ])

  const merged = new Map<string, Option>()
  for (const item of directory) {
    if (item.blocked) continue
    merged.set(item.chatId, {
      chatId: item.chatId,
      name: item.name || item.chatId,
      group: item.group,
    })
  }
  for (const item of conversations) {
    const existing = merged.get(item.chatId)
    const name = item.chatName && item.chatName !== item.chatId ? item.chatName : item.chatId
    if (existing) {
      // 目录里的名字更可信（备注 / 昵称），只在它就是个 ID 时才换成会话里的名字
      if (existing.name === existing.chatId && name !== item.chatId) existing.name = name
      continue
    }
    merged.set(item.chatId, {
      chatId: item.chatId,
      name,
      group: groupOf(item.chatId, item.chatType),
    })
  }
  // 还没聊过的排后面，聊过的按名字排，避免一屏全是陌生群
  options.value = [...merged.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
  loading.value = false
}

function onPick(value: string | undefined | null) {
  const chatId = value || ''
  emit('update:modelValue', chatId)
  const found = options.value.find((item) => item.chatId === chatId)
  emit(
    'pick',
    found
      ? { chatId: found.chatId, chatName: found.name, chatType: found.group ? 'GROUP' : 'PRIVATE' }
      : null,
  )
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
    <el-option
      v-for="item in options"
      :key="item.chatId"
      :label="item.name"
      :value="item.chatId"
      :disabled="item.blocked"
    >
      <div class="chat-option">
        <span class="chat-option__name">{{ item.name }}</span>
        <span class="chat-option__tag">{{ item.group ? '群聊' : '私聊' }}</span>
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
