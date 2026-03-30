import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref<string | null>(localStorage.getItem('chatflow-userId'))
  const username = ref<string>(localStorage.getItem('chatflow-username') || '')
  const token = ref<string | null>(localStorage.getItem('chatflow-token'))
  const isGuest = ref<boolean>(localStorage.getItem('chatflow-isGuest') === 'true')

  const isAuthenticated = computed(() => !!token.value)

  function setUser(newUserId: string, newUsername: string, newToken?: string, guest = false) {
    userId.value = newUserId
    username.value = newUsername
    isGuest.value = guest
    localStorage.setItem('chatflow-userId', newUserId)
    localStorage.setItem('chatflow-username', newUsername)
    localStorage.setItem('chatflow-isGuest', String(guest))
    if (newToken) {
      token.value = newToken
      localStorage.setItem('chatflow-token', newToken)
    }
  }

  async function login(uname: string, password: string) {
    const res = await axios.post('/api/auth/login', { username: uname, password })
    const data = res.data
    setUser(data.userId, data.username, data.token, false)
    return data
  }

  async function register(uname: string, password: string) {
    const res = await axios.post('/api/auth/register', { username: uname, password })
    const data = res.data
    setUser(data.userId, data.username, data.token, false)
    return data
  }

  function logout() {
    const t = token.value
    userId.value = null
    username.value = ''
    token.value = null
    isGuest.value = false
    localStorage.removeItem('chatflow-userId')
    localStorage.removeItem('chatflow-username')
    localStorage.removeItem('chatflow-token')
    localStorage.removeItem('chatflow-isGuest')
    if (t) {
      axios.post('/api/auth/logout', null, {
        headers: { Authorization: `Bearer ${t}` }
      }).catch(() => {})
    }
  }

  return {
    userId,
    username,
    token,
    isGuest,
    isAuthenticated,
    setUser,
    login,
    register,
    logout
  }
})
