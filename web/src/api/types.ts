export type ChatType = 'PRIVATE' | 'GROUP'
export type MessageStatus = 'RECEIVED' | 'GENERATING' | 'SUCCESS' | 'FAILED' | 'IGNORED'

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface Provider {
  id: string
  name: string
  baseUrl: string
  model: string
  enabled: boolean
  priority: number
  timeoutSeconds: number
  hasApiKey: boolean
  apiKeyHint?: string
  updatedAt: string
}

export interface ProviderInput {
  name: string
  baseUrl: string
  apiKey?: string
  model: string
  enabled: boolean
  priority: number
  timeoutSeconds: number
}

export interface Bot {
  id: string
  name: string
  enabled: boolean
  modelProviderId?: string
  modelProviderName?: string
  systemPrompt: string
  replyPrivate: boolean
  replyGroup: boolean
  groupWakeWords: string
  contextWindow: number
  temperature: number
  maxTokens: number
  rateLimitPerMinute: number
  updatedAt: string
}

export interface BotInput {
  name: string
  enabled: boolean
  modelProviderId?: string
  systemPrompt: string
  replyPrivate: boolean
  replyGroup: boolean
  groupWakeWords: string
  contextWindow: number
  temperature: number
  maxTokens: number
  rateLimitPerMinute: number
}

export interface Conversation {
  id: string
  chatId: string
  chatName: string
  chatType: ChatType
  lastMessage: string
  lastMessageAt: string
}

export interface Message {
  id: string
  direction: 'INBOUND' | 'OUTBOUND'
  senderName?: string
  content: string
  providerName?: string
  model?: string
  status: MessageStatus
  errorMessage?: string
  promptTokens?: number
  completionTokens?: number
  latencyMs?: number
  createdAt: string
}

export interface Dashboard {
  conversations: number
  messages24h: number
  inbound24h: number
  pendingOutbox: number
  enabledProviders: number
  recentMessages: Message[]
}

export interface ProviderTestResult {
  success: boolean
  reply: string
  model: string
  latencyMs: number
}

export interface DebugResult {
  reply: string
  provider: string
  model: string
  latencyMs: number
}

export interface HookStatus {
  enabled: boolean
  configured: boolean
  connected: boolean
  protocol: string
  version: string
  account: string
  startupState: string
  allowedChats: string[]
  message?: string
  checkedAt: string
}
