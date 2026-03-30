<template>
  <div ref="messagesContainer" class="chat-messages overflow-auto p-3">
    <!-- History loading -->
    <div v-if="loadingHistory" class="text-center py-3">
      <div class="spinner-border spinner-border-sm text-muted" role="status"></div>
      <small class="text-muted ms-2">이전 메시지 불러오는 중...</small>
    </div>

    <div
      v-for="message in messages"
      :key="message.id || message.messageId || message.timestamp"
      class="message-wrapper mb-3"
      :class="getMessageClass(message)"
    >
      <!-- AI 요약 메시지 -->
      <div v-if="message.type === 'AI_SUMMARY'" class="ai-summary-message">
        <div class="card border-info">
          <div class="card-header bg-info text-white d-flex align-items-center">
            <i class="bi bi-robot me-2"></i>
            <strong>AI 요약</strong>
            <small class="ms-auto">{{ formatTime(message.timestamp) }}</small>
          </div>
          <div class="card-body">
            <p class="card-text mb-0">{{ message.content }}</p>
          </div>
        </div>
      </div>

      <!-- 시스템 메시지 (입장/퇴장) -->
      <div v-else-if="message.type === 'JOIN' || message.type === 'LEAVE' || message.type === 'SYSTEM'" class="system-message text-center">
        <small class="text-muted fst-italic">
          <i class="bi bi-info-circle me-1"></i>
          {{ message.content }}
          <span class="ms-2">{{ formatTime(message.timestamp) }}</span>
        </small>
      </div>

      <!-- 일반 채팅 메시지 -->
      <div v-else class="chat-message">
        <div class="d-flex" :class="{ 'justify-content-end': isCurrentUser(message) }">
          <!-- 상대방 아바타 -->
          <div v-if="!isCurrentUser(message)" class="avatar me-2">
            <div class="bg-secondary rounded-circle d-flex align-items-center justify-content-center" style="width: 40px; height: 40px;">
              <small class="text-white fw-bold">{{ getInitials(message.username) }}</small>
            </div>
          </div>

          <!-- 메시지 내용 -->
          <div class="message-content" :class="{ 'text-end': isCurrentUser(message) }">
            <div v-if="!isCurrentUser(message)" class="message-header mb-1">
              <small class="text-muted fw-bold">{{ message.username }}</small>
            </div>
            
            <div 
              class="message-bubble px-3 py-2 rounded-3 d-inline-block"
              :class="{
                'bg-primary text-white': isCurrentUser(message),
                'bg-light': !isCurrentUser(message)
              }"
            >
              <div>{{ message.content }}</div>
              <div class="message-time mt-1">
                <small 
                  :class="{
                    'text-white-50': isCurrentUser(message),
                    'text-muted': !isCurrentUser(message)
                  }"
                >
                  {{ formatTime(message.timestamp) }}
                </small>
              </div>
            </div>
          </div>

          <!-- 내 아바타 -->
          <div v-if="isCurrentUser(message)" class="avatar ms-2">
            <div class="bg-primary rounded-circle d-flex align-items-center justify-content-center" style="width: 40px; height: 40px;">
              <small class="text-white fw-bold">{{ getInitials(message.username) }}</small>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 타이핑 인디케이터 -->
    <div v-if="isTyping" class="typing-indicator">
      <div class="d-flex align-items-center">
        <div class="avatar me-2">
          <div class="bg-secondary rounded-circle d-flex align-items-center justify-content-center" style="width: 40px; height: 40px;">
            <small class="text-white">...</small>
          </div>
        </div>
        <div class="typing-dots">
          <span></span>
          <span></span>
          <span></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import dayjs from 'dayjs'
import type { ChatMessage } from '@/types'

interface Props {
  messages: ChatMessage[]
  currentUser: string
  loadingHistory?: boolean
}

const props = defineProps<Props>()

const messagesContainer = ref<HTMLElement>()
const isTyping = ref(false)

const isCurrentUser = (message: ChatMessage) => {
  return message.username === props.currentUser
}

const getInitials = (name: string) => {
  return name.substring(0, 2).toUpperCase()
}

const formatTime = (timestamp: string) => {
  return dayjs(timestamp).format('HH:mm')
}

const getMessageClass = (message: ChatMessage) => {
  return {
    'current-user': isCurrentUser(message),
    'other-user': !isCurrentUser(message) && message.type === 'CHAT',
    'system-message': ['JOIN', 'LEAVE', 'SYSTEM'].includes(message.type),
    'ai-summary': message.type === 'AI_SUMMARY'
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

// 새 메시지가 추가되면 스크롤을 아래로
watch(() => props.messages.length, () => {
  scrollToBottom()
})
</script>

<style scoped>
.chat-messages {
  height: 100%;
  background-color: var(--bs-body-bg);
}

.message-bubble {
  max-width: 70%;
  word-wrap: break-word;
}

.current-user .message-bubble {
  background-color: var(--bs-primary) !important;
}

.other-user .message-bubble {
  background-color: var(--bs-light);
  color: var(--bs-body-color);
}

.ai-summary-message {
  margin: 1rem 0;
}

.typing-indicator {
  padding: 1rem 0;
}

.typing-dots {
  display: flex;
  align-items: center;
  gap: 4px;
}

.typing-dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--bs-secondary);
  animation: typing 1.5s infinite;
}

.typing-dots span:nth-child(2) {
  animation-delay: 0.5s;
}

.typing-dots span:nth-child(3) {
  animation-delay: 1s;
}

@keyframes typing {
  0%, 60%, 100% {
    transform: translateY(0);
  }
  30% {
    transform: translateY(-10px);
  }
}

/* 다크모드 지원 */
[data-bs-theme="dark"] .other-user .message-bubble {
  background-color: var(--bs-dark);
  color: var(--bs-light);
}
</style>