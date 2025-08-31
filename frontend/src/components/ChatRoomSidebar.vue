<template>
  <div class="chat-room-sidebar h-100 d-flex flex-column">
    <div class="sidebar-header p-3 border-bottom">
      <h6 class="mb-0">
        <i class="bi bi-chat-dots me-2"></i>
        채팅방
      </h6>
    </div>
    
    <div class="room-list flex-grow-1 overflow-auto">
      <div 
        v-for="room in rooms" 
        :key="room.id"
        class="room-item p-3 border-bottom cursor-pointer"
        :class="{ active: selectedRoom === room.id }"
        @click="selectRoom(room.id)"
      >
        <div class="d-flex justify-content-between align-items-center">
          <div>
            <div class="fw-semibold">{{ room.name }}</div>
            <small class="text-muted">{{ room.participants }}명 참여중</small>
          </div>
          <div class="room-status">
            <span class="badge bg-success rounded-pill">{{ room.participants }}</span>
          </div>
        </div>
      </div>
    </div>
    
    <div class="sidebar-footer p-3 border-top">
      <button class="btn btn-outline-primary btn-sm w-100" @click="createRoom">
        <i class="bi bi-plus-circle me-1"></i>
        새 채팅방
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { ChatRoom } from '@/types'

interface Props {
  selectedRoom: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'room-selected': [roomId: string]
  'update:selected-room': [roomId: string]
}>()

const rooms = ref<ChatRoom[]>([
  { id: 'general', name: '일반', participants: 12, createdAt: '2024-01-01T00:00:00Z' },
  { id: 'tech', name: '기술 토론', participants: 8, createdAt: '2024-01-01T00:00:00Z' },
  { id: 'random', name: '자유 토론', participants: 15, createdAt: '2024-01-01T00:00:00Z' },
  { id: 'project', name: '프로젝트', participants: 5, createdAt: '2024-01-01T00:00:00Z' }
])

const selectRoom = (roomId: string) => {
  emit('update:selected-room', roomId)
  emit('room-selected', roomId)
}

const createRoom = () => {
  const roomName = prompt('새 채팅방 이름을 입력하세요:')
  if (roomName) {
    const newRoom: ChatRoom = {
      id: `room_${Date.now()}`,
      name: roomName,
      participants: 1,
      createdAt: new Date().toISOString()
    }
    rooms.value.push(newRoom)
    selectRoom(newRoom.id)
  }
}
</script>

<style scoped>
.chat-room-sidebar {
  background-color: var(--bs-body-bg);
  min-width: 250px;
}

.room-item {
  transition: background-color 0.2s;
  cursor: pointer;
}

.room-item:hover {
  background-color: var(--bs-light);
}

.room-item.active {
  background-color: var(--bs-primary);
  color: white;
}

.room-item.active .text-muted {
  color: rgba(255, 255, 255, 0.7) !important;
}

[data-bs-theme="dark"] .room-item:hover {
  background-color: var(--bs-dark);
}
</style>