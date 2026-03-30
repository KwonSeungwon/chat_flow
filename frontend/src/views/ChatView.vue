<template>
  <div class="chat-layout h-100">
    <!-- 모바일 채팅방 사이드바 (오버레이) -->
    <div v-if="showMobileSidebar" class="mobile-sidebar-overlay" @click="showMobileSidebar = false">
      <div class="mobile-sidebar" @click.stop>
        <ChatRoomSidebar
          v-model:selected-room="currentRoomId"
          @room-selected="handleMobileRoomSelect"
        />
      </div>
    </div>

    <div class="row h-100 g-0">
      <!-- 채팅방 사이드바 (데스크톱) -->
      <div class="col-md-3 col-lg-2 d-none d-md-block border-end">
        <ChatRoomSidebar
          v-model:selected-room="currentRoomId"
          @room-selected="joinRoom"
        />
      </div>

      <!-- 메인 채팅 영역 -->
      <div class="col-12 col-md-9 col-lg-7 d-flex flex-column chat-main">
        <ChatHeader
          :room-id="currentRoomId"
          :is-connected="isConnected"
          :participants="onlineUsers"
          :username="auth.username"
          :is-guest="auth.isGuest"
          @logout="handleLogout"
          @toggle-sidebar="showMobileSidebar = !showMobileSidebar"
        />

        <ChatMessages
          :messages="allMessages"
          :current-user="auth.username"
          :loading-history="loadingHistory"
          class="flex-grow-1 min-h-0"
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
const showMobileSidebar = ref(false)

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

const handleMobileRoomSelect = (roomId: string) => {
  showMobileSidebar.value = false
  joinRoom(roomId)
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
.chat-layout {
  position: relative;
}

.row {
  margin: 0;
}

.chat-main {
  min-height: 0;
  max-height: 100%;
}

/* 모바일 사이드바 오버레이 */
.mobile-sidebar-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 1050;
}

.mobile-sidebar {
  position: absolute;
  top: 0;
  left: 0;
  bottom: 0;
  width: 280px;
  background: var(--bs-body-bg);
  box-shadow: 2px 0 8px rgba(0, 0, 0, 0.2);
  overflow-y: auto;
}

.min-h-0 {
  min-height: 0;
}

@media (max-width: 767px) {
  .chat-main {
    height: 100%;
  }
}
</style>
