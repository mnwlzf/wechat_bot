import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { adminApi } from '@/api'
import { TOKEN_KEY } from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const isAuthenticated = computed(() => Boolean(token.value))

  async function login(nextToken: string) {
    const value = nextToken.trim()
    localStorage.setItem(TOKEN_KEY, value)
    token.value = value
    try {
      await adminApi.ping()
    } catch (error) {
      logout()
      throw error
    }
  }

  function logout() {
    localStorage.removeItem(TOKEN_KEY)
    token.value = ''
  }

  return { token, isAuthenticated, login, logout }
})
