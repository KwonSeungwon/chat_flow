import axios from 'axios'

const api = axios.create()

// Request interceptor: attach JWT token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('chatflow-token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response interceptor: on 401, try guest re-register
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401 && !error.config._retry) {
      error.config._retry = true
      const registered = await ensureAuthenticated()
      if (registered) {
        error.config.headers.Authorization = `Bearer ${localStorage.getItem('chatflow-token')}`
        return api(error.config)
      }
    }
    return Promise.reject(error)
  }
)

export async function ensureAuthenticated(): Promise<boolean> {
  const token = localStorage.getItem('chatflow-token')
  if (token) return true

  try {
    const username = localStorage.getItem('chatflow-username') || `Guest_${Math.floor(Math.random() * 1000)}`
    const password = `guest_${username}_${Date.now()}`

    const res = await axios.post('/api/auth/register', { username, password })
    const data = res.data

    localStorage.setItem('chatflow-token', data.token)
    localStorage.setItem('chatflow-userId', data.userId)
    localStorage.setItem('chatflow-username', data.username)
    return true
  } catch {
    // Registration failed (username taken), try with unique name
    try {
      const username = `Guest_${Math.floor(Math.random() * 100000)}`
      const password = `guest_${username}_${Date.now()}`
      const res = await axios.post('/api/auth/register', { username, password })
      const data = res.data

      localStorage.setItem('chatflow-token', data.token)
      localStorage.setItem('chatflow-userId', data.userId)
      localStorage.setItem('chatflow-username', data.username)
      return true
    } catch {
      return false
    }
  }
}

export default api
