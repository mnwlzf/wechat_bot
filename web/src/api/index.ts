import { http } from './client'
import type {
  AutoReplyRule,
  AutoReplyRuleInput,
  AwayBlocksInput,
  Bot,
  BotInput,
  ChatCandidate,
  ChatMemory,
  ChatMemoryInput,
  ChatSendPolicy,
  ChatReplyPolicy,
  Conversation,
  Dashboard,
  DiagnosticsPayload,
  DisguiseConfig,
  DisguisePreview,
  DisguiseStatus,
  FailedOutbox,
  FilterPolicyInput,
  HookStatus,
  HookSession,
  HookContact,
  DebugResult,
  LlmPolicyInput,
  MediaPolicyInput,
  Message,
  PageResult,
  PersonaPreview,
  PromptTemplate,
  PromptTemplateInput,
  Provider,
  ProviderInput,
  ProviderTestResult,
  ScheduledMessage,
  ScheduledMessageInput,
  SearchSettings,
  SearchSettingsInput,
  SearchTestResult,
  SendPolicyEffective,
  SendPolicyInput,
  SendPolicyOverview,
  SendPolicyRow,
  ReplyPolicyEffective,
  ReplyPolicyInput,
  ReplyPolicyOverview,
  ReplyPolicyRow,
  SettingsSnapshot,
  UsagePricingInput,
  UsageSummary,
  WeChatPolicyInput,
} from './types'

export const adminApi = {
  async ping() {
    const { data } = await http.get<{ ok: boolean }>('/api/admin/ping')
    return data
  },
  async dashboard() {
    const { data } = await http.get<Dashboard>('/api/admin/dashboard')
    return data
  },
  async hookStatus() {
    const { data } = await http.get<HookStatus>('/api/admin/wechat/hook')
    return data
  },
  async providers() {
    const { data } = await http.get<Provider[]>('/api/admin/providers')
    return data
  },
  async createProvider(payload: ProviderInput) {
    const { data } = await http.post<Provider>('/api/admin/providers', payload)
    return data
  },
  async updateProvider(id: string, payload: ProviderInput) {
    const { data } = await http.put<Provider>(`/api/admin/providers/${id}`, payload)
    return data
  },
  async deleteProvider(id: string) {
    await http.delete(`/api/admin/providers/${id}`)
  },
  async testProvider(id: string) {
    const { data } = await http.post<ProviderTestResult>(`/api/admin/providers/${id}/test`)
    return data
  },
  async settings() {
    const { data } = await http.get<SettingsSnapshot>('/api/admin/settings')
    return data
  },
  async updateWeChatPolicy(payload: WeChatPolicyInput) {
    const { data } = await http.put<SettingsSnapshot>('/api/admin/settings/wechat', payload)
    return data
  },
  async updateLlmPolicy(payload: LlmPolicyInput) {
    const { data } = await http.put<SettingsSnapshot>('/api/admin/settings/llm', payload)
    return data
  },
  async bots() {
    const { data } = await http.get<Bot[]>('/api/admin/bots')
    return data
  },
  async updateBot(id: string, payload: BotInput) {
    const { data } = await http.put<Bot>(`/api/admin/bots/${id}`, payload)
    return data
  },
  async conversations(params: { query?: string; page: number; size: number }) {
    const { data } = await http.get<PageResult<Conversation>>('/api/admin/conversations', { params })
    return data
  },
  async sessions(limit = 200) {
    const { data } = await http.get<HookSession[]>('/api/admin/wechat/sessions', { params: { limit } })
    return data
  },
  async contacts(limit = 200) {
    const { data } = await http.get<HookContact[]>('/api/admin/wechat/contacts', { params: { limit } })
    return data
  },
  /** 按昵称 / 备注 / 群名称搜索可加入白名单的会话（公众号与系统会话会标记 blocked）。 */
  async searchChats(params: { kind: 'private' | 'groups'; keyword?: string; limit?: number }) {
    const { data } = await http.get<ChatCandidate[]>('/api/admin/wechat/chats', { params })
    return data
  },
  /** 给会话存一个本机备注（名称传空串即删除），之后可按备注搜索。 */
  async saveChatAlias(chatId: string, name: string) {
    const { data } = await http.put<{ chatId: string; name: string }>(
      `/api/admin/wechat/chats/${encodeURIComponent(chatId)}/alias`,
      { name },
    )
    return data
  },
  async messages(conversationId: string, page = 0, size = 200) {
    const { data } = await http.get<PageResult<Message>>(
      `/api/admin/conversations/${conversationId}/messages`,
      { params: { page, size } },
    )
    return data
  },
  /** 可选 quoteAuthor/quoteText 模拟引用回复，mediaKind 模拟语音、图片等媒体消息，promptTemplateId 指定调试用人设。 */
  async debugChat(
    botId: string,
    content: string,
    extra?: {
      quoteAuthor?: string
      quoteText?: string
      mediaKind?: string
      promptTemplateId?: string | null
    },
  ) {
    const { data } = await http.post<DebugResult>('/api/admin/debug/chat', { botId, content, ...extra })
    return data
  },
  async autoReplies() {
    const { data } = await http.get<AutoReplyRule[]>('/api/admin/auto-replies')
    return data
  },
  async createAutoReply(payload: AutoReplyRuleInput) {
    const { data } = await http.post<AutoReplyRule>('/api/admin/auto-replies', payload)
    return data
  },
  async updateAutoReply(id: string, payload: AutoReplyRuleInput) {
    const { data } = await http.put<AutoReplyRule>(`/api/admin/auto-replies/${id}`, payload)
    return data
  },
  async deleteAutoReply(id: string) {
    await http.delete(`/api/admin/auto-replies/${id}`)
  },
  async disguiseConfig() {
    const { data } = await http.get<DisguiseConfig>('/api/admin/disguise/config')
    return data
  },
  async disguiseStatus() {
    const { data } = await http.get<DisguiseStatus>('/api/admin/disguise')
    return data
  },
  async updateDisguiseAwayBlocks(payload: AwayBlocksInput) {
    const { data } = await http.put<DisguiseConfig>('/api/admin/disguise/away-blocks', payload)
    return data
  },
  async disguisePreview(payload: { chatId?: string; chatType: 'PRIVATE' | 'GROUP'; text: string }) {
    const { data } = await http.post<DisguisePreview>('/api/admin/disguise/preview', payload)
    return data
  },
  async diagnostics() {
    const { data } = await http.get<DiagnosticsPayload>('/api/admin/diagnostics')
    return data
  },
  async retryOutbox(id: string) {
    const { data } = await http.post<FailedOutbox>(`/api/admin/diagnostics/outbox/${id}/retry`)
    return data
  },
  async setTakeover(conversationId: string, paused: boolean) {
    const { data } = await http.post<Conversation>(`/api/admin/conversations/${conversationId}/takeover`, {
      paused,
    })
    return data
  },
  async setPersona(conversationId: string, promptTemplateId: string | null) {
    const { data } = await http.post<Conversation>(`/api/admin/conversations/${conversationId}/persona`, {
      promptTemplateId,
    })
    return data
  },
  /** 按 wxid / 群 ID 直接指定人设，会话还没出现过也能先绑定。 */
  async bindPersonaByChatId(chatId: string, promptTemplateId: string | null) {
    const { data } = await http.post<Conversation>('/api/admin/personas/bind', { chatId, promptTemplateId })
    return data
  },
  /** 这条会话实际会发给模型的人设 / 记忆 / 风格三段提示词。 */
  async personaPreview(conversationId: string) {
    const { data } = await http.get<PersonaPreview>(`/api/admin/personas/preview/${conversationId}`)
    return data
  },
  async promptTemplates() {
    const { data } = await http.get<PromptTemplate[]>('/api/admin/prompt-templates')
    return data
  },
  async createPromptTemplate(payload: PromptTemplateInput) {
    const { data } = await http.post<PromptTemplate>('/api/admin/prompt-templates', payload)
    return data
  },
  async updatePromptTemplate(id: string, payload: PromptTemplateInput) {
    const { data } = await http.put<PromptTemplate>(`/api/admin/prompt-templates/${id}`, payload)
    return data
  },
  async deletePromptTemplate(id: string) {
    await http.delete(`/api/admin/prompt-templates/${id}`)
  },
  /** 设为全局默认人设：没有单独指定人设的会话都用它。 */
  async setDefaultPromptTemplate(id: string) {
    const { data } = await http.post<PromptTemplate>(`/api/admin/prompt-templates/${id}/default`)
    return data
  },
  async clearDefaultPromptTemplate() {
    await http.delete('/api/admin/prompt-templates/default')
  },
  async memories(chatId?: string) {
    const { data } = await http.get<ChatMemory[]>('/api/admin/memories', { params: { chatId } })
    return data
  },
  async createMemory(payload: ChatMemoryInput) {
    const { data } = await http.post<ChatMemory>('/api/admin/memories', payload)
    return data
  },
  async updateMemory(id: string, payload: { content?: string; enabled?: boolean }) {
    const { data } = await http.put<ChatMemory>(`/api/admin/memories/${id}`, payload)
    return data
  },
  async deleteMemory(id: string) {
    await http.delete(`/api/admin/memories/${id}`)
  },
  /** 立即按现有历史重算一次共享摘要（同步，可能需要几秒）。 */
  async summarizeMemory(chatId: string) {
    const { data } = await http.post<ChatMemory>('/api/admin/memories/summaries', null, {
      params: { chatId },
    })
    return data
  },
  async resetMemorySummary(chatId: string) {
    await http.delete('/api/admin/memories/summaries', { params: { chatId } })
  },
  async scheduledMessages(page = 0, size = 20) {
    const { data } = await http.get<PageResult<ScheduledMessage>>('/api/admin/scheduled-messages', {
      params: { page, size },
    })
    return data
  },
  async createScheduledMessage(payload: ScheduledMessageInput) {
    const { data } = await http.post<ScheduledMessage>('/api/admin/scheduled-messages', payload)
    return data
  },
  async updateScheduledMessage(id: string, payload: ScheduledMessageInput) {
    const { data } = await http.put<ScheduledMessage>(`/api/admin/scheduled-messages/${id}`, payload)
    return data
  },
  async cancelScheduledMessage(id: string) {
    const { data } = await http.post<ScheduledMessage>(`/api/admin/scheduled-messages/${id}/cancel`)
    return data
  },
  async deleteScheduledMessage(id: string) {
    await http.delete(`/api/admin/scheduled-messages/${id}`)
  },
  async searchSettings() {
    const { data } = await http.get<SearchSettings>('/api/admin/search-settings')
    return data
  },
  async updateSearchSettings(payload: SearchSettingsInput) {
    const { data } = await http.put<SearchSettings>('/api/admin/search-settings', payload)
    return data
  },
  async testSearchSettings(query: string) {
    const { data } = await http.post<SearchTestResult>('/api/admin/search-settings/test', { query })
    return data
  },  async updateFilterPolicy(payload: FilterPolicyInput) {
    const { data } = await http.put<SettingsSnapshot>('/api/admin/settings/filter', payload)
    return data
  },
  async updateMediaPolicy(payload: MediaPolicyInput) {
    const { data } = await http.put<SettingsSnapshot>('/api/admin/settings/media', payload)
    return data
  },
  async updatePricing(payload: UsagePricingInput) {
    const { data } = await http.put<SettingsSnapshot>('/api/admin/settings/pricing', payload)
    return data
  },
  async usage(days = 7) {
    const { data } = await http.get<UsageSummary>('/api/admin/usage', { params: { days } })
    return data
  },
  async sendPolicies() {
    const { data } = await http.get<SendPolicyOverview>('/api/admin/send-policies')
    return data
  },
  async chatSendPolicy(chatId: string) {
    const { data } = await http.get<ChatSendPolicy>(`/api/admin/send-policies/${encodeURIComponent(chatId)}`)
    return data
  },
  async saveDefaultSendPolicy(payload: SendPolicyInput) {
    const { data } = await http.put<SendPolicyEffective>('/api/admin/send-policies/default', payload)
    return data
  },
  async saveChatSendPolicy(chatId: string, chatName: string, payload: SendPolicyInput) {
    const { data } = await http.put<SendPolicyRow>(
      `/api/admin/send-policies/${encodeURIComponent(chatId)}`,
      payload,
      { params: { chatName } },
    )
    return data
  },
  async deleteChatSendPolicy(chatId: string) {
    await http.delete(`/api/admin/send-policies/${encodeURIComponent(chatId)}`)
  },
  async replyPolicies() {
    const { data } = await http.get<ReplyPolicyOverview>('/api/admin/reply-policies')
    return data
  },
  async chatReplyPolicy(chatId: string) {
    const { data } = await http.get<ChatReplyPolicy>(`/api/admin/reply-policies/${encodeURIComponent(chatId)}`)
    return data
  },
  async saveDefaultReplyPolicy(payload: ReplyPolicyInput) {
    const { data } = await http.put<ReplyPolicyEffective>('/api/admin/reply-policies/default', payload)
    return data
  },
  async saveChatReplyPolicy(chatId: string, chatName: string, payload: ReplyPolicyInput) {
    const { data } = await http.put<ReplyPolicyRow>(
      `/api/admin/reply-policies/${encodeURIComponent(chatId)}`,
      payload,
      { params: { chatName } },
    )
    return data
  },
  async deleteChatReplyPolicy(chatId: string) {
    await http.delete(`/api/admin/reply-policies/${encodeURIComponent(chatId)}`)
  },
}