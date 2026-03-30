import { create } from 'zustand'
import axios from 'axios'

interface AuthState {
  userId: string | null
  username: string
  token: string | null
  isGuest: boolean
  isAuthenticated: boolean
  login: (username: string, password: string) => Promise<void>
  register: (username: string, password: string) => Promise<void>
  guestLogin: () => Promise<void>
  logout: () => void
  hydrate: () => void
}

export const useAuthStore = create<AuthState>((set, get) => ({
  userId: null,
  username: '',
  token: null,
  isGuest: false,
  isAuthenticated: false,

  hydrate: () => {
    const token = localStorage.getItem('chatflow-token')
    const userId = localStorage.getItem('chatflow-userId')
    const username = localStorage.getItem('chatflow-username') || ''
    const isGuest = localStorage.getItem('chatflow-isGuest') === 'true'
    set({ token, userId, username, isGuest, isAuthenticated: !!token })
  },

  login: async (username, password) => {
    const res = await axios.post('/api/auth/login', { username, password })
    const { token, userId, username: uname } = res.data
    localStorage.setItem('chatflow-token', token)
    localStorage.setItem('chatflow-userId', userId)
    localStorage.setItem('chatflow-username', uname)
    localStorage.setItem('chatflow-isGuest', 'false')
    set({ token, userId, username: uname, isGuest: false, isAuthenticated: true })
  },

  register: async (username, password) => {
    const res = await axios.post('/api/auth/register', { username, password })
    const { token, userId, username: uname } = res.data
    localStorage.setItem('chatflow-token', token)
    localStorage.setItem('chatflow-userId', userId)
    localStorage.setItem('chatflow-username', uname)
    localStorage.setItem('chatflow-isGuest', 'false')
    set({ token, userId, username: uname, isGuest: false, isAuthenticated: true })
  },

  guestLogin: async () => {
    const guestName = `Guest_${Math.floor(Math.random() * 100000)}`
    const guestPass = `guest_${guestName}_${Date.now()}`
    const res = await axios.post('/api/auth/register', { username: guestName, password: guestPass })
    const { token, userId, username } = res.data
    localStorage.setItem('chatflow-token', token)
    localStorage.setItem('chatflow-userId', userId)
    localStorage.setItem('chatflow-username', username)
    localStorage.setItem('chatflow-isGuest', 'true')
    set({ token, userId, username, isGuest: true, isAuthenticated: true })
  },

  logout: () => {
    const token = get().token
    if (token) {
      axios.post('/api/auth/logout', null, {
        headers: { Authorization: `Bearer ${token}` }
      }).catch(() => {})
    }
    localStorage.removeItem('chatflow-token')
    localStorage.removeItem('chatflow-userId')
    localStorage.removeItem('chatflow-username')
    localStorage.removeItem('chatflow-isGuest')
    set({ token: null, userId: null, username: '', isGuest: false, isAuthenticated: false })
  }
}))
