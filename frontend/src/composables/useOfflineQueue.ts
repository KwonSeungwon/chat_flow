import { ref, onMounted, onUnmounted } from 'vue'
import type { ChatMessage } from '@/types'

const STORAGE_KEY = 'chatflow:offline-queue'

export function useOfflineQueue() {
  const isOnline = ref(navigator.onLine)
  const queueSize = ref(0)

  const updateOnlineStatus = () => {
    isOnline.value = navigator.onLine
  }

  onMounted(() => {
    window.addEventListener('online', updateOnlineStatus)
    window.addEventListener('offline', updateOnlineStatus)
    queueSize.value = getQueue().length
  })

  onUnmounted(() => {
    window.removeEventListener('online', updateOnlineStatus)
    window.removeEventListener('offline', updateOnlineStatus)
  })

  function getQueue(): Partial<ChatMessage>[] {
    try {
      const data = localStorage.getItem(STORAGE_KEY)
      return data ? JSON.parse(data) : []
    } catch {
      return []
    }
  }

  function enqueue(message: Partial<ChatMessage>) {
    const queue = getQueue()
    queue.push(message)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(queue))
    queueSize.value = queue.length
  }

  function flushQueue(sendFn: (message: Partial<ChatMessage>) => void) {
    const queue = getQueue()
    if (queue.length === 0) return

    localStorage.removeItem(STORAGE_KEY)
    queueSize.value = 0

    for (const message of queue) {
      sendFn(message)
    }
  }

  function clearQueue() {
    localStorage.removeItem(STORAGE_KEY)
    queueSize.value = 0
  }

  return {
    isOnline,
    queueSize,
    enqueue,
    flushQueue,
    clearQueue
  }
}
