import { http } from './client'
import type {
  Bot,
  BotInput,
  Conversation,
  Dashboard,
  HookStatus,
  DebugResult,
  LlmPolicyInput,
  Message,
  PageResult,
  Provider,
  ProviderInput,
  ProviderTestResult,
  SettingsSnapshot,
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
}


