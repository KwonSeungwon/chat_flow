<template>
  <div class="widget-container">
    <!-- 닉네임 입력 -->
    <div v-if="!joined" class="nickname-form">
      <div class="nickname-header">채팅 참여</div>
      <input
        v-model="nickname"
        placeholder="닉네임을 입력하세요"
        @keyup.enter="joinChat"
        class="nickname-input"
        maxlength="20"
      />
      <button @click="joinChat" class="join-btn">입장</button>
    </div>

    <!-- 채팅 영역 -->
    <div v-else class="chat-area">
      <div class="chat-header">
        <span class="room-name">{{ roomName }}</span>
        <span :class="['status-dot', isConnected ? 'online' : 'offline']"></span>
      </div>

      <div class="messages-container" ref="messagesContainer">
        <div
          v-for="msg in messages"
          :key="msg.messageId || msg.id"
          :class="['message', msg.username === nickname ? 'mine' : 'other']"
        >
          <div v-if="msg.type === 'JOIN' || msg.type === 'LEAVE'" class="system-msg">
            {{ msg.content }}
          </div>
          <template v-else>
            <div class="msg-username" v-if="msg.username !== nickname">{{ msg.username }}</div>
            <div class="msg-bubble">{{ msg.content }}</div>
          </template>
        </div>
      </div>

      <div class="input-area">
        <input
          v-model="inputText"
          placeholder="메시지를 입력하세요..."
          @keyup.ctrl.enter="sendMsg"
          class="msg-input"
          maxlength="500"
        />
        <button @click="sendMsg" :disabled="!inputText.trim() || !isConnected" class="send-btn">
          전송
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useWebSocket } from '@/composables/useWebSocket'
import { MessageType } from '@/types'

const route = useRoute()
const { isConnected, messages, connect, sendMessage } = useWebSocket()

const nickname = ref('')
const inputText = ref('')
const joined = ref(false)
const roomName = ref('')
const messagesContainer = ref<HTMLElement | null>(null)

const roomId = ref((route.query.roomId as string) || 'default')
const serverUrl = route.query.server as string

onMounted(() => {
  roomName.value = (route.query.title as string) || roomId.value
  // 익명 닉네임 자동 생성
  nickname.value = '익명_' + Math.random().toString(36).substring(2, 6)
})

const joinChat = () => {
  if (!nickname.value.trim()) return
  joined.value = true
  connect(roomId.value, nickname.value.trim())
}

const sendMsg = () => {
  if (!inputText.value.trim() || !isConnected.value) return
  sendMessage({
    chatRoomId: roomId.value,
    username: nickname.value,
    content: inputText.value.trim(),
    type: MessageType.CHAT
  })
  inputText.value = ''
}

watch(messages, () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}, { deep: true })
</script>

<style scoped>
.widget-container {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  font-size: 14px;
  background: #ffffff;
}

.nickname-form {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;
  padding: 20px;
}

.nickname-header {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.nickname-input {
  width: 100%;
  max-width: 240px;
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  outline: none;
  font-size: 14px;
}

.nickname-input:focus {
  border-color: #6366f1;
}

.join-btn {
  padding: 8px 24px;
  background: #6366f1;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}

.join-btn:hover {
  background: #4f46e5;
}

.chat-area {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
}

.room-name {
  font-weight: 600;
  font-size: 13px;
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.online { background: #10b981; }
.status-dot.offline { background: #ef4444; }

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message.mine { align-self: flex-end; }
.message.other { align-self: flex-start; }

.msg-username {
  font-size: 11px;
  color: #6b7280;
  margin-bottom: 2px;
}

.msg-bubble {
  padding: 6px 10px;
  border-radius: 12px;
  max-width: 240px;
  word-break: break-word;
  line-height: 1.4;
}

.mine .msg-bubble {
  background: #6366f1;
  color: white;
  border-bottom-right-radius: 4px;
}

.other .msg-bubble {
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-left-radius: 4px;
}

.system-msg {
  text-align: center;
  font-size: 11px;
  color: #9ca3af;
  font-style: italic;
}

.input-area {
  display: flex;
  gap: 6px;
  padding: 8px 10px;
  border-top: 1px solid #e5e7eb;
  background: #f9fafb;
}

.msg-input {
  flex: 1;
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  outline: none;
  font-size: 13px;
}

.msg-input:focus {
  border-color: #6366f1;
}

.send-btn {
  padding: 8px 14px;
  background: #6366f1;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}

.send-btn:disabled {
  background: #d1d5db;
  cursor: not-allowed;
}
</style>
