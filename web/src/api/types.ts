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
  /** 白名单里的真人私聊（不含 @chatroom） */
  allowedPrivateChats: RuntimeSetting<string[]>
  /** 白名单里的群聊；群聊只认这份名单，不受「全量接收私聊」影响 */
  allowedGroupChats: RuntimeSetting<string[]>
  acceptAll: RuntimeSetting<boolean>
  groupsEnabled: RuntimeSetting<boolean>
  fallbackEnabled: RuntimeSetting<boolean>
  bannedWords: RuntimeSetting<string[]>
  filterMode: RuntimeSetting<'off' | 'rewrite' | 'block'>
  mediaEnabled: RuntimeSetting<boolean>
  mediaReplyMode: RuntimeSetting<MediaReplyMode>
  mediaAckText: RuntimeSetting<string>
  promptPricePerK: RuntimeSetting<number>
  completionPricePerK: RuntimeSetting<number>
}

export type MediaReplyMode = 'ignore' | 'ack' | 'llm'

export type MediaKind =
  | 'IMAGE'
  | 'VOICE'
  | 'VIDEO'
  | 'FILE'
  | 'LINK'
  | 'STICKER'
  | 'LOCATION'
  | 'CARD'
  | 'MINIAPP'
  | 'RECORD'
  | 'CHANNEL'
  | 'TRANSFER'
  | 'REDPACKET'
  | 'CALL'
  | 'SYSTEM'
  | 'OTHER'

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

/** 微信会话身份：公众号 / 机器人和系统会话永远不回复 */
export type ChatKind = 'PRIVATE' | 'GROUP' | 'OFFICIAL' | 'SYSTEM'

/** 白名单候选：按昵称 / 备注 / 群名称搜索得到 */
export interface ChatCandidate {
  chatId: string
  name: string
  kind: ChatKind
  group: boolean
  /** 公众号、机器人、系统会话：不可加入白名单 */
  blocked: boolean
  blockedReason: string
  /** 名字来源：本机备注、外部读取服务、微信联系人、本地会话、最近回调 */
  source: 'alias' | 'reader' | 'contact' | 'conversation' | 'session' | 'hook'
  /** 最近一条消息，用来认出还没有名字的群 */
  lastMessage: string | null
  lastActiveAt: string | null
}

export interface LlmPolicyInput {
  fallbackEnabled: boolean
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
  /** 实际生效的人设：未绑定时显示「默认人设（默认）」 */
  effectivePersonaName?: string | null
}

export interface PromptTemplate {
  id: string
  name: string
  description: string
  content: string
  /** 全局默认人设：会话未单独指定时回退到它 */
  defaultPersona: boolean
  /** 内置人设（例如大肥鱼） */
  builtin: boolean
  builtinKey?: string | null
  updatedAt: string
}

export interface PromptTemplateInput {
  name: string
  description: string
  content: string
}

export type MemoryKind = 'SUMMARY' | 'NOTE'

export interface ChatMemory {
  id: string
  chatId: string
  chatName: string
  kind: MemoryKind
  source: 'AUTO' | 'MANUAL'
  content: string
  enabled: boolean
  messageCount: number
  coveredUntil?: string | null
  createdAt: string
  updatedAt: string
}

export interface ChatMemoryInput {
  chatId: string
  chatName?: string
  content: string
  enabled?: boolean
}

export type ScheduledStatus = 'PENDING' | 'SENT' | 'CANCELLED' | 'FAILED'
export type ScheduledRepeat = 'NONE' | 'DAILY' | 'WEEKLY'

export interface ScheduledMessage {
  id: string
  botId: string
  chatId: string
  chatName: string
  content: string
  scheduledAt: string
  repeatRule: ScheduledRepeat
  repeatUntil?: string | null
  splitLines: boolean
  sentCount: number
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
  repeatRule?: ScheduledRepeat
  repeatUntil?: string | null
  splitLines?: boolean
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
  awayBlocksEnabled: boolean
  awayBlocks: string[]
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

export interface AwayBlocksInput {
  enabled: boolean
  blocks: string[]
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

export interface SearchSettings {
  enabled: boolean
  mode: 'OFF' | 'HEURISTIC' | 'ALWAYS'
  baseUrl: string
  hasApiKey: boolean
  apiKeyHint?: string
  hasBackupApiKey: boolean
  backupApiKeyHint?: string
  count: number
  needContent: boolean
  maxResults: number
  maxContentChars: number
  timeoutSeconds: number
}

export interface SearchSettingsInput {
  enabled: boolean
  mode: 'OFF' | 'HEURISTIC' | 'ALWAYS'
  baseUrl: string
  apiKey?: string
  backupApiKey?: string
  clearApiKey: boolean
  clearBackupApiKey: boolean
  count: number
  needContent: boolean
  maxResults: number
  maxContentChars: number
  timeoutSeconds: number
}

export interface SearchTestResult {
  success: boolean
  message: string
  resultCount: number
  latencyMs: number
}


export interface SendPolicyInput {
  readDelayMinMs?: number | null
  readDelayMaxMs?: number | null
  maxDelayMs?: number | null
  splitEnabled?: boolean | null
  maxParts?: number | null
  maxPartChars?: number | null
  perMinute?: number | null
  per10Minutes?: number | null
  perHour?: number | null
  perDay?: number | null
  minGapMs?: number | null
  emojiLimit?: number | null
  dropRate?: number | null
  promptEnabled?: boolean | null
  enabled?: boolean | null
}

/** 数据库里的一行策略：chatId 为空表示全局默认策略 */
export interface SendPolicyRow {
  id: string
  chatId: string | null
  chatName: string
  isDefault: boolean
  enabled: boolean
  readDelayMinMs: number | null
  readDelayMaxMs: number | null
  maxDelayMs: number | null
  splitEnabled: boolean | null
  maxParts: number | null
  maxPartChars: number | null
  perMinute: number | null
  per10Minutes: number | null
  perHour: number | null
  perDay: number | null
  minGapMs: number | null
  emojiLimit: number | null
  dropRate: number | null
  promptEnabled: boolean | null
  updatedAt: string
}

/** 合并后的生效值；inherited 表示该会话没有单独配置 */
export interface SendPolicyEffective {
  readDelayMinMs: number
  readDelayMaxMs: number
  maxDelayMs: number
  splitEnabled: boolean
  maxParts: number
  maxPartChars: number
  perMinute: number
  per10Minutes: number
  perHour: number
  perDay: number
  minGapMs: number
  emojiLimit: number
  dropRate: number
  promptEnabled: boolean
  inherited: boolean
}

export interface SendPolicyOverview {
  defaultPolicy: SendPolicyEffective
  defaultRow: SendPolicyRow | null
  overrides: SendPolicyRow[]
}

export interface ChatSendPolicy {
  effective: SendPolicyEffective
  override: SendPolicyRow | null
}
/** 群聊回复策略：主动回复 / 随机回复 / 关键词唤醒 */
export type ReplyMode = 'ALWAYS' | 'RANDOM' | 'WAKE' | 'HUMAN'

export interface ReplyPolicyInput {
  mode?: ReplyMode | null
  wakeWords?: string | null
  randomRate?: number | null
  humanCooldownSeconds?: number | null
  enabled?: boolean | null
}

/** 数据库里的一行回复策略：chatId 为空表示全局默认策略 */
export interface ReplyPolicyRow {
  id: string
  chatId: string | null
  chatName: string
  isDefault: boolean
  enabled: boolean
  mode: ReplyMode | null
  wakeWords: string | null
  randomRate: number | null
  humanCooldownSeconds: number | null
  updatedAt: string
}

/** 合并三层之后的生效策略；inherited 表示该群没有单独配置 */
export interface ReplyPolicyEffective {
  mode: ReplyMode
  wakeWords: string
  randomRate: number
  humanCooldownSeconds: number
  source: 'bot' | 'default' | 'chat'
  inherited: boolean
}

export interface ReplyPolicyOverview {
  defaultPolicy: ReplyPolicyEffective
  defaultRow: ReplyPolicyRow | null
  overrides: ReplyPolicyRow[]
  botWakeWords: string
}

export interface ChatReplyPolicy {
  effective: ReplyPolicyEffective
  override: ReplyPolicyRow | null
}