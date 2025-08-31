<template>
  <div class="chat-input border-top p-3">
    <form @submit.prevent="handleSubmit" class="d-flex gap-2">
      <div class="flex-grow-1">
        <div class="input-group">
          <input
            ref="messageInput"
            v-model="message"
            type="text"
            class="form-control"
            placeholder="메시지를 입력하세요..."
            :disabled="disabled"
            @keydown="handleKeyDown"
            @keyup="handleKeyUp"
            maxlength="1000"
          >
          <button
            class="btn btn-outline-secondary"
            type="button"
            @click="toggleEmojiPicker"
            :disabled="disabled"
            title="이모지"
          >
            <i class="bi bi-emoji-smile"></i>
          </button>
        </div>
        
        <!-- 문자 수 표시 -->
        <div v-if="message.length > 800" class="text-end mt-1">
          <small class="text-muted">{{ message.length }}/1000</small>
        </div>
      </div>
      
      <button
        type="submit"
        class="btn btn-primary"
        :disabled="disabled || !message.trim()"
        title="전송 (Ctrl+Enter)"
      >
        <i class="bi bi-send"></i>
      </button>
    </form>

    <!-- 이모지 피커 (간단한 버전) -->
    <div v-if="showEmojiPicker" class="emoji-picker mt-2">
      <div class="card">
        <div class="card-body">
          <div class="d-flex flex-wrap gap-1">
            <button
              v-for="emoji in commonEmojis"
              :key="emoji"
              class="btn btn-sm btn-outline-secondary"
              @click="insertEmoji(emoji)"
            >
              {{ emoji }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 타이핑 상태 표시 -->
    <div v-if="isTyping" class="mt-2">
      <small class="text-muted">
        <i class="bi bi-three-dots"></i>
        입력 중...
      </small>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'

interface Props {
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false
})

const emit = defineEmits<{
  'send-message': [content: string]
  'typing-start': []
  'typing-stop': []
}>()

const message = ref('')
const messageInput = ref<HTMLInputElement>()
const showEmojiPicker = ref(false)
const isTyping = ref(false)
const typingTimer = ref<number>()

const commonEmojis = [
  '😀', '😃', '😄', '😁', '😆', '😅', '🤣', '😂',
  '🙂', '🙃', '😉', '😊', '😇', '🥰', '😍', '🤩',
  '😘', '😗', '😚', '😙', '😋', '😛', '😜', '🤪',
  '😎', '🤓', '🧐', '🤔', '🤨', '😐', '😑', '😶',
  '👍', '👎', '👌', '✌️', '🤞', '🤟', '🤘', '🤙',
  '❤️', '🧡', '💛', '💚', '💙', '💜', '🤍', '🖤'
]

const handleSubmit = () => {
  const content = message.value.trim()
  if (content && !props.disabled) {
    emit('send-message', content)
    message.value = ''
    stopTyping()
    
    // 포커스 유지
    nextTick(() => {
      messageInput.value?.focus()
    })
  }
}

const handleKeyDown = (event: KeyboardEvent) => {
  if (event.key === 'Enter') {
    if (event.ctrlKey || event.metaKey) {
      // Ctrl+Enter로 전송
      handleSubmit()
    }
    // 일반 Enter는 기본 동작 방지
    event.preventDefault()
  }
  
  startTyping()
}

const handleKeyUp = () => {
  // 타이핑 중지 타이머 리셋
  if (typingTimer.value) {
    clearTimeout(typingTimer.value)
  }
  
  typingTimer.value = setTimeout(() => {
    stopTyping()
  }, 1000) as unknown as number
}

const startTyping = () => {
  if (!isTyping.value) {
    isTyping.value = true
    emit('typing-start')
  }
}

const stopTyping = () => {
  if (isTyping.value) {
    isTyping.value = false
    emit('typing-stop')
    
    if (typingTimer.value) {
      clearTimeout(typingTimer.value)
    }
  }
}

const toggleEmojiPicker = () => {
  showEmojiPicker.value = !showEmojiPicker.value
}

const insertEmoji = (emoji: string) => {
  const input = messageInput.value
  if (input) {
    const start = input.selectionStart || 0
    const end = input.selectionEnd || 0
    const text = message.value
    
    message.value = text.substring(0, start) + emoji + text.substring(end)
    
    // 커서 위치 조정
    nextTick(() => {
      const newPosition = start + emoji.length
      input.setSelectionRange(newPosition, newPosition)
      input.focus()
    })
  } else {
    message.value += emoji
  }
  
  showEmojiPicker.value = false
}

// 컴포넌트가 마운트되면 입력창에 포커스
nextTick(() => {
  messageInput.value?.focus()
})
</script>

<style scoped>
.chat-input {
  background-color: var(--bs-body-bg);
  border-color: var(--bs-border-color);
}

.emoji-picker {
  position: relative;
  z-index: 1000;
}

.btn:disabled {
  opacity: 0.5;
}

/* 모바일에서 입력창 크기 조정 */
@media (max-width: 768px) {
  .input-group input {
    font-size: 16px; /* iOS에서 줌 방지 */
  }
}
</style>