export type ChatType = 'PRIVATE' | 'GROUP'
export type MessageStatus = 'RECEIVED' | 'GENERATING' | 'SUCCESS' | 'FAILED' | 'IGNORED'

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface RuntimeSetting<T> {
  value: T
  source: 'db' | 'env'
}

export interface SettingsSnapshot {
  allowedChats: RuntimeSetting<string[]>
  acceptAll: RuntimeSetting<boolean>
  groupsEnabled: RuntimeSetting<boolean>
  fallbackEnabled: RuntimeSetting<boolean>
  searchEnabled: RuntimeSetting<boolean>
  bannedWords: RuntimeSetting<string[]>
  filterMode: RuntimeSetting<'off' | 'rewrite' | 'block'>
  mediaEnabled: RuntimeSetting<boolean>
  mediaReplyMode: RuntimeSetting<MediaReplyMode>
  mediaAckText: RuntimeSetting<string>
  promptPricePerK: RuntimeSetting<number>
  completionPricePerK: RuntimeSetting<number>
}

export type MediaReplyMode = 'ignore' | 'ack' | 'llm'

export type MediaKind = 'IMAGE' | 'VOICE' | 'VIDEO' | 'FILE' | 'LINK' | 'STICKER' | 'SYSTEM' | 'OTHER'

export interface MediaPolicyInput {
  enabled: boolean
  replyMode: MediaReplyMode
  ackText: string
}

export interface UsagePricingInput {
  promptPricePerK: number
  completionPricePerK: number
}

export interface UsageDaily {
  date: string
  inbound: number
  outbound: number
  promptTokens: number
  completionTokens: number
}

export interface UsageProvider {
  provider: string
  model: string
  replies: number
  promptTokens: number
  completionTokens: number
  avgLatencyMs: number
}

export interface UsageTotals {
  inbound: number
  outbound: number
  replied: number
  ignored: number
  failed: number
  promptTokens: number
  completionTokens: number
  avgLatencyMs: number
  activeConversations: number
}

export interface UsageSummary {
  days: number
  from: string
  to: string
  totals: UsageTotals
  estimatedCost: number
  currency: string
  daily: UsageDaily[]
  providers: UsageProvider[]
}

export interface FilterPolicyInput {
  bannedWords: string[]
  mode: 'off' | 'rewrite' | 'block'
}

export interface AutoReplyRule {
  id: string
  name: string
  matchType: 'EXACT' | 'CONTAINS' | 'REGEX'
  pattern: string
  reply: string
  scope: 'ALL' | 'PRIVATE' | 'GROUP'
  priority: number
  enabled: boolean
  updatedAt: string
}

export interface AutoReplyRuleInput {
  name: string
  matchType: 'EXACT' | 'CONTAINS' | 'REGEX'
  pattern: string
  reply: string
  scope: 'ALL' | 'PRIVATE' | 'GROUP'
  priority: number
  enabled: boolean
}

export interface FailedOutbox {
  id: string
  chatId: string
  content: string
  status: string
  attempts: number
  lastError?: string
  createdAt: string
  nextAttemptAt?: string
}

export interface DiagnosticsPayload {
  generatedAt: string
  wechat: {
    hook: {
      baseUrl: string
      reachable: boolean
      isLogin: unknown
      moduleBase: string
      instance: string
      error: string
      latencyMs: number
      checkedAt: string
    }
    callback: Record<string, unknown>
    guard: Record<string, any>
    allowedChats: string[]
  }
  recentCallbacks: Array<Record<string, any>>
  outbox: { failedCount: number; failed: FailedOutbox[] }
}

export interface WeChatPolicyInput {
  groupsEnabled: boolean
  acceptAll: boolean
  allowedChats: string[]
}

/** 微信 Hook 返回的原始会话行（字段名取决于客户端库，可能缺失） */
export interface HookSession {
  username?: string
  sort_timestamp?: number
  last_timestamp?: number
  unread_count?: number
}

/** 微信 Hook 返回的原始联系人行 */
export interface HookContact {
  username?: string
  remark?: string
  nick_name?: string
  alias?: string
}

export interface ChatCandidate {
  chatId: string
  name: string
  group: boolean
  source: 'conversation' | 'session' | 'contact'
}

export interface LlmPolicyInput {
  fallbackEnabled: boolean
  searchEnabled: boolean
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
  hasBackupApiKey: boolean
  backupApiKeyHint?: string
  webSearchEnabled: boolean
}

export interface ProviderInput {
  name: string
  baseUrl: string
  apiKey?: string
  model: string
  enabled: boolean
  priority: number
  timeoutSeconds: number
  backupApiKey?: string
  webSearchEnabled: boolean
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
  botPaused: boolean
  promptTemplateId?: string | null
  promptTemplateName?: string | null
}

export interface PromptTemplate {
  id: string
  name: string
  description: string
  content: string
  updatedAt: string
}

export interface PromptTemplateInput {
  name: string
  description: string
  content: string
}

export type ScheduledStatus = 'PENDING' | 'SENT' | 'CANCELLED' | 'FAILED'

export interface ScheduledMessage {
  id: string
  botId: string
  chatId: string
  chatName: string
  content: string
  scheduledAt: string
  status: ScheduledStatus
  attempts: number
  lastError?: string
  createdAt: string
  sentAt?: string
}

export interface ScheduledMessageInput {
  botId?: string
  chatId: string
  chatName?: string
  content: string
  scheduledAt: string
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
  mediaKind?: MediaKind
  mediaName?: string
  mediaSize?: number
  mediaUrl?: string
  rawType?: number
  quoteAuthor?: string
  quoteText?: string
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

export interface DisguiseConfig {
  enabled: boolean
  scheduleEnabled: boolean
  zone: string
  quietStart: string
  quietEnd: string
  maxDeferMinutes: number
  awayBlocksPerDay: number
  awayBlockMinMinutes: number
  awayBlockMaxMinutes: number
  readDelayMinMs: number
  readDelayMaxMs: number
  thinkBaseMs: number
  charsPerSecond: number
  typingJitter: number
  maxDelayMs: number
  longPauseProbability: number
  longPauseMaxMs: number
  awayProbability: number
  awayMinMs: number
  awayMaxMs: number
  groupDelayFactor: number
  splitEnabled: boolean
  maxParts: number
  maxPartChars: number
  maxReplyChars: number
  partGapMinMs: number
  partGapMaxMs: number
  perMinute: number
  per10Minutes: number
  perHour: number
  perDay: number
  globalPerHour: number
  globalPerDay: number
  minGapMs: number
  burstSize: number
  burstCooldownMinMs: number
  burstCooldownMaxMs: number
}

export interface DisguiseDecision {
  at: string
  chatId: string
  state: string
  decision: string
  parts: number
  delaySeconds: number
  preview: string
}

export interface DisguiseStatus {
  enabled: boolean
  state: string
  zone: string
  quietHours: string
  nextActiveAt: string
  partsLimit: number
  perMinute: number
  per10Minutes: number
  perHour: number
  perDay: number
  planned: number
  dropped: number
  deferred: number
  repetitive: number
  globalSentToday: number
  recent: DisguiseDecision[]
}

export interface DisguisePreviewPart {
  text: string
  delaySeconds: number
}

export interface DisguisePreview {
  decision: string
  note: string
  dropped: boolean
  aiScore: number
  parts: DisguisePreviewPart[]
}
