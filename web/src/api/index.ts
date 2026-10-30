import { http } from './client'
import type {
  AutoReplyRule,
  AutoReplyRuleInput,
  Bot,
  BotInput,
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
  PromptTemplate,
  PromptTemplateInput,
  Provider,
  ProviderInput,
  ProviderTestResult,
  ScheduledMessage,
  ScheduledMessageInput,
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
  async messages(conversationId: string, page = 0, size = 200) {
    const { data } = await http.get<PageResult<Message>>(
      `/api/admin/conversations/${conversationId}/messages`,
      { params: { page, size } },
    )
    return data
  },
  async debugChat(botId: string, content: string) {
    const { data } = await http.post<DebugResult>('/api/admin/debug/chat', { botId, content })
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
  async cancelScheduledMessage(id: string) {
    const { data } = await http.post<ScheduledMessage>(`/api/admin/scheduled-messages/${id}/cancel`)
    return data
  },
  async deleteScheduledMessage(id: string) {
    await http.delete(`/api/admin/scheduled-messages/${id}`)
  },
  async updateFilterPolicy(payload: FilterPolicyInput) {
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
}






