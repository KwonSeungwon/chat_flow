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
      />
      
      <ChatMessages 
        :messages="messages"
        :current-user="username"
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
import { useLocalStorage } from '@vueuse/core'
import { useWebSocket } from '@/composables/useWebSocket'
import type { ChatMessage, MessageType } from '@/types'

import ChatRoomSidebar from '@/components/ChatRoomSidebar.vue'
import ChatHeader from '@/components/ChatHeader.vue'
import ChatMessages from '@/components/ChatMessages.vue'
import ChatInput from '@/components/ChatInput.vue'
import AISummarySidebar from '@/components/AISummarySidebar.vue'

const route = useRoute()
const router = useRouter()
const username = useLocalStorage('chatflow-username', '')

// WebSocket 연결
const { isConnected, messages, connect, disconnect, sendMessage } = useWebSocket()

const currentRoomId = ref(route.params.roomId as string || 'general')
const onlineUsers = ref<string[]>([])

const joinRoom = (roomId: string) => {
  if (currentRoomId.value !== roomId) {
    disconnect()
    currentRoomId.value = roomId
    router.push(`/chat/${roomId}`)
    
    if (username.value) {
      connect(roomId, username.value)
    }
  }
}

const handleSendMessage = (content: string) => {
  const message: Partial<ChatMessage> = {
    chatRoomId: currentRoomId.value,
    userId: `user_${Date.now()}`,
    username: username.value,
    content,
    type: 'CHAT' as MessageType,
    timestamp: new Date().toISOString()
  }
  
  sendMessage(message)
}

// 라우트 변경 감지
watch(
  () => route.params.roomId,
  (newRoomId) => {
    if (newRoomId && newRoomId !== currentRoomId.value) {
      joinRoom(newRoomId as string)
    }
  }
)

// 사용자명 변경 감지
watch(username, (newUsername) => {
  if (newUsername && isConnected.value) {
    // 재연결
    disconnect()
    setTimeout(() => {
      connect(currentRoomId.value, newUsername)
    }, 100)
  }
})

onMounted(() => {
  if (!username.value) {
    username.value = `Guest_${Math.floor(Math.random() * 1000)}`
  }
  
  if (currentRoomId.value) {
    connect(currentRoomId.value, username.value)
  }
})
</script>

<style scoped>
.row {
  margin: 0;
}
</style>