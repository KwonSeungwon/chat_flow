import axios from 'axios'

const api = axios.create()

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('chatflow-token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('chatflow-token')
      localStorage.removeItem('chatflow-userId')
      localStorage.removeItem('chatflow-username')
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

export default api
