<template>
  <div ref="messagesContainer" class="chat-messages overflow-auto p-2 p-md-3">
    <!-- History loading -->
    <div v-if="loadingHistory" class="text-center py-3">
      <div class="spinner-border spinner-border-sm text-muted" role="status"></div>
      <small class="text-muted ms-2">이전 메시지 불러오는 중...</small>
    </div>

    <div
      v-for="message in messages"
      :key="message.id || message.messageId || message.timestamp"
      class="mb-2"
    >
      <!-- AI 요약 메시지 -->
      <div v-if="message.type === 'AI_SUMMARY'" class="ai-summary-message my-2">
        <div class="card border-info">
          <div class="card-header bg-info text-white d-flex align-items-center py-2">
            <i class="bi bi-robot me-2"></i>
            <strong>AI 요약</strong>
            <span class="ms-auto time-text">{{ formatTime(message.timestamp) }}</span>
          </div>
          <div class="card-body py-2">
            <p class="card-text mb-0">{{ message.content }}</p>
          </div>
        </div>
      </div>

      <!-- 시스템 메시지 (입장/퇴장) -->
      <div v-else-if="isSystemMsg(message)" class="text-center my-1">
        <small class="text-muted fst-italic">
          {{ message.content }}
          <span class="time-text ms-1">{{ formatTime(message.timestamp) }}</span>
        </small>
      </div>

      <!-- 내 메시지 -->
      <div v-else-if="isCurrentUser(message)" class="d-flex justify-content-end">
        <div class="bubble mine">
          <div class="bubble-text">{{ message.content }}</div>
          <div class="bubble-time text-end">
            <span class="time-text text-white-50">{{ formatTime(message.timestamp) }}</span>
          </div>
        </div>
      </div>

      <!-- 상대 메시지 -->
      <div v-else class="d-flex align-items-start">
        <div class="avatar-circle me-2 flex-shrink-0">
          {{ getInitials(message.username) }}
        </div>
        <div class="bubble-wrap">
          <div class="bubble-username">{{ message.username }}</div>
          <div class="bubble other">
            <div class="bubble-text">{{ message.content }}</div>
            <div class="bubble-time">
              <span class="time-text text-muted">{{ formatTime(message.timestamp) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import type { ChatMessage } from '@/types'

dayjs.extend(utc)

interface Props {
  messages: ChatMessage[]
  currentUser: string
  loadingHistory?: boolean
}

const props = defineProps<Props>()
const messagesContainer = ref<HTMLElement>()

const isCurrentUser = (message: ChatMessage) => message.username === props.currentUser
const isSystemMsg = (message: ChatMessage) => ['JOIN', 'LEAVE', 'SYSTEM'].includes(message.type)
const getInitials = (name: string) => name.substring(0, 2).toUpperCase()

const formatTime = (timestamp: string) => {
  return dayjs.utc(timestamp).utcOffset(9).format('HH:mm')
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

watch(() => props.messages.length, () => scrollToBottom())
</script>

<style scoped>
.chat-messages {
  height: 100%;
  -webkit-overflow-scrolling: touch;
}

/* 아바타 */
.avatar-circle {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--bs-secondary);
  color: white;
  font-size: 0.65rem;
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* 유저네임 */
.bubble-username {
  font-size: 0.75rem;
  color: var(--bs-secondary-color);
  font-weight: 600;
  margin-bottom: 2px;
}

/* 버블 공통 */
.bubble {
  padding: 8px 12px;
  border-radius: 12px;
  max-width: min(75%, 400px);
  width: fit-content;
}

.bubble-wrap {
  min-width: 0;
  max-width: min(75%, 400px);
}

/* 내 메시지 */
.bubble.mine {
  background: var(--bs-primary);
  color: white;
  border-bottom-right-radius: 4px;
}

/* 상대 메시지 */
.bubble.other {
  background: var(--bs-light);
  color: var(--bs-body-color);
  border-bottom-left-radius: 4px;
  max-width: 100%;
}

/* 텍스트 줄바꿈 */
.bubble-text {
  word-break: break-word;
  overflow-wrap: break-word;
  white-space: pre-wrap;
  line-height: 1.45;
}

/* 시간 — 줄바꿈 금지 */
.time-text {
  font-size: 0.65rem;
  white-space: nowrap;
  letter-spacing: 0;
}

.bubble-time {
  margin-top: 2px;
}

/* 다크모드 */
[data-bs-theme="dark"] .bubble.other {
  background: var(--bs-dark);
  color: var(--bs-light);
}

/* 모바일 */
@media (max-width: 576px) {
  .bubble {
    max-width: min(82%, 300px);
    font-size: 0.9rem;
  }
  .bubble-wrap {
    max-width: min(82%, 300px);
  }
  .avatar-circle {
    width: 28px;
    height: 28px;
    font-size: 0.6rem;
  }
}
</style>
