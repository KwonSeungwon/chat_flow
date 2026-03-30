<template>
  <div class="chat-input border-top p-2">
    <!-- 이모지 피커 (input 위에) -->
    <div v-if="showEmojiPicker" class="emoji-picker mb-2">
      <div class="card">
        <div class="card-body p-2">
          <div class="d-flex justify-content-between align-items-center mb-1">
            <small class="text-muted fw-semibold">이모지</small>
            <button class="btn btn-sm btn-close" @click="showEmojiPicker = false"></button>
          </div>
          <div class="d-flex flex-wrap gap-1">
            <button
              v-for="emoji in commonEmojis"
              :key="emoji"
              class="btn btn-sm btn-outline-secondary emoji-btn"
              @click="insertEmoji(emoji)"
            >
              {{ emoji }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <form @submit.prevent="handleSubmit" class="d-flex gap-2 align-items-end">
      <div class="flex-grow-1">
        <div class="input-group">
          <textarea
            ref="messageInput"
            v-model="message"
            class="form-control"
            placeholder="메시지를 입력하세요..."
            :disabled="disabled"
            @keydown="handleKeyDown"
            @input="autoResize"
            rows="1"
            maxlength="1000"
          ></textarea>
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
        <div v-if="message.length > 800" class="text-end mt-1">
          <small class="text-muted">{{ message.length }}/1000</small>
        </div>
      </div>

      <button
        type="submit"
        class="btn btn-primary flex-shrink-0"
        :disabled="disabled || !message.trim()"
        title="전송 (Enter)"
      >
        <i class="bi bi-send"></i>
      </button>
    </form>
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
const messageInput = ref<HTMLTextAreaElement>()
const showEmojiPicker = ref(false)

const commonEmojis = [
  '😀', '😃', '😄', '😁', '😆', '😅', '🤣', '😂',
  '🙂', '😉', '😊', '😇', '🥰', '😍', '🤩', '😘',
  '😎', '🤓', '🤔', '😐', '😶', '😏', '😒', '😔',
  '👍', '👎', '👌', '✌️', '🤞', '🤟', '🤘', '🤙',
  '❤️', '🧡', '💛', '💚', '💙', '💜', '🤍', '🖤',
  '🔥', '⭐', '🎉', '💯', '✅', '❌', '🙏', '💪'
]

const handleSubmit = () => {
  const content = message.value.trim()
  if (content && !props.disabled) {
    emit('send-message', content)
    message.value = ''
    showEmojiPicker.value = false
    nextTick(() => {
      resetTextareaHeight()
      messageInput.value?.focus()
    })
  }
}

const handleKeyDown = (event: KeyboardEvent) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSubmit()
  }
  // Shift+Enter = new line (default textarea behavior)
}

const autoResize = () => {
  const el = messageInput.value
  if (el) {
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 120) + 'px'
  }
}

const resetTextareaHeight = () => {
  const el = messageInput.value
  if (el) {
    el.style.height = 'auto'
  }
}

const toggleEmojiPicker = () => {
  showEmojiPicker.value = !showEmojiPicker.value
}

const insertEmoji = (emoji: string) => {
  const el = messageInput.value
  if (el) {
    const start = el.selectionStart || 0
    const end = el.selectionEnd || 0
    const text = message.value
    message.value = text.substring(0, start) + emoji + text.substring(end)
    nextTick(() => {
      const pos = start + emoji.length
      el.setSelectionRange(pos, pos)
      el.focus()
    })
  } else {
    message.value += emoji
  }
  // 이모지 피커 열어둠 — 연속 입력 가능
}

nextTick(() => {
  messageInput.value?.focus()
})
</script>

<style scoped>
.chat-input {
  background-color: var(--bs-body-bg);
  flex-shrink: 0;
}

textarea.form-control {
  resize: none;
  min-height: 38px;
  max-height: 120px;
  overflow-y: auto;
  line-height: 1.4;
}

.emoji-picker {
  position: relative;
  z-index: 1000;
}

.emoji-btn {
  font-size: 1.2em;
  padding: 2px 6px;
  line-height: 1;
  border: none;
}

.emoji-btn:hover {
  background-color: var(--bs-light);
  transform: scale(1.2);
}

@media (max-width: 768px) {
  textarea.form-control {
    font-size: 16px; /* iOS zoom 방지 */
  }
}
</style>
