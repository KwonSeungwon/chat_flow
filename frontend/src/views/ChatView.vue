<template>
  <div class="row h-100 g-0">
    <!-- 채팅방 사이드바 (모바일에서는 숨김) -->
    <div class="col-md-3 col-lg-2 d-none d-md-block border-end">
      <ChatRoomSidebar
        v-model:selected-room="currentRoomId"
        @room-selected="joinRoom"
      />
    </div>

    <!-- 메인 채팅 영역 -->
    <div class="col-md-9 col-lg-7 d-flex flex-column">
      <ChatHeader
        :room-id="currentRoomId"
        :is-connected="isConnected"
        :participants="onlineUsers"
        :username="auth.username"
        :is-guest="auth.isGuest"
        @logout="handleLogout"
      />

      <ChatMessages
        :messages="allMessages"
        :current-user="auth.username"
        :loading-history="loadingHistory"
        class="flex-grow-1"
      />

      <ChatInput
        v-if="isConnected"
        @send-message="handleSendMessage"
        :disabled="!isConnected"
      />
    </div>

    <!-- AI 요약 사이드바 -->
    <div class="col-lg-3 d-none d-lg-block border-start">
      <AISummarySidebar :room-id="currentRoomId" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWebSocket } from '@/composables/useWebSocket'
import api from '@/utils/api'
import type { ChatMessage, MessageType } from '@/types'

import ChatRoomSidebar from '@/components/ChatRoomSidebar.vue'
import ChatHeader from '@/components/ChatHeader.vue'
import ChatMessages from '@/components/ChatMessages.vue'
import ChatInput from '@/components/ChatInput.vue'
import AISummarySidebar from '@/components/AISummarySidebar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const { isConnected, messages, connect, disconnect, sendMessage } = useWebSocket()

const currentRoomId = ref(route.params.roomId as string || 'general')
const onlineUsers = ref<string[]>([])
const historyMessages = ref<ChatMessage[]>([])
const loadingHistory = ref(false)

const allMessages = computed(() => {
  const history = historyMessages.value.filter(
    h => !messages.value.some(m => m.messageId === h.messageId)
  )
  return [...history, ...messages.value]
})

async function loadHistory(roomId: string) {
  loadingHistory.value = true
  try {
    const res = await api.get(`/api/chat/rooms/${roomId}/messages?size=50`)
    const page = res.data.data
    const items = (page.content || []).map((m: any) => ({
      ...m,
      type: m.type || m.messageType,
      isAiGenerated: m.aiGenerated || m.isAiGenerated || false
    }))
    historyMessages.value = items.reverse()
  } catch {
    historyMessages.value = []
  } finally {
    loadingHistory.value = false
  }
}

const joinRoom = (roomId: string) => {
  if (currentRoomId.value !== roomId) {
    disconnect()
    currentRoomId.value = roomId
    router.push(`/chat/${roomId}`)
    loadHistory(roomId)
    if (auth.username) {
      connect(roomId, auth.username)
    }
  }
}

const handleSendMessage = (content: string) => {
  const message: Partial<ChatMessage> = {
    chatRoomId: currentRoomId.value,
    userId: auth.userId || `user_${Date.now()}`,
    username: auth.username,
    content,
    type: 'CHAT' as MessageType,
    timestamp: new Date().toISOString()
  }
  sendMessage(message)
}

function handleLogout() {
  disconnect()
  auth.logout()
  router.push('/login')
}

watch(
  () => route.params.roomId,
  (newRoomId) => {
    if (newRoomId && newRoomId !== currentRoomId.value) {
      joinRoom(newRoomId as string)
    }
  }
)

onMounted(() => {
  if (currentRoomId.value) {
    loadHistory(currentRoomId.value)
    connect(currentRoomId.value, auth.username)
  }
})
</script>

<style scoped>
.row {
  margin: 0;
}
</style>
