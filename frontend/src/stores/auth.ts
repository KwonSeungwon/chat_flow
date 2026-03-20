import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref<string | null>(localStorage.getItem('chatflow-userId'))
  const username = ref<string>(localStorage.getItem('chatflow-username') || '')
  const token = ref<string | null>(localStorage.getItem('chatflow-token'))

  const isAuthenticated = computed(() => !!token.value)

  function setUser(newUserId: string, newUsername: string, newToken?: string) {
    userId.value = newUserId
    username.value = newUsername
    localStorage.setItem('chatflow-userId', newUserId)
    localStorage.setItem('chatflow-username', newUsername)
    if (newToken) {
      token.value = newToken
      localStorage.setItem('chatflow-token', newToken)
    }
  }

  function initGuest() {
    if (!username.value) {
      const guestName = `Guest_${crypto.randomUUID().substring(0, 8)}`
      setUser(crypto.randomUUID(), guestName)
    }
  }

  function logout() {
    userId.value = null
    username.value = ''
    token.value = null
    localStorage.removeItem('chatflow-userId')
    localStorage.removeItem('chatflow-username')
    localStorage.removeItem('chatflow-token')
  }

  return {
    userId,
    username,
    token,
    isAuthenticated,
    setUser,
    initGuest,
    logout
  }
})
